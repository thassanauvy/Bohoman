package com.thassan.bohoman

@JsFun("() => window.bohomanConfig.apiBaseUrl || window.location.origin")
private external fun apiBaseUrl(): String
@JsFun("() => !!window.bohomanConfig.googleClientId")
private external fun configured(): Boolean
@JsFun("() => window.bohomanAuth.begin()")
private external fun begin()
@JsFun("() => window.bohomanAuth.takeToken()")
private external fun takeToken(): String
@JsFun("() => window.bohomanAuth.error")
private external fun error(): String
@JsFun("() => window.bohomanAuth.close()")
private external fun close()

internal actual fun browserApiBaseUrl(): String = apiBaseUrl()
internal actual fun browserGoogleConfigured(): Boolean = configured()
internal actual fun beginGoogleSignIn() = begin()
internal actual fun takeGoogleToken(): String = takeToken()
internal actual fun googleSignInError(): String = error()
internal actual fun closeGoogleSignIn() = close()
