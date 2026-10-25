package com.music.laya.platform

expect fun sha1(data: ByteArray): ByteArray
expect fun sha256(data: ByteArray): ByteArray
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** DES/ECB/PKCS5 decryption, for JioSaavn's encrypted media URLs. */
expect fun desEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray
