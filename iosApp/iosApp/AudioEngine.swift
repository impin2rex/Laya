import AVFoundation
import Foundation
import MediaPlayer
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
    private var player = AVQueuePlayer()
    private weak var listener: AudioEngineListener?

    private var timeObservers: [(AVQueuePlayer, Any)] = []
    private var deckObservations: [NSKeyValueObservation] = []

    /// Loader per queued item; an item's loader must live as long as the item.
    private var loaders: [ObjectIdentifier: ChunkedResourceLoader] = [:]
    private var speed: Float = 1
    private var currentItem: AVPlayerItem?
    private var nextItem: AVPlayerItem?

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

    func setListener(listener: AudioEngineListener?) {
        self.listener = listener
    }

    func play(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        stop()
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
        clearNext()
        guard currentItem != nil,
              let item = makeItem(url: url, headers: headers, mimeType: mimeType,
                                  contentLength: contentLength, chunkBytes: chunkBytes) else { return }
        nextItem = item
        player.insert(item, after: currentItem)
    }

    func clearNext() {
        guard let item = nextItem else { return }
        nextItem = nil
        player.remove(item)
        dropItem(item)
    }

    private func dropItem(_ item: AVPlayerItem) {
        loaders.removeValue(forKey: ObjectIdentifier(item))?.cancelAll()
    }

    func pause() {
        pausedByInterruption = false
        player.pause()
        reportProgress()
    }

    func resume() {
        try? AVAudioSession.sharedInstance().setActive(true)
        go()
        reportProgress()
    }

    func seekTo(positionMs: Int64) {
        guard let item = player.currentItem, item.status == .readyToPlay else {
            pendingSeekMs = positionMs
            return
        }
        let time = CMTime(value: positionMs, timescale: 1000)
        // A little tolerance lets AVPlayer land on the nearest audio packet
        // instead of decoding up to an exact sample — much faster over the network.
        let tolerance = CMTime(value: 250, timescale: 1000)
        seekingToMs = positionMs
        reportProgress()
        player.seek(to: time, toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] _ in
            guard let self else { return }
            if self.seekingToMs == positionMs { self.seekingToMs = nil }
            self.reportProgress()
            self.updateNowPlayingTiming()
        }
    }

    func stop() {
        player.pause()
        player.removeAllItems()
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
        player.rate = speed
    }

    func setSpeed(speed: Float) {
        self.speed = speed
        if player.timeControlStatus != .paused { player.rate = speed }
        updateNowPlayingTiming()
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
        loader.onHTTPError = { [weak self, weak item] status in
            DispatchQueue.main.async {
                // Only the playing item's refusal is actionable; a queued one is
                // re-resolved by Kotlin when it becomes current and fails.
                guard let self, let item, item === self.currentItem else { return }
                self.listener?.onError(message: "Stream refused (HTTP \(status))", httpStatus: Int32(status))
            }
        }
        loaders[ObjectIdentifier(item)] = loader
        return item
    }

    private func prepare(_ item: AVPlayerItem) {
        item.audioTimePitchAlgorithm = .spectral
        if EqualizerTuning.wanted { EqualizerTap.attach(to: item) }
    }

    /// Point the status observation and the end/fail notifications at [item].
    private func becomeCurrent(_ item: AVPlayerItem) {
        currentItem = item
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
                case .failed:
                    let refused = self.loaders[ObjectIdentifier(item)]?.reportedHTTPError ?? false
                    if !refused {
                        self.listener?.onError(
                            message: item.error?.localizedDescription ?? "Playback failed",
                            httpStatus: 0
                        )
                    }
                default:
                    break
                }
            }
        }
    }

    private func currentItemChanged(from old: AVPlayerItem?) {
        let now = player.currentItem
        if let old, old !== now {
            dropItem(old)
        }
        guard let now else {
            // Queue ran dry: the last item ended (or everything was removed by stop()).
            if old != nil && currentItem != nil {
                currentItem = nil
                listener?.onEnded()
            }
            return
        }
        if now === nextItem {
            nextItem = nil
            becomeCurrent(now)
            listener?.onAdvancedToNext()
        }
    }

    private func reportProgress() {
        guard let item = player.currentItem else { return }
        let position = seekingToMs.map { Double($0) / 1000 } ?? player.currentTime().seconds
        let duration = item.duration.seconds
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

    // MARK: - Now Playing timing

    /// The lock screen extrapolates the position from elapsed time + rate, so
    /// it only needs telling on play/pause/seek/track change, not every tick.
    private func updateNowPlayingTiming() {
        guard !nowPlaying.isEmpty else { return }
        let position = seekingToMs.map { Double($0) / 1000 } ?? player.currentTime().seconds
        if let duration = player.currentItem?.duration.seconds, duration.isFinite {
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
            guard let self, let item = note.object as? AVPlayerItem, item === self.currentItem else { return }
            if self.loaders[ObjectIdentifier(item)]?.reportedHTTPError ?? false { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self.listener?.onError(message: error?.localizedDescription ?? "Playback stopped", httpStatus: 0)
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
