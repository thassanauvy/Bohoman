package com.thassan.bohoman.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

internal expect fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient
