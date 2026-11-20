package com.orchestrator.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orchestrator.api.ApiJson
import com.orchestrator.api.Capabilities
import com.orchestrator.api.Seal
import com.orchestrator.api.User
import com.orchestrator.app.attempt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyPair

private val Context.store by preferencesDataStore("orchestrator")

/** The app's own settings and its sign-in, kept on the phone. */
data class Settings(
    /** The server's address, e.g. https://devices.example.com; empty before signing in. */
    val server: String = "",
    /** The app's sign-in token (long-lived, revocable from the server). Stored sealed. */
    val token: String? = null,
    val user: User? = null,
    val serverName: String = "",
    /** This phone, as the server knows it. */
    val deviceId: String = "",
    val deviceName: String = "",
    /** This phone's key pair (P-256): the public half goes to the server, the private half is stored sealed. */
    val publicKey: String = "",
    val privateKey: String? = null,
    /** Light, dark, or as the phone is. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** All text in Lexend Bold, as the phone's own Bold text setting draws it. */
    val boldText: Boolean = false,
    val palette: Palette = Palette.AURORA,
    /** Send this phone's notifications to the person's other devices. */
    val mirrorOut: Boolean = true,
    /** Show the other devices' notifications here. */
    val showIncoming: Boolean = true,
    /** Keep the line to the server open all the time (off: a look every 15 minutes or so). */
    val alwaysOn: Boolean = true,
    /** Apps whose notifications the person switched off, and ones from the built-in quiet list they switched on. */
    val appsOff: Set<String> = emptySet(),
    val appsOn: Set<String> = emptySet(),
    /** The first-run permission cards were seen. */
    val setupDone: Boolean = false,
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null && deviceId.isNotEmpty() && privateKey != null

    /** What this phone does with what it's sent, as it tells the server. */
    val capabilities: List<String>
        get() = listOfNotNull(
            Capabilities.NOTIFICATIONS.takeIf { showIncoming },
            Capabilities.CLIPBOARD, Capabilities.LINKS, Capabilities.RING, Capabilities.ACTIVITY, Capabilities.MEDIA,
        )

    fun keys(): KeyPair? = privateKey?.let { private -> attempt { Seal.keyPair(private, publicKey) }.getOrNull() }

    /** Never the token or the key, wherever this ends up being printed. */
    override fun toString(): String = "Settings(server=$server, user=${user?.username}, device=$deviceId)"
}

/** Light, dark, or as the phone is. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The themes, as on the web app and the family's other apps, with the two colours each shows. */
enum class Palette(val label: String, val swatchA: Long, val swatchB: Long) {
    AURORA("Aurora", 0xFF6A3FD6, 0xFF12A37C),
    VIOLET("Violet", 0xFF5B3FD1, 0xFF8E3F5C),
    OCEAN("Ocean", 0xFF1D5FC9, 0xFF0E8FA3),
    EMBER("Ember", 0xFFB4471A, 0xFF9C3F60),
}

/** A finished sign-in: everything that's kept from it. */
class SignedInDevice(
    val server: String,
    val token: String,
    val user: User,
    val serverName: String,
    val deviceId: String,
    val deviceName: String,
    val keys: KeyPair,
)

class Prefs(private val context: Context, private val vault: Vault) {
    /** Opening a sealed value asks the Keystore: once for each, not on every read. */
    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()

    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun signIn(device: SignedInDevice) {
        val token = vault.seal(device.token)
        val private = vault.seal(Seal.privateKeyText(device.keys.private))
        context.store.edit {
            it[SERVER] = device.server
            it[TOKEN] = token
            it[USER] = ApiJson.encodeToString(User.serializer(), device.user)
            it[SERVER_NAME] = device.serverName
            it[DEVICE_ID] = device.deviceId
            it[DEVICE_NAME] = device.deviceName
            it[PUBLIC_KEY] = Seal.publicKeyText(device.keys.public)
            it[PRIVATE_KEY] = private
        }
    }

    /** Forgets the sign-in and the key; the server's address and the person's choices stay. */
    suspend fun signOut() {
        opened.clear()
        context.store.edit {
            listOf(TOKEN, USER, DEVICE_ID, DEVICE_NAME, PUBLIC_KEY, PRIVATE_KEY).forEach { key -> it.remove(key) }
            it.remove(SETUP_DONE)
        }
    }

    /** This phone as the server now knows it (after a rename, or registering again). */
    suspend fun device(id: String, name: String) = context.store.edit {
        it[DEVICE_ID] = id
        it[DEVICE_NAME] = name
    }

    /** Changes the person's choices (never the sign-in). */
    suspend fun update(transform: (Settings) -> Settings) = context.store.edit { prefs ->
        val next = transform(read(prefs))
        prefs[THEME_MODE] = next.themeMode.name
        prefs[BOLD_TEXT] = next.boldText
        prefs[PALETTE] = next.palette.name
        prefs[MIRROR_OUT] = next.mirrorOut
        prefs[SHOW_INCOMING] = next.showIncoming
        prefs[ALWAYS_ON] = next.alwaysOn
        prefs[APPS_OFF] = next.appsOff
        prefs[APPS_ON] = next.appsOn
        prefs[SETUP_DONE] = next.setupDone
    }

    private fun open(sealed: String?): String? = sealed?.let { opened[it] ?: vault.open(it)?.also { plain -> opened[sealed] = plain } }

    private fun read(prefs: Preferences) = Settings(
        server = prefs[SERVER].orEmpty(),
        token = open(prefs[TOKEN]),
        user = prefs[USER]?.let { attempt { ApiJson.decodeFromString(User.serializer(), it) }.getOrNull() },
        serverName = prefs[SERVER_NAME].orEmpty(),
        deviceId = prefs[DEVICE_ID].orEmpty(),
        deviceName = prefs[DEVICE_NAME].orEmpty(),
        publicKey = prefs[PUBLIC_KEY].orEmpty(),
        privateKey = open(prefs[PRIVATE_KEY]),
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[THEME_MODE] } ?: ThemeMode.SYSTEM,
        boldText = prefs[BOLD_TEXT] ?: false,
        palette = Palette.entries.firstOrNull { it.name == prefs[PALETTE] } ?: Palette.AURORA,
        mirrorOut = prefs[MIRROR_OUT] ?: true,
        showIncoming = prefs[SHOW_INCOMING] ?: true,
        alwaysOn = prefs[ALWAYS_ON] ?: true,
        appsOff = prefs[APPS_OFF].orEmpty(),
        appsOn = prefs[APPS_ON].orEmpty(),
        setupDone = prefs[SETUP_DONE] ?: false,
    )

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token_sealed")
        val USER = stringPreferencesKey("user")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PUBLIC_KEY = stringPreferencesKey("public_key")
        val PRIVATE_KEY = stringPreferencesKey("private_key_sealed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val PALETTE = stringPreferencesKey("palette")
        val MIRROR_OUT = booleanPreferencesKey("mirror_out")
        val SHOW_INCOMING = booleanPreferencesKey("show_incoming")
        val ALWAYS_ON = booleanPreferencesKey("always_on")
        val APPS_OFF = stringSetPreferencesKey("apps_off")
        val APPS_ON = stringSetPreferencesKey("apps_on")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
    }
}
