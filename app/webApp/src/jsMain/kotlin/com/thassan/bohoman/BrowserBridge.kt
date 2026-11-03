package com.thassan.bohoman

internal actual fun browserApiBaseUrl(): String = js("window.bohomanConfig.apiBaseUrl || window.location.origin") as String
internal actual fun browserGoogleConfigured(): Boolean = js("!!window.bohomanConfig.googleClientId") as Boolean
internal actual fun beginGoogleSignIn() { js("window.bohomanAuth.begin()") }
internal actual fun takeGoogleToken(): String = js("window.bohomanAuth.takeToken()") as String
internal actual fun googleSignInError(): String = js("window.bohomanAuth.error") as String
internal actual fun closeGoogleSignIn() { js("window.bohomanAuth.close()") }
