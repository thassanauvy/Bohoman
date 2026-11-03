package com.thassan.bohoman.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class AndroidGoogleSignIn(private val activity: Activity, private val clientId: String) {
    suspend fun idToken(): String {
        require(clientId.isNotBlank()) { "Google sign-in is not configured." }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google did not return an ID token. Please try signing in again."
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
