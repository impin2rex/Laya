package com.music.laya.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

actual fun ktorClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) { config() }
