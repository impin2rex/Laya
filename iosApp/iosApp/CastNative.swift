import Foundation
import GoogleCast
import LayaKit

/// Google Cast for the Kotlin side (IosCast): discovery, sessions, and the receiver's queue and clock.
/// Android's CastOptionsProvider, ported: the stock media receiver, and the receiver is stopped when the session ends.
/// Everything runs on the main thread, like the SDK.
final class CastNativeImpl: NSObject, CastNative, GCKSessionManagerListener, GCKRemoteMediaClientListener, GCKDiscoveryManagerListener {
    private static let mediaIdKey = "mediaId"

    private weak var listener: CastNativeListener?
    private var client: GCKRemoteMediaClient?
    private var ticker: Timer?
    private var resumed = false

    override init() {
        super.init()
        let options = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID))
        // Leaving the receiver running after the phone lets go keeps a TV on a "casting" screen with nothing driving it.
        options.stopReceiverApplicationWhenEndingSession = true
        GCKCastContext.setSharedInstanceWith(options)
        let context = GCKCastContext.sharedInstance()
        context.sessionManager.add(self)
        context.discoveryManager.add(self)
        if let session = context.sessionManager.currentCastSession { began(session, resumed: true) }
    }

    func setListener(listener_ listener: CastNativeListener?) {
        self.listener = listener
        guard let listener else { return }
        listener.onReady()
        publishDevices()
        if let session = GCKCastContext.sharedInstance().sessionManager.currentCastSession, client != nil {
            listener.onSession(state: 2, name: session.device.friendlyName ?? "Cast", resumed: resumed)
            push()
        }
    }

    func discover(active: Bool) {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        manager.passiveScan = !active
        if active { manager.startDiscovery() }
    }

    func connect(id: String) {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        if let device = manager.device(withUniqueID: id) {
            listener?.onSession(state: 1, name: nil, resumed: false)
            GCKCastContext.sharedInstance().sessionManager.startSession(with: device)
        }
    }

    func disconnect() { GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true) }

    // MARK: Devices

    private func publishDevices() {
        let manager = GCKCastContext.sharedInstance().discoveryManager
        let devices = (0..<manager.deviceCount).map { manager.device(at: $0) }
        listener?.onDevices(ids: devices.map { $0.uniqueID }, names: devices.map { $0.friendlyName ?? "Cast" })
    }

    func didUpdateDeviceList() { publishDevices() }

    // MARK: Sessions

    func sessionManager(_ sessionManager: GCKSessionManager, willStart session: GCKCastSession) {
        listener?.onSession(state: 1, name: nil, resumed: false)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, willResumeCastSession session: GCKCastSession) {
        listener?.onSession(state: 1, name: nil, resumed: true)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) { began(session, resumed: false) }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) { began(session, resumed: true) }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        print("Cast: session failed to start: \(error)")
        listener?.onSession(state: 0, name: nil, resumed: false)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        ticker?.invalidate(); ticker = nil
        client?.remove(self); client = nil
        listener?.onSession(state: 0, name: nil, resumed: false)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, castSession session: GCKCastSession, didReceiveDeviceVolume volume: Float, muted: Bool) {
        listener?.onVolume(level: volume)
    }

    private func began(_ session: GCKCastSession, resumed: Bool) {
        self.resumed = resumed
        client?.remove(self)
        client = session.remoteMediaClient
        client?.add(self)
        // The SDK only calls back on a status change; the position the bar shows needs a clock.
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.push() }
        listener?.onSession(state: 2, name: session.device.friendlyName ?? "Cast", resumed: resumed)
        listener?.onVolume(level: session.currentDeviceVolume)
        push()
    }

    // MARK: The receiver's clock

    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) { push() }

    private func push() {
        guard let client else { return }
        guard let status = client.mediaStatus else {
            listener?.onStatus(mediaId: nil, positionMs: 0, durationMs: 0, state: 0)
            return
        }
        let state: Int32
        switch status.playerState {
        case .playing: state = 1
        case .paused: state = 2
        case .buffering, .loading: state = 3
        case .idle: state = status.idleReason == .finished ? 4 : status.idleReason == .error ? 5 : 0
        default: state = 0
        }
        let duration = status.mediaInformation?.streamDuration ?? 0
        listener?.onStatus(
            mediaId: mediaId(of: status.currentQueueItem),
            positionMs: Int64(max(0, client.approximateStreamPosition()) * 1000),
            durationMs: duration.isFinite ? Int64(duration * 1000) : 0,
            state: state
        )
    }

    private func mediaId(of item: GCKMediaQueueItem?) -> String? {
        (item?.mediaInformation.customData as? [String: Any])?[Self.mediaIdKey] as? String
    }

    private var queueItems: [GCKMediaQueueItem] {
        guard let status = client?.mediaStatus else { return [] }
        return (0..<status.queueItemCount).compactMap { status.queueItem(at: $0) }
    }

    private var ahead: [GCKMediaQueueItem] {
        guard let current = client?.mediaStatus?.currentItemID else { return [] }
        let items = queueItems
        guard let here = items.firstIndex(where: { $0.itemID == current }) else { return [] }
        return Array(items.dropFirst(here + 1))
    }

    // MARK: The receiver's queue

    private func queueItem(_ item: CastItem, autoplay: Bool) -> GCKMediaQueueItem? {
        guard let url = URL(string: item.url) else { return nil }
        let metadata = GCKMediaMetadata(metadataType: .musicTrack)
        metadata.setString(item.title, forKey: kGCKMetadataKeyTitle)
        if let artist = item.artist { metadata.setString(artist, forKey: kGCKMetadataKeyArtist) }
        if let album = item.album { metadata.setString(album, forKey: kGCKMetadataKeyAlbumTitle) }
        if let art = item.artworkUrl.flatMap(URL.init(string:)) { metadata.addImage(GCKImage(url: art, width: 0, height: 0)) }
        let info = GCKMediaInformationBuilder(contentURL: url)
        info.streamType = .buffered
        info.contentType = item.mimeType
        info.metadata = metadata
        info.customData = [Self.mediaIdKey: item.mediaId]
        let builder = GCKMediaQueueItemBuilder()
        builder.mediaInformation = info.build()
        builder.autoplay = autoplay
        return builder.build()
    }

    func load(item: CastItem, positionMs: Int64, autoplay: Bool) {
        guard let client, let queued = queueItem(item, autoplay: true) else { return }
        let queue = GCKMediaQueueDataBuilder(queueType: .generic)
        queue.items = [queued]
        queue.startIndex = 0
        queue.startTime = TimeInterval(positionMs) / 1000
        let request = GCKMediaLoadRequestDataBuilder()
        request.queueData = queue.build()
        request.autoplay = NSNumber(value: autoplay)
        request.startTime = TimeInterval(positionMs) / 1000
        client.loadMedia(with: request.build())
    }

    func aheadIds() -> [String] { ahead.compactMap { mediaId(of: $0) } }

    func trimAhead(keep: Int32) {
        let ids = ahead.dropFirst(Int(keep)).map { NSNumber(value: $0.itemID) }
        if !ids.isEmpty { client?.queueRemoveItems(withIDs: Array(ids)) }
    }

    func append(item: CastItem) {
        if let queued = queueItem(item, autoplay: true) { client?.queueInsert(queued, beforeItemWithID: kGCKMediaQueueInvalidItemID) }
    }

    func jumpTo(mediaId: String) -> Bool {
        guard let target = queueItems.first(where: { self.mediaId(of: $0) == mediaId }) else { return false }
        client?.queueJumpToItem(withID: target.itemID)
        return true
    }

    func currentMediaId() -> String? { mediaId(of: client?.mediaStatus?.currentQueueItem) }
    func play() { client?.play() }
    func pause() { client?.pause() }
    func seekTo(positionMs: Int64) {
        let options = GCKMediaSeekOptions()
        options.interval = TimeInterval(positionMs) / 1000
        client?.seek(with: options)
    }
    func setVolume(level: Float) { GCKCastContext.sharedInstance().sessionManager.currentCastSession?.setDeviceVolume(level) }
}
