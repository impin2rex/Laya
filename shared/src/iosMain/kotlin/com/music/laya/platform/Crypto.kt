@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.laya.platform

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256

// Pad so addressOf(0) is legal on empty input; the length passed is still the real one.
private fun ByteArray.safe() = if (isEmpty()) ByteArray(1) else this

actual fun sha1(data: ByteArray): ByteArray = ByteArray(CC_SHA1_DIGEST_LENGTH).also { out ->
    out.usePinned { o -> data.safe().usePinned { CC_SHA1(it.addressOf(0), data.size.convert(), o.addressOf(0).reinterpret<UByteVar>()) } }
}

actual fun sha256(data: ByteArray): ByteArray = ByteArray(CC_SHA256_DIGEST_LENGTH).also { out ->
    out.usePinned { o -> data.safe().usePinned { CC_SHA256(it.addressOf(0), data.size.convert(), o.addressOf(0).reinterpret<UByteVar>()) } }
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(CC_SHA256_DIGEST_LENGTH).also { out ->
    out.usePinned { o -> key.safe().usePinned { k -> data.safe().usePinned { d ->
        CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), o.addressOf(0))
    } } }
}
