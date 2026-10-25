import AVFoundation
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
        clearNext()
        guard currentItem != nil,
              let item = makeItem(url: url, headers: headers, mimeType: mimeType,
                                  contentLength: contentLength, chunkBytes: chunkBytes) else { return }
        nextItem = item
        fadeSkipped = false
        place(item)
        updateFadeTimer()
    }

    func clearNext() { onMain { self.clearNextOnMain() } }

    private func clearNextOnMain() {
        if let f = fade, !f.swapped { return }
        guard let item = nextItem else { return }
        nextItem = nil
        if parked { idle.removeAllItems(); parked = false } else { player.remove(item) }
        dropItem(item)
        updateFadeTimer()
    }

    private func dropItem(_ item: AVPlayerItem) {
        loaders.removeValue(forKey: ObjectIdentifier(item))?.cancelAll()
    }

    func pause() {
        pausedByInterruption = false
        player.pause()
        if fade != nil { idle.pause() }
        reportProgress()
    }

    func resume() {
        try? AVAudioSession.sharedInstance().setActive(true)
        go()
        reportProgress()
    }

    func seekTo(positionMs: Int64) { onMain { self.seekOnMain(positionMs: positionMs) } }

    private func seekOnMain(positionMs: Int64) {
        cancelFade()
        guard let item = player.currentItem, item.status == .readyToPlay else {
            pendingSeekMs = positionMs
            return
        }
        let time = CMTime(value: positionMs, timescale: 1000)
        // A little tolerance lets AVPlayer land on the nearest audio packet
        // instead of decoding up to an exact sample — much faster over the network.
        let tolerance = CMTime(value: 250, timescale: 1000)
        seekingToMs = positionMs
        IosDsp.shared.reset()
        reportProgress()
        player.seek(to: time, toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] _ in
            guard let self else { return }
            if self.seekingToMs == positionMs { self.seekingToMs = nil }
            self.reportProgress()
            self.updateNowPlayingTiming()
        }
    }

    func stop() {
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

    private func makeItem(url: String, headers: [String: String], mimeType: String,
                          contentLength: Int64, chunkBytes: Int64) -> AVPlayerItem? {
        guard let source = URL(string: url) else { return nil }
        // A downloaded or imported file: AVFoundation reads it directly.
        if source.isFileURL {
            let item = AVPlayerItem(asset: AVURLAsset(url: source))
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
        let asset = AVURLAsset(url: loader.proxyURL)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        prepare(item)
        adopt(loader, for: item)
        return item
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
        guard item === currentItem, failedItems.insert(ObjectIdentifier(item)).inserted else { return }
        cancelFade()
        clearNextOnMain()
        player.pause()
        listener?.onError(message: message, httpStatus: httpStatus)
    }

    private func hasFailed(_ item: AVPlayerItem) -> Bool {
        item.status == .failed || item.error != nil || failedItems.contains(ObjectIdentifier(item))
    }

    private func prepare(_ item: AVPlayerItem) {
        item.audioTimePitchAlgorithm = .spectral
        if EqualizerTuning.wanted { EqualizerTap.attach(to: item) }
    }

    /// Point the status observation and the end/fail notifications at [item].
    private func becomeCurrent(_ item: AVPlayerItem) {
        currentItem = item
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

    /// The stream's stated length when it has one (see ChunkedResourceLoader.durationHint), else AVFoundation's.
    private func streamDuration(of item: AVPlayerItem) -> Double {
        loaders[ObjectIdentifier(item)]?.durationHint ?? item.duration.seconds
    }

    private func reportProgress() {
        guard let item = player.currentItem else { return }
        let position = seekingToMs.map { Double($0) / 1000 } ?? player.currentTime().seconds
        let duration = streamDuration(of: item)
        let isPlaying = player.timeControlStatus == .playing
        let isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        wasPlaying = isPlaying || isBuffering
        listener?.onProgress(
            positionMs: position.isFinite ? Int64(position * 1000) : 0,
            durationMs: duration.isFinite ? Int64(duration * 1000) : 0,
            isPlaying: isPlaying,
            isBuffering: isBuffering
        )
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
            idle.seek(to: CMTime(value: transition.cueMs, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
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
        deck.rate = inSpeed
        if transition.outRate != 1 { player.rate = speed * transition.outRate }
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
        nowPlaying[MPNowPlayingInfoPropertyPlaybackRate] = player.timeControlStatus == .playing ? Double(speed) : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlaying
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
        case .ended:
            let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsRaw)
            if pausedByInterruption && options.contains(.shouldResume) {
                resume()
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
        }
    }

    // MARK: - Remote commands (lock screen, Control Center, headphones)

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            self.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.player.currentItem != nil else { return .noActionableNowPlayingItem }
            if self.player.timeControlStatus == .paused { self.resume() } else { self.pause() }
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
            self.seekTo(positionMs: Int64(event.positionTime * 1000))
            return .success
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }
}
