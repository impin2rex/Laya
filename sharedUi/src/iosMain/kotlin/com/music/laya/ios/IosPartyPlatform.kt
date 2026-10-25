package com.music.laya.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.laya.ui.screens.PartyPlatformHost
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.setValue
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.posix.memcpy

internal object IosPartyPlatform : PartyPlatformHost {
    @Composable
    override fun rememberShare(): (String) -> Unit = ::shareText

    override fun copyText(text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun formatTime(epochMs: Long): String =
        NSDateFormatter().apply { dateFormat = "HH:mm:ss" }
            .stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

    @Composable
    override fun QrCode(content: String, size: Dp) {
        val image by produceState<ImageBitmap?>(null, content) {
            value = withContext(Dispatchers.Default) { renderQr(content) }
        }
        Box(
            Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
        ) {
            image?.let { Image(bitmap = it, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
        }
    }
}

/** CoreImage's QR generator, scaled by whole steps so the modules stay sharp, as an Image Compose can draw. */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun renderQr(content: String): ImageBitmap? {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val message = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(message, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val scaled = filter.outputImage?.imageByApplyingTransform(CGAffineTransformMakeScale(10.0, 10.0)) ?: return null
    val cg = CIContext().createCGImage(scaled, fromRect = scaled.extent) ?: return null
    val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cg)) ?: return null
    val bytes = ByteArray(png.length.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
