package com.orchestrator.app.ui.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchestrator.api.AuthConfig
import com.orchestrator.app.R
import com.orchestrator.app.net.ServerAddress
import com.orchestrator.app.net.ServerApi
import com.orchestrator.app.ui.components.AppCard
import com.orchestrator.app.ui.components.Backdrop
import com.orchestrator.app.ui.components.Notice
import com.orchestrator.app.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What signing in needs from the rest of the app (so it can be drawn on its own). */
class SignInActions(
    val checkServer: suspend (String) -> AuthConfig,
    /** Starts a browser sign-in and returns its pairing code, to show before the page opens. */
    val browserSignIn: (String) -> String,
    /** Opens the page for the sign-in just started. */
    val openBrowser: (String) -> Unit = {},
)

@Composable
fun SignInRoute(finishing: Boolean, error: String?) {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    SignInScreen(
        initialServer = settings.server,
        finishing = finishing,
        error = error,
        actions = SignInActions(
            checkServer = { c.api.authConfig(it) },
            browserSignIn = { c.signIn.prepareBrowserSignIn(it) },
            openBrowser = { c.signIn.openBrowserSignIn(it) },
        ),
    )
}

/** Sign-in: the server's address, then the server's own sign-in page in a browser tab. */
@Composable
fun SignInScreen(
    initialServer: String,
    finishing: Boolean,
    error: String?,
    actions: SignInActions,
    initialConfig: Pair<String, AuthConfig>? = null,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(initialServer.removePrefix("https://")) }
    var config by remember { mutableStateOf(initialConfig) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(error) { mutableStateOf(error) }
    // A browser sign-in just started: its server and the pairing code to check on the page.
    var pairing by remember { mutableStateOf<Pair<String, String>?>(null) }
    pairing?.let { (server, code) ->
        AlertDialog(
            onDismissRequest = { pairing = null },
            title = { Text("Your code: $code") },
            text = { Text("The sign-in page will show the same code. Only allow it there if they match.") },
            confirmButton = {
                TextButton(onClick = {
                    pairing = null
                    actions.openBrowser(server)
                }) { Text("Open the sign-in page") }
            },
            dismissButton = { TextButton(onClick = { pairing = null }) { Text("Cancel") } },
        )
    }

    fun check() {
        val server = ServerApi.normalize(address)
        if (server == null) {
            message = "That isn't a web address"
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                config = server to actions.checkServer(server)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message = "Couldn't reach a SuperAwesomeMagicalOrchestrator server there (${e.message ?: e.javaClass.simpleName})"
            }
            busy = false
        }
    }

    Backdrop {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // The launcher icon is adaptive (painterResource can't draw those): its parts, drawn here.
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Text(
                    config?.second?.serverName ?: stringResource(R.string.app_full_name),
                    // The family's names are one long word: small enough to stay on one line on a phone.
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (config == null) "Sign in once on this phone, and your apps and your other devices know it's you."
                    else "Sign in to connect this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AppCard(padding = 20.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        message?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                        val current = config
                        when {
                            finishing -> {
                                CircularProgressIndicator()
                                Text("Finishing signing in…")
                            }
                            current == null -> {
                                OutlinedTextField(
                                    address, { address = it },
                                    label = { Text("Server address") },
                                    placeholder = { Text("devices.example.com") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { if (!busy && address.isNotBlank()) check() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(enabled = !busy && address.isNotBlank(), onClick = ::check, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (busy) "Checking…" else "Continue")
                                }
                            }
                            else -> {
                                val (server, auth) = current
                                if (ServerAddress.unencrypted(server)) Notice(ServerAddress.WARNING, icon = Icons.Rounded.LockOpen)
                                Button(onClick = { pairing = server to actions.browserSignIn(server) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                                    Text(auth.oidc?.label ?: "Sign in", modifier = Modifier.padding(start = 8.dp))
                                }
                                Text(
                                    "Opens the server's sign-in page in your browser, then brings you back here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(
                                    onClick = { config = null },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                ) { Text("Use another server") }
                            }
                        }
                    }
                }
                config?.second?.let {
                    Text("Server ${it.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
