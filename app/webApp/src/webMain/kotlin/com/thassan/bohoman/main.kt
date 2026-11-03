package com.thassan.bohoman

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.thassan.bohoman.core.network.AppConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App(
            config = AppConfig(apiBaseUrl = browserApiBaseUrl(), deviceLabel = "Web", browser = true),
            googleSignIn = if (browserGoogleConfigured()) ::googleIdToken else null,
        )
    }
}

private suspend fun googleIdToken(): String {
    beginGoogleSignIn()
    try {
        return withTimeout(300_000) {
            var token = takeGoogleToken()
            while (token.isEmpty()) {
                val error = googleSignInError()
                check(error.isEmpty()) { error }
                delay(100)
                token = takeGoogleToken()
            }
            token
        }
    } finally {
        closeGoogleSignIn()
    }
}

internal expect fun browserApiBaseUrl(): String
internal expect fun browserGoogleConfigured(): Boolean
internal expect fun beginGoogleSignIn()
internal expect fun takeGoogleToken(): String
internal expect fun googleSignInError(): String
internal expect fun closeGoogleSignIn()
