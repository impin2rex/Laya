import SwiftUI
import WidgetKit

// The Android layouts (res/layout/widget_media_*.xml) in SwiftUI, at the same sizes in points as they are in dp.

/// What a tap anywhere but a button does: open the app on the full player (Android's openPlayer).
let openPlayerURL = URL(string: "laya://widget/open")!

/// ALPHA_DISABLED / ALPHA_ENABLED.
private let disabledAlpha = 90.0 / 255

private let white70 = Color.white.opacity(0xB3 / 255.0)

/// One transport button: [glyph] in a [size] circle, [inset] in from its edge, as the layouts' padding does.
/// Runs [command] in the app (LayaWidgetIntent); a disabled one is only drawn, so a tap opens the app instead.
struct WidgetButton: View {
    let glyph: String
    let command: String
    let size: CGFloat
    let inset: CGFloat
    var enabled = true
    var on = false // the Shuffle toggle's lit background (widget_toggle_on)
    var width: CGFloat? = nil

    var body: some View {
        let icon = Image(glyph).renderingMode(.template).resizable().scaledToFit()
            .foregroundStyle(.white)
            .padding(inset)
            .frame(width: width ?? size, height: size)
            .background(Circle().fill(Color.white.opacity(on ? 0x33 / 255.0 : 0)).frame(width: size, height: size))
            .opacity(enabled ? 1 : disabledAlpha)
            .contentShape(Rectangle())
        if enabled {
            Button(intent: LayaWidgetIntent(command)) { icon }.buttonStyle(.plain)
        } else {
            icon
        }
    }
}

/// Previous / play-pause / next for [s], dimmed at the ends of the queue like the media notification.
private func transport(_ s: LayaWidgetSnapshot, size: CGFloat, insets: (CGFloat, CGFloat, CGFloat), spacing: CGFloat = 0, flexible: Bool = false) -> some View {
    HStack(spacing: spacing) {
        WidgetButton(glyph: "ic_widget_previous", command: "previous", size: size, inset: insets.0, enabled: s.hasTrack && s.hasPrevious)
            .frame(maxWidth: flexible ? .infinity : nil)
        WidgetButton(glyph: s.isPlaying ? "ic_widget_pause" : "ic_widget_play", command: "toggle", size: size, inset: insets.1, enabled: s.hasTrack)
            .frame(maxWidth: flexible ? .infinity : nil)
        WidgetButton(glyph: "ic_widget_next", command: "next", size: size, inset: insets.2, enabled: s.hasTrack && s.hasNext)
            .frame(maxWidth: flexible ? .infinity : nil)
    }
}

private func titleText(_ s: LayaWidgetSnapshot) -> String { s.hasTrack ? s.title : "Nothing played yet" }

// MARK: - Now playing (2×2 and 4×2)

/// widget_media_compact: the 2×2 one. Title over three centred buttons in a 66pt band at the foot of the cover.
struct CompactMediaView: View {
    let s: LayaWidgetSnapshot
    let art: UIImage?

    var body: some View {
        ZStack(alignment: .bottom) {
            artwork(art)
            VStack(spacing: 0) {
                Text(titleText(s))
                    .font(.system(size: 13, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                    .frame(maxWidth: .infinity).padding(.horizontal, 4).padding(.bottom, 3)
                transport(s, size: 40, insets: (9, 9, 9), flexible: true)
            }
            .padding(.horizontal, 8).padding(.bottom, 4)
            .frame(height: 66, alignment: .bottom)
        }
    }
}

/// widget_media_wide: the 4×2 one. Title and artist beside the buttons in a 60pt band.
struct WideMediaView: View {
    let s: LayaWidgetSnapshot
    let art: UIImage?

    var body: some View {
        ZStack(alignment: .bottom) {
            artwork(art)
            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(titleText(s)).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                    if !s.artist.isEmpty {
                        Text(s.artist).font(.system(size: 12)).foregroundStyle(white70).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.trailing, 8)
                transport(s, size: 44, insets: (10, 10, 10))
            }
            .padding(.leading, 14).padding(.trailing, 4)
            .frame(height: 60)
        }
    }
}

private func artwork(_ art: UIImage?) -> some View {
    Group {
        if let art { Image(uiImage: art).resizable() } else { placeholderGradient }
    }
}

let placeholderGradient = LinearGradient(
    stops: WidgetArt.placeholder.map { Gradient.Stop(color: Color($0.0), location: $0.1) },
    startPoint: .top, endPoint: .bottom)

// MARK: - 4×1

/// widget_media_pill: the round cover, "title - artist", and Like / previous / play / next / Shuffle. iOS has no
/// one-row widget, so the capsule's contents are drawn at their Android proportions, [scale]d to fill a medium tile
/// whose whole face is the body.
struct PillView: View {
    let s: LayaWidgetSnapshot
    let disc: UIImage?
    let scale: CGFloat

    /// The capsule at its Android proportions (78dp tall; 7 + 64 + 6 + five 38dp buttons + 12 ≈ 279dp across at the
    /// least), as large as [size] allows with a little room around it.
    static func scale(for size: CGSize) -> CGFloat { min(size.height / 78, size.width / 300) }

    var body: some View {
        let k = scale
        HStack(spacing: 0) {
            Group {
                if let disc { Image(uiImage: disc).resizable().scaledToFill() } else { placeholderGradient }
            }
            .frame(width: 64 * k, height: 64 * k).clipShape(Circle())
            VStack(alignment: .leading, spacing: 0) {
                Text(!s.hasTrack ? "Nothing played yet" : s.artist.isEmpty ? s.title : "\(s.title) - \(s.artist)")
                    .font(.system(size: 16 * k, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                    .padding(.horizontal, 10 * k)
                HStack(spacing: 0) {
                    let b = 38 * k
                    if s.canLike {
                        WidgetButton(glyph: s.isLiked ? "ic_widget_heart_filled" : "ic_widget_heart", command: "like",
                                     size: b, inset: 9 * k, enabled: s.hasTrack)
                        Spacer(minLength: 0)
                    }
                    WidgetButton(glyph: "ic_widget_previous", command: "previous", size: b, inset: 6 * k, enabled: s.hasTrack && s.hasPrevious)
                    Spacer(minLength: 0)
                    WidgetButton(glyph: s.isPlaying ? "ic_widget_pause" : "ic_widget_play", command: "toggle", size: b, inset: 4 * k, enabled: s.hasTrack)
                    Spacer(minLength: 0)
                    WidgetButton(glyph: "ic_widget_next", command: "next", size: b, inset: 6 * k, enabled: s.hasTrack && s.hasNext)
                    Spacer(minLength: 0)
                    WidgetButton(glyph: "ic_widget_shuffle", command: "shuffle", size: b, inset: 8 * k, enabled: s.hasTrack, on: s.shuffleEnabled)
                }
                .frame(height: 38 * k)
            }
            .padding(.leading, 6 * k).padding(.top, 4 * k)
        }
        .padding(.leading, 7 * k).padding(.trailing, 12 * k)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Now playing + Liked

/// widget_media_library(_tall): the track and transport over the first Liked Music covers. [tall] from 150pt, as on
/// Android (TALL_MIN_DP); a medium widget is that tall on every iPhone but the smallest.
struct LibraryView: View {
    let s: LayaWidgetSnapshot
    let art: UIImage?
    let tiles: [UIImage?]
    let size: CGSize

    private var tall: Bool { size.height >= 150 }

    var body: some View {
        let pad: CGFloat = tall ? 14 : 10
        let content = size.height - 2 * pad
        let rows = content - 12
        let liked = !s.liked.isEmpty
        // The same arithmetic MediaWidgetLibrary.geometry does for its bitmaps.
        let artSide = tall ? (liked ? rows * 3 / 5 : content) : (liked ? content - 6 - 40 : content)
        let tileSide = tall ? min((size.width - 28) / 5, rows * 2 / 5) : 40
        ZStack(alignment: .topTrailing) {
            VStack(spacing: 0) {
                if tall { tallTop(artSide) } else { shortTop(artSide) }
                if liked {
                    HStack(spacing: 0) {
                        ForEach(0..<5, id: \.self) { i in
                            tile(i, side: tileSide).frame(maxWidth: .infinity)
                        }
                    }
                    .frame(height: tall ? rows * 2 / 5 : 40)
                    .padding(.top, tall ? 12 : 6)
                }
            }
            .padding(pad)
            Image("ic_notification_logo").renderingMode(.template).resizable()
                .foregroundStyle(.white).opacity(0.9)
                .frame(width: tall ? 20 : 14, height: tall ? 20 : 14)
                .padding(.top, tall ? 14 : 6).padding(.trailing, tall ? 14 : 6)
        }
        .frame(width: size.width, height: size.height)
    }

    private func cover(_ side: CGFloat) -> some View {
        Group {
            if let art { Image(uiImage: art).resizable().scaledToFill() } else { Color.white.opacity(0x33 / 255.0) }
        }
        .frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: side * 0.08))
    }

    private func tallTop(_ side: CGFloat) -> some View {
        HStack(alignment: .top, spacing: 14) {
            cover(side)
            VStack(alignment: .leading, spacing: 0) {
                Text(titleText(s)).font(.system(size: 16, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                    .padding(.top, 2).padding(.trailing, 26)
                if !s.artist.isEmpty {
                    Text(s.artist).font(.system(size: 13)).foregroundStyle(white70).lineLimit(1).padding(.top, 3)
                }
                Spacer(minLength: 0)
                transport(s, size: 44, insets: (9, 6, 9), spacing: 6).padding(.leading, -8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(height: side)
    }

    private func shortTop(_ side: CGFloat) -> some View {
        HStack(spacing: 0) {
            cover(side)
            VStack(alignment: .leading, spacing: 2) {
                Text(titleText(s)).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                if !s.artist.isEmpty { Text(s.artist).font(.system(size: 12)).foregroundStyle(white70).lineLimit(1) }
            }
            .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 10).padding(.trailing, 4)
            transport(s, size: 36, insets: (6, 4, 6))
        }
        .frame(maxHeight: .infinity)
    }

    /// A liked song's cover; a tap plays it (Android: a watch?v= link handed to the app).
    @ViewBuilder private func tile(_ i: Int, side: CGFloat) -> some View {
        if i < s.liked.count {
            let picture = Group {
                if let img = tiles[safe: i] ?? nil { Image(uiImage: img).resizable().scaledToFill() } else { Color.white.opacity(0x33 / 255.0) }
            }
            .frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: max(side * 0.08, 4)))
            Link(destination: URL(string: "laya://widget/play?v=\(s.liked[i].id.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")")!) { picture }
        } else {
            Color.clear.frame(width: side, height: side)
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
