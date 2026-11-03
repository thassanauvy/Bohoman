package com.thassan.bohoman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.thassan.bohoman.auth.AndroidGoogleSignIn
import com.thassan.bohoman.auth.KeystoreRefreshTokenStore
import com.thassan.bohoman.core.network.AppConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val tokenStore = remember { KeystoreRefreshTokenStore(applicationContext) }
            val signIn = remember { AndroidGoogleSignIn(this, BuildConfig.GOOGLE_WEB_CLIENT_ID) }
            App(
                config = AppConfig(
                    apiBaseUrl = BuildConfig.API_BASE_URL,
                    deviceLabel = "Android",
                    signInSetupMessage = "Google sign-in needs this app's Google client ID. See the project setup guide.",
                ),
                googleSignIn = if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) null else { { signIn.idToken() } },
                tokenStore = tokenStore,
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
