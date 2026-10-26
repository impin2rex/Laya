import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// Android's MediaWidgetArt on Core Image: the same pictures from the same numbers, so the widgets look alike.
///
/// - `media`: the cover filling the widget, its bottom dissolving into a blur for the transport, under a scrim.
///   Four progressively blurrier copies drawn back over it, each masked by a gradient starting lower than the last,
///   so the blur accelerates downwards and its start can't be seen.
/// - `body`: the cover blurred until only its colours are left, darkened for white text (the 4×1 and library widgets).
enum WidgetArt {
    // MediaWidgetArt's tuning, unchanged.
    static let blurRegionScale: CGFloat = 2
    static let blurSigmas: [CGFloat] = [0.035, 0.070, 0.110, 0.155]
    static let stops: [CGFloat] = [0.28, 0.50, 0.70, 0.86]
    static let stopFeather: CGFloat = 0.26
    static let scrimScale: CGFloat = 1.2
    /// pill(): three passes of a box radius a tenth of a 64 px working image, as a Gaussian sigma of its width.
    static let bodySigmaOfWidth: CGFloat = sqrt(3 * (6 * 6 + 6) / 3) / 64
    static let bodyAlpha: CGFloat = 210 / 255

    /// Android's placeholder: what stands in for a cover there isn't one of.
    static let placeholder: [(UIColor, CGFloat)] = [
        (UIColor(rgb: 0x2E3446), 0), (UIColor(rgb: 0x1B2130), 0.45), (UIColor(rgb: 0x07090E), 1),
    ]

    private static let context = CIContext(options: [.cacheIntermediates: false])

    /// The cover at [size] points with the transport band's blur and scrim, [band] points tall.
    static func media(cover: UIImage?, size: CGSize, band: CGFloat, scale: CGFloat) -> UIImage? {
        let w = size.width * scale, h = size.height * scale, bandPx = band * scale
        let rect = CGRect(x: 0, y: 0, width: w, height: h)
        var image = cover.flatMap { filled($0, rect) } ?? gradient(rect, placeholder)
        let region = min(bandPx * blurRegionScale, h)
        let top = h - region // measured from the top, as Android draws
        let base = image.clampedToExtent()
        for (i, sigma) in blurSigmas.enumerated() {
            let blurred = base.applyingGaussianBlur(sigma: Double(sigma * bandPx)).cropped(to: rect)
            let start = top + stops[i] * region
            let end = max(top + min(stops[i] + stopFeather, 1) * region, start + 1)
            image = blurred.applyingFilter("CIBlendWithMask", parameters: [
                kCIInputBackgroundImageKey: image,
                kCIInputMaskImageKey: verticalAlpha(rect, from: start, to: end),
            ])
        }
        let scrimTop = max(h - bandPx * scrimScale, 0)
        image = gradient(CGRect(x: 0, y: 0, width: w, height: h - scrimTop), [
            (.clear, 0), (UIColor(white: 0, alpha: 0x40 / 255), 0.45), (UIColor(white: 0, alpha: 0xB8 / 255), 1),
        ]).composited(over: image)
        return render(image, rect, scale)
    }

    /// The cover blurred to colour at [size], darkened top to bottom, over the stand-in dark the Android widget's
    /// translucent body shows when the wallpaper behind it is black.
    static func body(cover: UIImage?, size: CGSize, scale: CGFloat) -> UIImage? {
        let w = size.width * scale, h = size.height * scale
        let rect = CGRect(x: 0, y: 0, width: w, height: h)
        var image: CIImage
        if let cover, let fill = filled(cover, rect) {
            image = fill.clampedToExtent().applyingGaussianBlur(sigma: Double(bodySigmaOfWidth * w)).cropped(to: rect)
        } else {
            image = gradient(rect, placeholder)
        }
        image = gradient(rect, [(UIColor(white: 0, alpha: 0x40 / 255), 0), (UIColor(white: 0, alpha: 0x70 / 255), 1)])
            .composited(over: image)
        image = image.applyingFilter("CIColorMatrix", parameters: ["inputAVector": CIVector(x: 0, y: 0, z: 0, w: bodyAlpha)])
            .composited(over: CIImage(color: CIColor(color: UIColor(rgb: 0x141619))).cropped(to: rect))
        return render(image, rect, scale)
    }

    /// [src] centre-cropped to fill [rect] (fillCentreCropped).
    private static func filled(_ src: UIImage, _ rect: CGRect) -> CIImage? {
        guard let cg = src.cgImage else { return nil }
        let img = CIImage(cgImage: cg)
        let s = max(rect.width / img.extent.width, rect.height / img.extent.height)
        let scaled = img.transformed(by: CGAffineTransform(scaleX: s, y: s))
        let dx = (scaled.extent.width - rect.width) / 2, dy = (scaled.extent.height - rect.height) / 2
        return scaled.transformed(by: CGAffineTransform(translationX: -dx - scaled.extent.minX, y: -dy - scaled.extent.minY)).cropped(to: rect)
    }

    /// A vertical gradient filling [rect] from its top, with stops as fractions of its height: one gradient with all
    /// its stops, as Android's LinearGradient. Its y runs down here.
    private static func gradient(_ rect: CGRect, _ stops: [(UIColor, CGFloat)]) -> CIImage {
        let w = max(Int(rect.width.rounded()), 1), h = max(Int(rect.height.rounded()), 1)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(data: nil, width: 1, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
              let g = CGGradient(colorsSpace: space, colors: stops.map { $0.0.cgColor } as CFArray, locations: stops.map { $0.1 }) else {
            return CIImage.empty()
        }
        // Bitmap contexts' y runs up: from its top (y = h) to its bottom.
        ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: h), end: .zero, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        guard let column = ctx.makeImage() else { return CIImage.empty() }
        // Clamped first: stretched alone, the column's edge pixels blend with the nothing beside them.
        return CIImage(cgImage: column).clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: CGFloat(w), y: 1).translatedBy(x: rect.minX / CGFloat(w), y: rect.minY))
            .cropped(to: rect)
    }

    /// Alpha 0 above [from] and 1 below [to], both measured from the top of [rect]: a level's mask.
    private static func verticalAlpha(_ rect: CGRect, from: CGFloat, to: CGFloat) -> CIImage {
        let g = CIFilter.linearGradient()
        g.point0 = CGPoint(x: 0, y: rect.height - from); g.point1 = CGPoint(x: 0, y: rect.height - to)
        g.color0 = CIColor(red: 0, green: 0, blue: 0, alpha: 0); g.color1 = CIColor(red: 1, green: 1, blue: 1, alpha: 1)
        return g.outputImage!.cropped(to: rect)
    }

    private static func render(_ image: CIImage, _ rect: CGRect, _ scale: CGFloat) -> UIImage? {
        guard let cg = context.createCGImage(image, from: rect) else { return nil }
        return UIImage(cgImage: cg, scale: scale, orientation: .up)
    }
}

extension UIColor {
    convenience init(rgb: Int, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: alpha)
    }
}
