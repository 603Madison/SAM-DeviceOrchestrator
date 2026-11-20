package com.orchestrator.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings.Global
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.DeviceRegistration
import com.orchestrator.api.Seal
import com.orchestrator.app.attempt
import com.orchestrator.app.data.Prefs
import com.orchestrator.app.data.Settings
import com.orchestrator.app.data.SignedInDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing this phone in to the server. The server's own page does it, in a browser tab: the person
 * signs in there (Authentik, or a password), agrees, and the page hands back a one-time code at
 * `samorchestrator://signed-in`, which only works together with the secret this app made for that
 * sign-in (PKCE), so another app catching the link gets nothing. Then the phone makes its key pair
 * and registers as one of the person's devices.
 */
class SignIn(private val context: Context, private val api: ServerApi, private val prefs: Prefs) {
    private val pending = context.getSharedPreferences("sign-in", Context.MODE_PRIVATE)

    private val _finishing = MutableStateFlow(false)

    /** Whether a browser sign-in is being finished (kept here, so turning the phone doesn't lose it). */
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last browser sign-in couldn't be finished, if it couldn't. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Finishes a browser sign-in from [link] in [scope] (the app's, so turning the phone or leaving
     * the screen can't cut the code exchange short); [finishing] and [error] say how it went.
     */
    fun finishInBackground(link: Uri, scope: CoroutineScope, then: () -> Unit = {}) {
        if (_finishing.value) return
        _finishing.value = true
        _error.value = null
        scope.launch {
            try {
                finish(link)
                then()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't finish signing in"
            } finally {
                _finishing.value = false
            }
        }
    }

    /**
     * Starts a browser sign-in: a new secret, and the pairing code ("123 456") the person sees here
     * and checks on the server's page before allowing it. Then [openBrowserSignIn].
     */
    fun prepareBrowserSignIn(server: String): String {
        val verifier = token(48)
        val state = token(24)
        pending.edit {
            putString("server", server)
            putString("verifier", verifier)
            putString("state", state)
        }
        return pairingCode(challenge(verifier))
    }

    /** Opens the server's sign-in page in a browser tab, for the sign-in [prepareBrowserSignIn] started. */
    fun openBrowserSignIn(server: String) {
        val verifier = pending.getString("verifier", null) ?: return
        val state = pending.getString("state", null) ?: return
        val url = signInUrl(server, challenge(verifier), state, deviceName(context)).toUri()
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url)
    }

    /**
     * Finishes a browser sign-in from the link the server's page opened: swaps the code for the
     * app's token, makes this phone's key pair, and registers it. The secret it was started with
     * is forgotten only once it has all worked, and nothing is kept unless it all did.
     */
    suspend fun finish(link: Uri) {
        val server = pending.getString("server", null) ?: throw ServerException(0, "That sign-in wasn't started here; try again")
        val verifier = pending.getString("verifier", null) ?: throw ServerException(0, "That sign-in expired; try again")
        val expected = pending.getString("state", null)
        val code = link.getQueryParameter("code") ?: throw ServerException(0, "The server sent no sign-in code")
        // The code only works with this sign-in's secret (PKCE); the state, where the page sends it back, must be this sign-in's too.
        link.getQueryParameter("state")?.let { if (it != expected) throw ServerException(0, "That sign-in link wasn't for this sign-in") }
        val name = deviceName(context)
        val config = api.authConfig(server)
        val signedIn = api.exchangeCode(server, code, verifier, name)
        val token = signedIn.token ?: throw ServerException(0, "The server sent no sign-in")
        val keys = Seal.newKeyPair()
        val device = try {
            api.register(registration(name, Seal.publicKeyText(keys.public), Settings().capabilities), server, bearer = token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Half a sign-in is no use: give the token back rather than leave it lying on the server.
            api.logout(server, token)
            throw e
        }
        prefs.signIn(SignedInDevice(server, token, signedIn.user, config.serverName, device.id, device.name, keys))
        pending.edit { clear() }
    }

    companion object {
        const val REDIRECT = "samorchestrator://signed-in"
        private val random = SecureRandom()

        /** The server's page for signing a device in (docs/PROTOCOL.md, "Signing in a device"). */
        fun signInUrl(server: String, challenge: String, state: String, name: String): String =
            "$server/connect-app?challenge=${enc(challenge)}&state=${enc(state)}&name=${enc(name)}&redirect=${enc(REDIRECT)}"

        private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** What this phone tells the server about itself. */
        fun registration(name: String, publicKey: String, capabilities: List<String>) =
            DeviceRegistration(name = name, kind = DeviceKind.ANDROID, model = model(), publicKey = publicKey, capabilities = capabilities)

        /** "Google Pixel 9". */
        fun model(): String = listOf(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }.distinct().joinToString(" ").take(60).ifBlank { "Android phone" }

        /** What the person called this phone (Settings › About phone › Device name), or its model. */
        fun deviceName(context: Context): String {
            val named = attempt { Global.getString(context.contentResolver, "device_name") }.getOrNull()
            return named?.trim()?.take(60)?.ifBlank { null } ?: Build.MODEL.orEmpty().trim().take(60).ifBlank { "Android phone" }
        }

        fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        /**
         * The six digits ("123 456") both the app and the server's page show for a sign-in, worked
         * out from its challenge (as the web's `pairingCode`), so the person can see the page is
         * for the sign-in they started here, not one another app started.
         */
        fun pairingCode(challenge: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var n = 0L
            for (ch in challenge) n = (n * 64 + alphabet.indexOf(ch).coerceAtLeast(0)) % 1_000_000L
            val digits = n.toString().padStart(6, '0')
            return "${digits.take(3)} ${digits.drop(3)}"
        }

        /** The PKCE S256 challenge for [verifier]. */
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
