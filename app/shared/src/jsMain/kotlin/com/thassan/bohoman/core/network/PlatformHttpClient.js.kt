package com.thassan.bohoman.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

internal actual fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Js) {
        engine { configureRequest { credentials = "include" } }
        configure()
    }
