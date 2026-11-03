package com.thassan.bohoman.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(CIO) { configure() }
