package com.music.laya.platform

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
actual fun desEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("DES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES")); doFinal(data) }
