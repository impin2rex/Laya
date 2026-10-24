package com.music.laya.platform

expect fun normalizeNfd(text: String): String
expect fun normalizeNfkc(text: String): String
expect fun languageOf(tag: String): String
expect fun systemLanguageTag(): String
expect fun displayLanguageName(code: String, inTag: String): String
