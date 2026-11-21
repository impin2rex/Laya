package com.music.laya.platform

expect fun md5(data: ByteArray): ByteArray
expect fun sha1(data: ByteArray): ByteArray
expect fun sha256(data: ByteArray): ByteArray
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** DES/ECB/PKCS5 decryption, for JioSaavn's encrypted media URLs. */
expect fun desEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray

/** Lowercase hex MD5 of [input]'s UTF-8 bytes — what a Last.fm API signature is made of. */
fun md5Hex(input: String): String =
    md5(input.encodeToByteArray()).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
