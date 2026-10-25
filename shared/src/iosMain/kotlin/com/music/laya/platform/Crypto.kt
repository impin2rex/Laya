@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.laya.platform

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmDES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar
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

actual fun desEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size + 8) // one DES block of slack
    return memScoped {
        val moved = alloc<size_tVar>()
        val status = out.usePinned { o -> key.safe().usePinned { k -> data.safe().usePinned { d ->
            CCCrypt(
                kCCDecrypt, kCCAlgorithmDES, kCCOptionPKCS7Padding or kCCOptionECBMode,
                k.addressOf(0), key.size.convert(), null, d.addressOf(0), data.size.convert(),
                o.addressOf(0), out.size.convert(), moved.ptr,
            )
        } } }
        check(status == kCCSuccess) { "DES decrypt failed ($status)" }
        out.copyOf(moved.value.toInt())
    }
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = ByteArray(CC_SHA256_DIGEST_LENGTH).also { out ->
    out.usePinned { o -> key.safe().usePinned { k -> data.safe().usePinned { d ->
        CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), o.addressOf(0))
    } } }
}
