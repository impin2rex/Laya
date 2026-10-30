import AVFoundation
import os
import Foundation
import MediaPlayer
import QuartzCore
import LayaKit
import UIKit

/// AVFoundation implementation of the Kotlin `AudioEngine`.
///
/// - Playback runs on an `AVQueuePlayer` holding the current item and, once
///   Kotlin has resolved it, the next one — so the next track starts by itself,
///   gaplessly, even with the screen locked.
/// - Media bytes go through `ChunkedResourceLoader` (bounded ranges, minting
///   client's headers), one loader per item.
/// - Owns the iOS side of a music app: audio session, interruptions (calls,
///   Siri, alarms), headphone unplug, and the lock screen / Control Center
///   Now Playing card with its remote commands.
final class AVAudioEngineImpl: NSObject, AudioEngine {
    /// Two decks: `player` is the one the app sees; `idle` holds the next track while a crossfade is armed or running.
    private var player = AVQueuePlayer()
    private var idle = AVQueuePlayer()
    private weak var listener: AudioEngineListener?

    private var timeObservers: [(AVQueuePlayer, Any)] = []
    private var deckObservations: [NSKeyValueObservation] = []

    /// Loader per queued item; an item's loader must live as long as the item.
    private var loaders: [ObjectIdentifier: ChunkedResourceLoader] = [:]
    private var speed: Float = 1
    /// Racing through a silent stretch (see EqualizerTap.listen).
    private var skipping = false
    private var currentItem: AVPlayerItem?
    private var nextItem: AVPlayerItem?
    /// Items already reported as failed, so the status, the notification and the loader don't report one failure three times.
    private var failedItems = Set<ObjectIdentifier>()
    /// Where the clock last stood still, and since when (see reportProgress).
    private var stuckAt: Double = -1
    private var stuckSince: CFTimeInterval = 0
    private var lastWatchedPosition: Double = -1

    private var statusObservation: NSKeyValueObservation?
    private var notificationTokens: [NSObjectProtocol] = []
    private var pendingSeekMs: Int64?
    /// Target of a seek still in flight. Reported as the position until it lands,
    /// so the timeline doesn't jump back to the old spot while the new range loads.
    private var seekingToMs: Int64?
    private var progressTicks = 0

    /// Paused by an interruption (call, Siri) rather than by the user, so it may resume after.
    private var pausedByInterruption = false
    /// Last reported playing state; by the time an interruption is announced iOS has already paused.
    private var wasPlaying = false

    private var nowPlaying: [String: Any] = [:]
    private var artworkTask: URLSessionDataTask?

    override init() {
        super.init()
        configureSession()
        observeDeck(player)
        observeDeck(idle)

        observeSystemEvents()
        configureRemoteCommands()
        let watchdog = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.checkStuckEnd() }
        RunLoop.main.add(watchdog, forMode: .common)
    }

    private func observeDeck(_ deck: AVQueuePlayer) {
        deck.automaticallyWaitsToMinimizeStalling = true
        deck.actionAtItemEnd = .advance

        let observer = deck.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4),
            queue: .main
        ) { [weak self, weak deck] _ in
            guard let self, let deck, deck === self.player else { return }
            self.reportProgress()
            // The lock screen extrapolates between updates; re-anchor it every few
            // seconds so buffering stalls don't leave it running ahead.
            self.progressTicks += 1
            if self.progressTicks % 20 == 0 { self.updateNowPlayingTiming() }
        }
        timeObservers.append((deck, observer))

        // The queue player moved on to the item queued with setNext.
        deckObservations.append(deck.observe(\.currentItem, options: [.old, .new]) { [weak self] deck, change in
            DispatchQueue.main.async {
                guard let self else { return }
                if deck === self.player {
                    self.currentItemChanged(from: change.oldValue ?? nil)
                }
            }
        })
        deckObservations.append(deck.observe(\.timeControlStatus, options: [.new]) { [weak self] deck, _ in
            DispatchQueue.main.async {
                guard let self, deck === self.player else { return }
                self.reportProgress()
                self.updateNowPlayingTiming()
            }
        })
    }

    // MARK: - AudioEngine

    /// Kotlin calls from its IO threads as well as main, and a deck's queue and the fade state below are main-only.
    /// Every call goes through main's FIFO, so their order is kept.
    private func onMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread { body() } else { DispatchQueue.main.async(execute: body) }
    }

    func setListener(listener: AudioEngineListener?) {
        self.listener = listener
    }

    func play(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        onMain { self.playOnMain(url: url, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes) }
    }

    private func playOnMain(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        stop()
        IosDsp.shared.reset()
        setSkipping(false)
        guard let item = makeItem(url: url, headers: headers, mimeType: mimeType,
                                  contentLength: contentLength, chunkBytes: chunkBytes) else {
            listener?.onError(message: "Bad stream URL", httpStatus: 0)
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        becomeCurrent(item)
        player.insert(item, after: nil)
        go()
    }

    func setNext(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        onMain { self.setNextOnMain(url: url, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes) }
    }

    private func setNextOnMain(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        if let f = fade, !f.swapped { return } // ponytail: a queue edit mid-fade keeps the track already fading in
        clearNextOnMain()
        guard currentItem != nil else { return }
        pendingNext = PendingNext(url: url, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes)
        materializeNextIfDue()
    }

    private struct PendingNext { let url: String; let headers: [String: String]; let mimeType: String; let contentLength: Int64; let chunkBytes: Int64 }

    /// The next track, held until the current one nears its end. AVQueuePlayer starts loading a queued item at once,
    /// so a track queued at the start of the current one opened a second connection to the same host for minutes;
    /// addon CDNs answer that with HTTP 429. Android's player likewise loads the next item only near the end.
    private var pendingNext: PendingNext?

    private func materializeNextIfDue(force: Bool = false) {
        guard let p = pendingNext, let current = currentItem, swapItem == nil else { return }
        if !force {
            let duration = streamDuration(of: current)
            let position = current.currentTime().seconds
            guard current.status == .readyToPlay, duration.isFinite, duration > 0, position.isFinite else { return }
            // Early enough for the blend's window when one is planned, and for the item to buffer before it is needed.
            let lead = max(45, transition.startMs >= 0 ? duration - Double(transition.startMs) / 1000 + 20 : Double(transition.fadeMs) / 1000 + 25)
            guard duration - position <= lead else { return }
        }
        pendingNext = nil
        guard let item = makeItem(url: p.url, headers: p.headers, mimeType: p.mimeType,
                                  contentLength: p.contentLength, chunkBytes: p.chunkBytes) else { return }
        nextItem = item
        fadeSkipped = false
        place(item)
        updateFadeTimer()
    }

    func clearNext() { onMain { self.clearNextOnMain() } }

    private func clearNextOnMain() {
        if let f = fade, !f.swapped { return }
        pendingNext = nil
        guard let item = nextItem else { return }
        nextItem = nil
        if parked { idle.removeAllItems(); parked = false } else { player.remove(item) }
        dropItem(item)
        updateFadeTimer()
    }

    private func dropItem(_ item: AVPlayerItem) {
        loaders.removeValue(forKey: ObjectIdentifier(item))?.cancelAll()
    }

    func pause() { onMain { self.pauseOnMain() } }

    private func pauseOnMain() {
        cancelSwap()
        pausedByInterruption = false
        resumeAfterSeek = false
        player.pause()
        if fade != nil { idle.pause() }
        reportProgress()
        updateNowPlayingTiming()
    }

    func resume() {
        try? AVAudioSession.sharedInstance().setActive(true)
        go()
        reportProgress()
    }

    func seekTo(positionMs: Int64) { onMain { self.seekOnMain(positionMs: positionMs) } }

    private func seekOnMain(positionMs: Int64) {
        cancelSwap()
        cancelFade()
        guard let item = player.currentItem, item.status == .readyToPlay else {
            pendingSeekMs = positionMs
            return
        }
        if let loader = loaders[ObjectIdentifier(item)], !loader.precise, loader.flacInfo != nil {
            // The reopen is this seek, made exactly; before the file is in, it lands on the estimate and is put right then.
            if loader.flacOnDisk, fade == nil { return reopenPrecise(item, loader, at: positionMs) }
            loader.estimatedSeek = true
        }
        let time = CMTime(value: positionMs, timescale: 1000)
        // Exact, as ExoPlayer seeks. With any tolerance AVPlayer may place a FLAC with no SEEKTABLE (most addon and
        // downloaded files) by a byte estimate and still report the asked-for time: audio and lyrics land off.
        let tolerance = CMTime.zero
        seekingToMs = positionMs
        lastWatchedPosition = -1 // a seek back is not the tail replaying
        IosDsp.shared.reset()
        reportProgress()
        player.seek(to: time, toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] _ in
            guard let self else { return }
            if self.seekingToMs == positionMs { self.seekingToMs = nil }
            if self.resumeAfterSeek { self.resumeAfterSeek = false; self.go() }
            print("AudioEngine: seek landed requested=\(Double(positionMs) / 1000)s currentTime=\(self.player.currentTime().seconds)s")
            self.reportProgress()
            self.updateNowPlayingTiming()
        }
    }

    func stop() {
        cancelSwap(resume: false)
        pendingNext = nil
        for deck in [player, idle] { deck.pause(); deck.removeAllItems(); deck.volume = 1 }
        fade = nil
        easing = false
        IosTransition.shared.end()
        parked = false
        fadeSkipped = false
        transition = Transition()
        EqualizerTuning.fades = false
        updateFadeTimer()
        statusObservation = nil
        currentItem = nil
        nextItem = nil
        loaders.values.forEach { $0.cancelAll() }
        loaders.removeAll()
        pendingSeekMs = nil
        seekingToMs = nil
        reopening = nil
        resumeAfterSeek = false
        pausedByInterruption = false
    }

    /// Play at the chosen speed (play() alone would reset the rate to 1).
    private func go() {
        player.play()
        player.rate = deckRate(player)
        if fade != nil { idle.play(); idle.rate = deckRate(idle) }
    }

    /// What a deck should run at: the listener's speed, with the blend's tempo match on the decks it applies to.
    private func deckRate(_ deck: AVQueuePlayer) -> Float {
        if let f = fade {
            if deck === f.inn { return f.inSpeed }
            if deck === f.out && f.swapped == false { return speed * transition.outRate }
        }
        return skipping ? speed * 4 : speed
    }

    func setSpeed(speed: Float) {
        self.speed = speed
        applyRate()
        updateNowPlayingTiming()
    }

    private func applyRate() {
        if player.timeControlStatus != .paused { player.rate = skipping ? speed * 4 : speed }
    }

    private func setSkipping(_ on: Bool) {
        EqualizerTuning.skipping = on
        guard on != skipping else { return }
        skipping = on
        applyRate()
    }

    func setEffects(dsp: Bool, skipSilence: Bool) {
        EqualizerTuning.dsp = dsp
        EqualizerTuning.skipSilence = skipSilence
        EqualizerTuning.onSkipping = { [weak self] on in DispatchQueue.main.async { self?.setSkipping(on) } }
        if !skipSilence { setSkipping(false) }
        if EqualizerTuning.wanted { [currentItem, nextItem].forEach { $0.map(EqualizerTap.attach) } }
    }

    func setEqualizer(gainsDb: [KotlinFloat], qs: [KotlinFloat], preampDb: Float, balance: Float) {
        func floats(_ a: [KotlinFloat]) -> [Float] { a.map { $0.floatValue } }
        EqualizerTuning.set(gains: floats(gainsDb), qs: floats(qs), preampDb: preampDb, balance: balance)
        if EqualizerTuning.wanted { [currentItem, nextItem].forEach { $0.map(EqualizerTap.attach) } }
    }

    func setNowPlaying(title: String, artist: String, album: String?, artworkUrl: String?) {
        artworkTask?.cancel()
        nowPlaying = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPNowPlayingInfoPropertyPlaybackRate: 0.0,
        ]
        if let album { nowPlaying[MPMediaItemPropertyAlbumTitle] = album }
        // Fill in duration/position/rate from the player right away: after an
        // automatic advance the new item is already ready, so no status change
        // will come along to do it.
        updateNowPlayingTiming()

        guard let artworkUrl else { return }
        let expectedTitle = title
        // Offline artwork is a plain path (downloads and imports keep covers on disk).
        if artworkUrl.hasPrefix("/") {
            if let image = UIImage(contentsOfFile: artworkUrl) {
                nowPlaying[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlaying
            }
            return
        }
        guard let url = URL(string: artworkUrl) else { return }
        artworkTask = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.nowPlaying[MPMediaItemPropertyTitle] as? String == expectedTitle else { return }
                self.nowPlaying[MPMediaItemPropertyArtwork] =
                    MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = self.nowPlaying
            }
        }
        artworkTask?.resume()
    }

    func setQueueCapabilities(hasNext: Bool, hasPrevious: Bool) {
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = hasNext
        center.previousTrackCommand.isEnabled = hasPrevious
    }

    // MARK: - System volume

    private var volumeObservation: NSKeyValueObservation?
    private lazy var volumeView: MPVolumeView = {
        let v = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 100, height: 40)) // offscreen: only its slider is used
        v.alpha = 0.01
        return v
    }()

    func systemVolume() -> Float {
        if volumeObservation == nil {
            volumeObservation = AVAudioSession.sharedInstance().observe(\.outputVolume, options: [.new]) { [weak self] _, change in
                DispatchQueue.main.async { self?.listener?.onVolumeChanged(level: change.newValue ?? 1) }
            }
        }
        return AVAudioSession.sharedInstance().outputVolume
    }

    /// [name, portType (AVAudioSession.Port raw value), sampleRateHz, outputChannels, ioBufferMs] of the current route's first output; the Kotlin bindings don't expose these.
    func outputRoute() -> [String] {
        let session = AVAudioSession.sharedInstance()
        let port = session.currentRoute.outputs.first
        return [port?.portName ?? "", port?.portType.rawValue ?? "",
                String(Int(session.sampleRate)), String(session.outputNumberOfChannels),
                String(Int(session.ioBufferDuration * 1000))]
    }

    func setSystemVolume(level: Float) {
        // The slider only exists once the view is in a window.
        if volumeView.superview == nil {
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            (scene?.windows.first(where: \.isKeyWindow) ?? scene?.windows.first)?.addSubview(volumeView)
        }
        (volumeView.subviews.first { $0 is UISlider } as? UISlider)?.value = level
    }

    // MARK: - Items

    /// Without this, AVFoundation places a seek in a FLAC with no SEEKTABLE (most addon and downloaded files) by a byte
    /// estimate, seconds out, while reporting the asked-for time: audio, lyrics and seekbar part ways. With it, it scans
    /// the frames first and seeks exactly; that scan reads the whole file, so it is only asked for once the file is all
    /// on disk. Measured on a no-SEEKTABLE FLAC through a resource loader: -2 s to +4 s off without, under 30 ms with.
    private static let precise: [String: Any] = [AVURLAssetPreferPreciseDurationAndTimingKey: true]

    private func makeItem(url: String, headers: [String: String], mimeType: String,
                          contentLength: Int64, chunkBytes: Int64) -> AVPlayerItem? {
        guard let source = URL(string: url) else { return nil }
        // A downloaded or imported file: AVFoundation reads it directly.
        if source.isFileURL {
            let flac = (try? FileHandle(forReadingFrom: source))
                .map { h in defer { try? h.close() }; return h.readData(ofLength: 4) == Data("fLaC".utf8) } ?? false
            let item = AVPlayerItem(asset: AVURLAsset(url: source, options: flac ? Self.precise : nil))
            item.preferredForwardBufferDuration = 30
            prepare(item)
            return item
        }
        let loader = ChunkedResourceLoader(
            source: source,
            headers: headers,
            mimeType: mimeType,
            knownLength: contentLength > 0 ? contentLength : nil,
            chunkBytes: max(64 * 1024, chunkBytes)
        )
        // A FLAC already fetched whole (a replay) is precise from the start; one still downloading is reopened later.
        loader.precise = StreamCache.shared.isCompleteFlac(source)
        let asset = AVURLAsset(url: loader.proxyURL, options: loader.precise ? Self.precise : nil)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        prepare(item)
        adopt(loader, for: item)
        return item
    }

    /// The precise copy of the playing item while it takes over (see reopenPrecise).
    private var reopening: AVPlayerItem?
    /// Paused for a reopen: playback goes on once its seek lands.
    private var resumeAfterSeek = false

    /// Replaces the playing FLAC [item] with the same stream reopened precisely, now its file is all on disk, at [ms].
    private func reopenPrecise(_ item: AVPlayerItem, _ loader: ChunkedResourceLoader, at ms: Int64) {
        let fresh = loader.reopened()
        fresh.precise = true
        let asset = AVURLAsset(url: fresh.proxyURL, options: Self.precise)
        asset.resourceLoader.setDelegate(fresh, queue: fresh.queue)
        let new = AVPlayerItem(asset: asset)
        new.preferredForwardBufferDuration = item.preferredForwardBufferDuration
        prepare(new)
        adopt(fresh, for: new)
        print("AudioEngine: reopening the FLAC with precise timing at \(Double(ms) / 1000)s")
        reopening = new
        resumeAfterSeek = player.timeControlStatus != .paused
        player.pause() // nothing plays from 0 before the seek below lands
        player.insert(new, after: item)
        player.advanceToNextItem() // currentItemChanged hands it the seek
        pendingSeekMs = ms
        seekingToMs = ms
    }

    private func adopt(_ loader: ChunkedResourceLoader, for item: AVPlayerItem) {
        loader.onHTTPError = { [weak self, weak item] status in
            DispatchQueue.main.async {
                // Only the playing item's refusal is actionable; a queued one is
                // re-resolved by Kotlin when it becomes current and fails.
                guard let self, let item else { return }
                self.fail(item, message: "Stream refused (HTTP \(status))", httpStatus: Int32(status))
            }
        }
        loaders[ObjectIdentifier(item)] = loader
    }

    /// The playing item can't go on. AVQueuePlayer would quietly move to the queued next track
    /// (a skip nobody asked for), so the queue is held here and Kotlin decides: retry, or move on.
    /// A queued item's failure is left for when it becomes current.
    private func fail(_ item: AVPlayerItem, message: String, httpStatus: Int32) {
        guard item === currentItem else { return }
        // AVFoundation reads some fragmented MP4s as twice their length (see ChunkedResourceLoader.durationHint), so
        // the real end arrives as a failure. That is the track ending, not breaking: move on rather than retry it.
        if atStatedEnd(item) { return endByHint(item) }
        guard failedItems.insert(ObjectIdentifier(item)).inserted else { return }
        let ns = item.error as NSError?
        let under = ns?.userInfo[NSUnderlyingErrorKey] as? NSError
        let loader = loaders[ObjectIdentifier(item)]
        print("AudioEngine: item failed (free \(os_proc_available_memory() / 1_048_576) MB): \(message) [\(ns?.domain ?? "-") \(ns?.code ?? 0) / \(under?.domain ?? "-") \(under?.code ?? 0)] length=\(loader?.knownLength.map(String.init) ?? "?") mime=\(loader?.mimeType ?? "?") log=\(item.errorLog()?.events.last.map { "\($0.errorStatusCode) \($0.errorDomain) \($0.errorComment ?? "")" } ?? "-")")
        cancelFade()
        clearNextOnMain()
        player.pause()
        listener?.onError(message: message, httpStatus: httpStatus)
    }

    private func hasFailed(_ item: AVPlayerItem) -> Bool {
        !atStatedEnd(item) && (item.status == .failed || item.error != nil || failedItems.contains(ObjectIdentifier(item)))
    }

    /// Within the last seconds of the length the stream states (only googlevideo URLs state one), or, for any source,
    /// with the whole file already delivered: a failure or stall then can only be the end of the audio.
    private func atStatedEnd(_ item: AVPlayerItem) -> Bool {
        guard let loader = loaders[ObjectIdentifier(item)] else { return false }
        if let hint = loader.durationHint, hint > 0 { return item.currentTime().seconds >= hint - 3 }
        return loader.deliveredEnd && item.currentTime().seconds > 0
    }

    /// Twice a second, independent of the periodic observer (which goes quiet when playback stalls): a track whose
    /// clock has reached its end but that AVPlayer never ends (it misjudged the length and waits for bytes that don't
    /// exist, or keeps replaying its tail) is ended here. Any source; the stated length (dur=) is handled in reportProgress.
    private func checkStuckEnd() {
        materializeNextIfDue()
        // A FLAC seeked while it downloaded plays from AVFoundation's estimate: once the file is in, reopen it where the
        // timeline (and the lyrics) say it is, so the audio comes back to them.
        if let item = currentItem, item === player.currentItem, fade == nil, seekingToMs == nil, item.status == .readyToPlay,
           let loader = loaders[ObjectIdentifier(item)], loader.estimatedSeek, loader.flacOnDisk {
            return reopenPrecise(item, loader, at: Int64(item.currentTime().seconds * 1000))
        }
        guard let item = player.currentItem, item === currentItem, seekingToMs == nil, fade == nil,
              player.timeControlStatus != .paused else { stuckAt = -1; lastWatchedPosition = -1; return }
        let position = player.currentTime().seconds
        let duration = streamDuration(of: item)
        guard position.isFinite, duration.isFinite, duration > 0 else { return }
        let delivered = loaders[ObjectIdentifier(item)]?.deliveredEnd ?? item.asset is AVURLAsset
        // The tail replaying: the clock jumping back near the end with nobody seeking.
        let replaying = delivered && lastWatchedPosition >= duration - 10 && position < lastWatchedPosition - 0.5
        lastWatchedPosition = position
        if replaying {
            print("AudioEngine: tail replaying (back to \(position)s of \(duration)s); ending the track")
            stuckAt = -1
            return endByHint(item)
        }
        // Past the end only by a length the stream states exactly (dur=, or FLAC's STREAMINFO); AVFoundation's own
        // estimate can be short or long, so it never cuts a track by itself.
        let exact = exactDuration(of: item)
        let overrun = exact.map { position >= $0 - 0.05 } ?? false
        let atEnd = delivered && position >= duration - 2
        guard overrun || atEnd else { stuckAt = -1; return }
        if overrun || (abs(position - stuckAt) <= 0.05 && CACurrentMediaTime() - stuckSince > 1.5) {
            print("AudioEngine: \(overrun ? "past" : "stuck at") the end (\(position)s of \(duration)s, \(player.timeControlStatus.rawValue)); ending the track")
            stuckAt = -1
            return endByHint(item)
        }
        if abs(position - stuckAt) > 0.05 { stuckAt = position; stuckSince = CACurrentMediaTime() }
    }

    /// The track's length as its source states it, when one does: googlevideo's dur=, or a FLAC's STREAMINFO.
    private func exactDuration(of item: AVPlayerItem) -> Double? {
        guard let loader = loaders[ObjectIdentifier(item)] else { return nil }
        if let hint = loader.durationHint, hint > 0 { return hint }
        if let f = loader.flacInfo, f.totalSamples > 0, f.sampleRate > 0 { return Double(f.totalSamples) / Double(f.sampleRate) }
        return nil
    }

    /// [item] has played the whole of its stated length: on to the queued track as a natural end would.
    private func endByHint(_ item: AVPlayerItem) {
        guard item === currentItem, fade == nil else { return }
        print("AudioEngine: track over at \(item.currentTime().seconds)s (parked=\(parked), next=\(nextItem != nil), held=\(pendingNext != nil))")
        materializeNextIfDue(force: true)
        if parked { return handOverNow() }
        if let next = nextItem, player.items().contains(next) { return player.advanceToNextItem() }
        player.pause()
        currentItem = nil
        dropItem(item)
        listener?.onEnded()
    }

    private func prepare(_ item: AVPlayerItem) {
        item.audioTimePitchAlgorithm = .spectral
        if EqualizerTuning.wanted { EqualizerTap.attach(to: item) }
    }

    /// Point the status observation and the end/fail notifications at [item].
    private func becomeCurrent(_ item: AVPlayerItem) {
        currentItem = item
        lastWatchedPosition = -1
        stuckAt = -1
        EqualizerTuning.dspKey = UnsafeRawPointer(Unmanaged.passUnretained(item).toOpaque())
        IosDsp.shared.setCurrent(key: Self.key(of: item))
        seekingToMs = nil
        // .initial: a queued item is usually ready before it becomes current.
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, item === self.currentItem else { return }
                switch item.status {
                case .readyToPlay:
                    if let ms = self.pendingSeekMs {
                        self.pendingSeekMs = nil
                        self.seekTo(positionMs: ms)
                    }
                    self.updateNowPlayingTiming()
                    self.reportAudioFormat(item)
                case .failed:
                    self.fail(item, message: item.error?.localizedDescription ?? "Playback failed", httpStatus: 0)
                default:
                    break
                }
            }
        }
    }

    /// What the decoder was handed, for the player's nerd stats (codec, rate, channels, bitrate).
    private func reportAudioFormat(_ item: AVPlayerItem) {
        guard let track = item.tracks.first(where: { $0.assetTrack?.mediaType == .audio })?.assetTrack,
              let desc = (track.formatDescriptions as? [CMFormatDescription])?.first,
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc)?.pointee else { return }
        let mime: String?
        switch CMFormatDescriptionGetMediaSubType(desc) {
        case kAudioFormatMPEG4AAC: mime = "audio/mp4a-latm"
        case kAudioFormatAppleLossless: mime = "audio/alac"
        case kAudioFormatFLAC: mime = "audio/flac"
        case kAudioFormatOpus: mime = "audio/opus"
        case kAudioFormatMPEGLayer3: mime = "audio/mpeg"
        default: mime = nil
        }
        var rate = Int32(asbd.mSampleRate), channels = Int32(asbd.mChannelsPerFrame), bits = Int32(asbd.mBitsPerChannel)
        var kbps = Int32(track.estimatedDataRate / 1000)
        let loader = loaders[ObjectIdentifier(item)]
        if let flac = loader?.flacInfo {
            // The decoder's description of FLAC carries no depth; the file's own STREAMINFO does.
            rate = Int32(flac.sampleRate); channels = Int32(flac.channels); bits = Int32(flac.bitDepth)
        } else if mime == "audio/alac" {
            // ALAC states the source depth in the format flags (1=16, 2=20, 3=24, 4=32).
            bits = [1: 16, 2: 20, 3: 24, 4: 32][Int32(asbd.mFormatFlags)] ?? 0
        }
        // Lossless average bitrate = bytes served over runtime, measured here rather than claimed by an addon.
        if let length = loader?.streamLength {
            let seconds = loader?.flacInfo.map { Double($0.totalSamples) / Double(max($0.sampleRate, 1)) } ?? item.duration.seconds
            if seconds.isFinite, seconds > 0 { kbps = Int32(Double(length) * 8 / seconds / 1000) }
        }
        listener?.onAudioFormat(mimeType: mime, sampleRateHz: rate, channels: channels, bitDepth: bits, bitrateKbps: kbps)
    }

    private func currentItemChanged(from old: AVPlayerItem?) {
        let now = player.currentItem
        if old !== now {
            print("AudioEngine: item changed (old at \(old?.currentTime().seconds ?? -1)s status=\(old?.status.rawValue ?? -1) err=\(old?.error?.localizedDescription ?? "-"), next=\(now === nextItem), parked=\(parked))")
        }
        if let fresh = reopening, now === fresh {
            // The same track reopened precisely (reopenPrecise), not the next one.
            reopening = nil
            if let old { dropItem(old) }
            becomeCurrent(fresh) // its status observer makes the pending seek once it is ready
            seekingToMs = pendingSeekMs
            return
        }
        if let old, old !== now, old === currentItem, hasFailed(old) {
            // The queue moved past a track that broke rather than ended. Undo that and let Kotlin recover it.
            cancelFade()
            if let now, now === nextItem { player.remove(now); dropItem(now); nextItem = nil } else { clearNextOnMain() }
            player.pause()
            dropItem(old)
            if failedItems.insert(ObjectIdentifier(old)).inserted {
                listener?.onError(message: old.error?.localizedDescription ?? "Playback stopped", httpStatus: 0)
            }
            return
        }
        if let old, old !== now {
            dropItem(old)
        }
        guard let now else {
            // Queue ran dry: the last item ended (or everything was removed by stop()).
            if old != nil && currentItem != nil {
                if let f = fade { return finishFade(f) } // the track ran out under its own fade
                if parked { return handOverNow() } // no fade happened: the parked track starts now
                currentItem = nil
                listener?.onEnded()
            }
            return
        }
        if now === nextItem {
            IosDsp.shared.reset()
            setSkipping(false)
            nextItem = nil
            becomeCurrent(now)
            listener?.onAdvancedToNext()
        }
    }

    /// The stream's stated length when it has one (googlevideo's dur=, a FLAC's STREAMINFO), else AVFoundation's,
    /// which for a FLAC opened without precise timing is an estimate: the seekbar's scale.
    private func streamDuration(of item: AVPlayerItem) -> Double {
        exactDuration(of: item) ?? item.duration.seconds
    }

    private func reportProgress() {
        guard let item = player.currentItem else { return }
        let position = seekingToMs.map { Double($0) / 1000 } ?? player.currentTime().seconds
        let duration = streamDuration(of: item)
        let isPlaying = player.timeControlStatus == .playing
        let isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        // Past the stated end and still "playing" or waiting for bytes that don't exist (the doubled length above):
        // the track is over. Not while seeking, or while a blend owns the hand-over.
        if let hint = loaders[ObjectIdentifier(item)]?.durationHint, seekingToMs == nil, fade == nil,
           position >= hint - 0.05, isPlaying || isBuffering {
            return endByHint(item)
        }
        wasPlaying = isPlaying || isBuffering
        listener?.onProgress(
            positionMs: position.isFinite ? Int64(position * 1000) : 0,
            durationMs: duration.isFinite ? Int64(duration * 1000) : 0,
            isPlaying: isPlaying,
            isBuffering: isBuffering
        )
    }


    // MARK: - Source swap

    /// The other copy of the playing track (a better source landing mid-song), loading and cued on the idle deck.
    private var swapItem: AVPlayerItem?
    private var swapObservation: NSKeyValueObservation?
    private var swapGen = 0

    /// Replaces the playing track with another copy of it, at the same position, without stopping the audio: the copy
    /// loads and seeks on the idle deck while the old one keeps playing, starts on the clock at the moment the old one
    /// reaches the same point, and takes over with a short crossfade (Android's smoothSwapCurrentTrackVersion).
    /// Gives up quietly (the old copy plays on) if anything gets in the way: a seek, a pause, a blend, a stop.
    func swapCurrent(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        onMain { self.swapOnMain(url: url, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes) }
    }

    private func swapOnMain(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        cancelSwap()
        guard fade == nil, !parked, let old = currentItem, player.currentItem === old, old.status == .readyToPlay,
              let item = makeItem(url: url, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes)
        else { return print("AudioEngine: source swap skipped") }
        clearNextOnMain() // Kotlin queues it again once the swap is in
        swapItem = item
        swapGen += 1
        let gen = swapGen
        let deck = idle
        deck.removeAllItems()
        deck.volume = 0
        deck.insert(item, after: nil)
        deck.pause()
        swapObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, gen == self.swapGen else { return }
                if item.status == .readyToPlay {
                    self.swapObservation = nil
                    self.swapAlign(item, deck, gen, attempt: 0)
                } else if item.status == .failed {
                    print("AudioEngine: source swap failed to load; staying on the playing copy")
                    self.cancelSwap()
                }
            }
        }
    }

    /// Cues the copy a moment ahead of the playing one and starts it at the host time the old one gets there.
    private func swapAlign(_ item: AVPlayerItem, _ deck: AVQueuePlayer, _ gen: Int, attempt: Int) {
        guard gen == swapGen, player.currentItem === currentItem else { return }
        let rate = max(speed, 0.1)
        let running = player.timeControlStatus != .paused
        // A FLAC cue is far more data than an Opus one; the lead grows each time the copy can't get there in time.
        let target = player.currentTime().seconds + (running ? (1.5 + 1.5 * Double(attempt)) * Double(rate) : 0)
        func retry() {
            if attempt >= 3 { print("AudioEngine: source swap could not line up; staying on the playing copy"); return cancelSwap() }
            swapAlign(item, deck, gen, attempt: attempt + 1)
        }
        deck.seek(to: CMTime(seconds: target, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] done in
            DispatchQueue.main.async {
                guard let self, gen == self.swapGen, done else { return }
                guard running else { return self.swapCommit(deck, item, gen) }
                // Buffered at the cue before it is asked to sound. Started cold, a streaming FLAC stalls at once, and a deck
                // started with automaticallyWaitsToMinimizeStalling off stays stopped after a stall (Play had to be pressed).
                // preroll throws unless the deck is ready and stopped.
                guard deck.status == .readyToPlay, item.status == .readyToPlay, deck.rate == 0 else { return retry() }
                deck.preroll(atRate: rate) { [weak self] _ in
                    DispatchQueue.main.async {
                        guard let self, gen == self.swapGen else { return }
                        let wait = (target - self.player.currentTime().seconds) / Double(rate) // the buffering may have outlasted the lead
                        guard wait > 0.05 else { return retry() }
                        deck.automaticallyWaitsToMinimizeStalling = false
                        deck.setRate(rate, time: CMTime(seconds: target, preferredTimescale: 1000),
                                     atHostTime: CMTime(seconds: CACurrentMediaTime() + wait, preferredTimescale: 1000))
                        deck.automaticallyWaitsToMinimizeStalling = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.swapCommit(deck, item, gen) }
                    }
                }
            }
        }
    }

    /// The copy is sounding in step with the old one: it becomes the current track, and the old one fades out under it.
    private func swapCommit(_ deck: AVQueuePlayer, _ item: AVPlayerItem, _ gen: Int) {
        guard gen == swapGen, swapItem === item else { return }
        swapItem = nil
        swapObservation = nil
        let out = player
        let oldItem = currentItem
        player = deck
        idle = out
        IosDsp.shared.reset()
        becomeCurrent(item)
        if let oldItem { dropItem(oldItem) }
        func retire() {
            out.pause()
            out.removeAllItems()
            out.volume = 1
            deck.volume = 1
        }
        if out.timeControlStatus == .paused { // paused (or interrupted) while the copy was lining up
            deck.pause()
            retire()
        } else {
            go() // a deck that stalled since its start sits at rate 0; this puts it back to playing (waiting for bytes if it must)
            let began = CACurrentMediaTime()
            let timer = Timer(timeInterval: 0.02, repeats: true) { t in
                let f = min(1, (CACurrentMediaTime() - began) / 0.16)
                deck.volume = Float(f)
                out.volume = Float(1 - f)
                if f >= 1 { t.invalidate(); retire() }
            }
            RunLoop.main.add(timer, forMode: .common)
        }
        materializeNextIfDue()
        reportProgress()
        updateNowPlayingTiming()
        print("AudioEngine: source swapped at \(player.currentTime().seconds)s")
    }

    private func cancelSwap(resume: Bool = true) {
        guard let item = swapItem else { return }
        swapGen += 1
        swapItem = nil
        swapObservation = nil
        idle.pause()
        idle.removeAllItems()
        idle.volume = 1
        dropItem(item)
        if resume { materializeNextIfDue() }
    }

    // MARK: - Crossfade

    private struct Transition { var startMs: Int64 = -1; var fadeMs: Int64 = 0; var cueMs: Int64 = 0; var rate: Float = 1; var outRate: Float = 1 }

    /// One fade in flight. `out` is the deck the track is leaving on, `inn` the deck it arrives on. Neither deck's volume
    /// is touched: each deck's tap (EqualizerTap -> IosDeck) renders its side of the blend (fader, filters, echo) from
    /// the shared TransitionRender, sample by sample, and this class only decides when it starts, hands off and ends.
    private final class Fade {
        let out: AVQueuePlayer, inn: AVQueuePlayer, item: AVPlayerItem, oldItem: AVPlayerItem?
        let start: Double, length: Double
        var swapped = false
        /// The incoming deck's speed now (the listener's, times the beatmatch stretch, times any phase-lock nudge).
        var inSpeed: Float
        init(out: AVQueuePlayer, inn: AVQueuePlayer, item: AVPlayerItem, oldItem: AVPlayerItem?, start: Double, length: Double, inSpeed: Float) {
            self.out = out; self.inn = inn; self.item = item; self.oldItem = oldItem; self.start = start; self.length = length; self.inSpeed = inSpeed
        }
    }

    private var transition = Transition()
    /// The queued next item waits paused on `idle`, cued, instead of behind the current one, so it can fade in.
    private var parked = false
    private var parkedCueMs: Int64 = -1
    /// This pair missed its window (a seek, a slow load); it joins gaplessly instead.
    private var fadeSkipped = false
    private var fade: Fade?
    private var fadeTimer: Timer?
    /// The playing track was sped up for a blend that has not (yet) come; the speed it has been brought to, if so.
    private var rampedSpeed: Float?
    /// A stretched track walking back to the listener's speed, a step per beat, after a blend that never came.
    private var easing = false
    private var easeItem: AVPlayerItem?

    private static func key(of item: AVPlayerItem) -> Int64 { Int64(Int(bitPattern: Unmanaged.passUnretained(item).toOpaque())) }

    func setTransition(startMs: Int64, fadeMs: Int64, cueMs: Int64, rate: Float, outRate: Float) {
        onMain {
            self.transition = Transition(startMs: startMs, fadeMs: max(0, fadeMs), cueMs: max(0, cueMs), rate: rate, outRate: outRate)
            self.materializeNextIfDue()
            // A blend renders through the taps, so every item needs one.
            let fades = fadeMs > 0
            if fades != EqualizerTuning.fades {
                EqualizerTuning.fades = fades
                if fades { [self.currentItem, self.nextItem].forEach { $0.map(EqualizerTap.attach) } }
            }
            self.placeNext()
        }
    }

    /// Puts [item], which sits on no deck, where the plan needs it: parked on the idle deck for a fade, behind the current item otherwise.
    private func place(_ item: AVPlayerItem) {
        if fade == nil && transition.fadeMs > 0 && !fadeSkipped {
            idle.removeAllItems()
            idle.insert(item, after: nil)
            idle.pause()
            parked = true
            parkedCueMs = -1
        } else {
            player.insert(item, after: currentItem)
            parked = false
        }
    }

    /// Moves the queued next item to wherever the plan now wants it. A removed item cannot join another deck at once
    /// (AVFoundation throws), so it goes as a fresh item over the same asset.
    private func placeNext() {
        guard fade == nil, let old = nextItem else { return updateFadeTimer() }
        if (transition.fadeMs > 0 && !fadeSkipped) != parked {
            if parked { idle.removeAllItems() } else { player.remove(old) }
            let item = AVPlayerItem(asset: old.asset)
            item.preferredForwardBufferDuration = old.preferredForwardBufferDuration
            if let loader = loaders.removeValue(forKey: ObjectIdentifier(old)) { adopt(loader, for: item) }
            prepare(item)
            nextItem = item
            place(item)
        }
        if parked && parkedCueMs != transition.cueMs {
            parkedCueMs = transition.cueMs
            idle.seek(to: CMTime(value: transition.cueMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] done in
                // Ready to sound the moment the blend asks: starting a cold deck costs a couple of hundred ms of phase.
                DispatchQueue.main.async {
                    // preroll throws (and the app aborts) unless the player is ready and stopped; by now the blend
                    // may have started, the decks swapped, or the item still be loading.
                    guard done, let self, self.parked, self.fade == nil else { return }
                    let deck = self.idle
                    guard deck.status == .readyToPlay, deck.currentItem?.status == .readyToPlay, deck.rate == 0 else { return }
                    deck.preroll(atRate: self.speed * self.transition.rate) { _ in }
                }
            }
        }
        updateFadeTimer()
    }

    /// A 50 Hz timer only while a fade is armed or running (or a tempo is easing back).
    private func updateFadeTimer() {
        let need = fade != nil || (parked && nextItem != nil) || easing
        if need && fadeTimer == nil {
            let timer = Timer(timeInterval: 0.02, repeats: true) { [weak self] _ in self?.fadeTick() }
            RunLoop.main.add(timer, forMode: .common)
            fadeTimer = timer
        } else if !need {
            fadeTimer?.invalidate()
            fadeTimer = nil
        }
    }

    private func fadeTick() {
        if let f = fade { return driveFade(f) }
        if easing { easeTick() }
        guard parked, !fadeSkipped, let item = nextItem, let current = currentItem,
              player.timeControlStatus == .playing else { return }
        let duration = streamDuration(of: current)
        guard duration.isFinite, duration > 0 else { return }
        // The playing track climbs to the incoming tempo over its last beats, a step per beat (Android's rampOutgoing).
        let position = player.currentTime().seconds
        let step = IosTransition.shared.rampRate(outPositionMs: Int64(position * 1000), speed: speed)
        if !step.isNaN {
            player.rate = step
            rampedSpeed = step
            print(String(format: "Transition: ramp -> %.4f at %.2fs", step, position))
        }
        // An Automix plan states its own window; a plain fade is kept to a third of the track and ends with it.
        let planned = transition.startMs >= 0
        let length = planned ? Double(transition.fadeMs) / 1000 : min(Double(transition.fadeMs) / 1000, duration / 3)
        let start = planned ? Double(transition.startMs) / 1000 : duration - length
        guard position >= start else { return }
        if position - start > 1 { // arrived late (a seek into the window): no fade for this pair
            fadeSkipped = true
            print("Crossfade: window missed (at \(position)s, due \(start)s); gapless")
            return abandonRamp()
        }
        guard item.status == .readyToPlay else { return } // still loading; the window allows a second of grace
        // The blend starts on the incoming tempo, however the ramp went.
        IosTransition.shared.finishRamp()
        let nowMs = Int64(CACurrentMediaTime() * 1000)
        guard IosTransition.shared.begin(outKey: Self.key(of: current), inKey: Self.key(of: item), startSec: start,
                                         fadeSec: length, outDurationSec: duration, nowMs: nowMs) else {
            fadeSkipped = true // a deck without its tap cannot be blended: join gaplessly
            return abandonRamp()
        }
        let deck = idle
        let inSpeed = speed * transition.rate
        let outSpeed = speed * transition.outRate
        // Start the incoming deck at a host time, at the point of its track that the outgoing one's position implies
        // (Android's cue is met as the fade starts; a deck started with a plain rate sounds ~200 ms late).
        let lead = 0.12
        let host = CACurrentMediaTime()
        let ratio = Double(transition.rate / transition.outRate)
        let at = Double(transition.cueMs) / 1000 + max(0, position + lead * Double(outSpeed) - start) * ratio
        // setRate(_:time:atHostTime:) throws unless the player is ready; a plain start is the safe fallback.
        if item.status == .readyToPlay, deck.status == .readyToPlay, at.isFinite {
            deck.automaticallyWaitsToMinimizeStalling = false
            deck.setRate(inSpeed, time: CMTime(seconds: at, preferredTimescale: 1000),
                         atHostTime: CMTime(seconds: host + lead, preferredTimescale: 1000))
            deck.automaticallyWaitsToMinimizeStalling = true
        } else {
            deck.rate = inSpeed
        }
        if transition.outRate != 1 { player.rate = outSpeed }
        rampedSpeed = nil
        fade = Fade(out: player, inn: deck, item: item, oldItem: current, start: position, length: length, inSpeed: inSpeed)
        print("Crossfade: begin out=\(position)s len=\(length)s cue=\(transition.cueMs)ms rate=\(transition.rate)/\(transition.outRate)")
    }

    private func driveFade(_ f: Fade) {
        let host = CACurrentMediaTime()
        let outEnded = f.out.currentItem == nil
        let outPos = f.out.currentTime().seconds
        let verdict = IosTransition.shared.drive(
            nowMs: Int64(host * 1000), hostSec: host,
            outPosSec: outEnded ? .infinity : outPos, inPosSec: f.inn.currentTime().seconds,
            outPlaying: f.out.timeControlStatus == .playing, inPlaying: f.inn.timeControlStatus == .playing, speed: speed)
        let nudge = IosTransition.shared.inSpeed
        if !nudge.isNaN {
            f.inSpeed = nudge
            f.inn.rate = nudge
        }
        // Verdict bits: 1 = the session moves to the incoming track (Android's HANDOFF_AT), 2 = the blend has run its course.
        if verdict & 1 != 0 && !f.swapped { swapDecks(f) }
        if verdict & 2 != 0 || outEnded { finishFade(f) }
    }

    /// Halfway, where the two are equally loud: the incoming track becomes the current one for everything else (Android's HANDOFF_AT).
    private func swapDecks(_ f: Fade) {
        f.swapped = true
        player = f.inn
        idle = f.out
        nextItem = nil
        parked = false
        fadeSkipped = false
        IosDsp.shared.reset()
        setSkipping(false)
        becomeCurrent(f.item)
        listener?.onAdvancedToNext()
    }

    private func finishFade(_ f: Fade) {
        if !f.swapped { swapDecks(f) }
        IosTransition.shared.end()
        f.out.pause()
        f.out.removeAllItems()
        if let old = f.oldItem { dropItem(old) }
        fade = nil
        // The blend's tempo match ends with it, as Android's finish() puts the incoming player back on the listener's speed.
        player.rate = skipping ? speed * 4 : speed
        placeNext()
        reportProgress()
        updateNowPlayingTiming()
        print("Crossfade: done")
    }

    /// Nothing faded: the parked track starts now, the old one having run out.
    private func handOverNow() {
        guard let item = nextItem else { return }
        let deck = idle
        deck.rate = speed
        let f = Fade(out: player, inn: deck, item: item, oldItem: nil, start: 0, length: 0, inSpeed: speed)
        fade = f
        finishFade(f)
    }

    /// A seek or a skip while a fade runs: before the swap the incoming track goes back to waiting; after, the fade is cut short.
    private func cancelFade() {
        guard let f = fade else { return abandonRamp() }
        if f.swapped { return finishFade(f) }
        IosTransition.shared.end()
        f.inn.pause()
        parkedCueMs = -1
        fadeSkipped = true
        fade = nil
        placeNext()
        abandonRamp()
    }

    /// The playing track was sped up for a blend that is not coming: walk it back a step per beat (Android's beginEase).
    private func abandonRamp() {
        guard let ramped = rampedSpeed else { return }
        rampedSpeed = nil
        let plan = IosTransition.shared
        let rate = Double(ramped / speed)
        if plan.beginEase(rate: rate, analysis: plan.outAnalysis, positionMs: Int64(player.currentTime().seconds * 1000)) {
            easing = true
            easeItem = player.currentItem
            updateFadeTimer()
        } else {
            applyRate()
        }
    }

    private func easeTick() {
        let pos = Int64(player.currentTime().seconds * 1000)
        if easeItem !== player.currentItem || IosTransition.shared.easeLost(positionMs: pos) { return endEase() }
        let next = IosTransition.shared.easeStep(positionMs: pos, speed: speed)
        if next.isNaN { return }
        if next < 0 { return endEase() } // arrived
        player.rate = next
    }

    private func endEase() {
        easing = false
        easeItem = nil
        applyRate()
        updateFadeTimer()
    }

    // MARK: - Now Playing timing

    /// The lock screen extrapolates the position from elapsed time + rate, so
    /// it only needs telling on play/pause/seek/track change, not every tick.
    private func updateNowPlayingTiming() {
        guard !nowPlaying.isEmpty else { return }
        let position = seekingToMs.map { Double($0) / 1000 } ?? player.currentTime().seconds
        if let item = player.currentItem, case let duration = streamDuration(of: item), duration.isFinite {
            nowPlaying[MPMediaItemPropertyPlaybackDuration] = duration
        }
        nowPlaying[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position.isFinite ? position : 0
        let playing = player.timeControlStatus != .paused
        nowPlaying[MPNowPlayingInfoPropertyPlaybackRate] = player.timeControlStatus == .playing ? Double(speed) : 0.0
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nowPlaying
        // The lock screen's button reads this, not the rate: a pause the app made itself (another device in a
        // Listen Together party) stopped the audio and left the button on Pause.
        center.playbackState = playing ? .playing : .paused
    }

    // MARK: - Audio session & system events

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
    }

    private func observeSystemEvents() {
        let center = NotificationCenter.default
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in self?.handleInterruption(note) })

        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in self?.handleRouteChange(note) })

        // Media services reset (rare, but kills every player): rebuild the session.
        notificationTokens.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.configureSession() })

        notificationTokens.append(center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main
        ) { [weak self] note in
            guard let self, let item = note.object as? AVPlayerItem else { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self.fail(item, message: error?.localizedDescription ?? "Playback stopped", httpStatus: 0)
        })
    }

    private func handleInterruption(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            // iOS has already paused the player; remember it wasn't the user.
            pausedByInterruption = wasPlaying
            reportProgress()
            if pausedByInterruption { listener?.onSystemPause() }
        case .ended:
            let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
            if pausedByInterruption && options.contains(.shouldResume) {
                requestPlaying(true)
            }
            pausedByInterruption = false
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw) else { return }
        // Headphones unplugged / Bluetooth disconnected: pause rather than blast the speaker.
        if reason == .oldDeviceUnavailable {
            pause()
            listener?.onSystemPause()
        }
    }

    // MARK: - Remote commands (lock screen, Control Center, headphones)

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        // Through Kotlin, like the app's own button: a Listen Together party has to hear these, or its next
        // reconcile puts the music straight back on.
        center.playCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            self.requestPlaying(true)
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.requestPlaying(false)
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            self.requestPlaying(self.player.timeControlStatus == .paused)
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.listener?.onRemoteNext()
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.listener?.onRemotePrevious()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(event.positionTime * 1000)
            if let listener = self.listener { listener.onRemoteSeek(positionMs: ms) } else { self.seekTo(positionMs: ms) }
            return .success
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    private func requestPlaying(_ play: Bool) {
        if let listener { listener.onRemotePlayPause(play: play) } else if play { resume() } else { pause() }
    }
}
