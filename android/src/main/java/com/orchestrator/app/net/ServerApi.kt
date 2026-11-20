package com.orchestrator.app.net

import com.orchestrator.api.Ack
import com.orchestrator.api.ApiJson
import com.orchestrator.api.AppLink
import com.orchestrator.api.AppTokenRequest
import com.orchestrator.api.AuthConfig
import com.orchestrator.api.Battery
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceChange
import com.orchestrator.api.DeviceRegistration
import com.orchestrator.api.DeviceStatus
import com.orchestrator.api.ErrorBody
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.OutgoingMessage
import com.orchestrator.api.SentMessage
import com.orchestrator.api.SignedIn
import com.orchestrator.api.Ticket
import com.orchestrator.api.TicketRequest
import com.orchestrator.app.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * An error from the server, with a message meant for people; [code] 0 means it wasn't reached.
 * [fromServer] says the answer was the server's own JSON error, not a page from something in
 * between (a proxy, a captive portal): only the server's own word signs the app out.
 */
class ServerException(val code: Int, message: String, val fromServer: Boolean = false) : IOException(message)

/**
 * The server's API, as the app uses it (docs/PROTOCOL.md). [base] is the server's address and
 * [token] the app's sign-in; [onSignedOut] is told when the server says the sign-in is no longer
 * good.
 */
class ServerApi(
    private val http: OkHttpClient,
    internal val base: () -> String,
    private val token: () -> String?,
    private val onSignedOut: () -> Unit = {},
) {
    // ---- Accounts ----

    suspend fun authConfig(server: String = base()): AuthConfig = get("$server/api/auth/config", AuthConfig.serializer(), auth = false)

    suspend fun exchangeCode(server: String, code: String, verifier: String, deviceName: String): SignedIn = send(
        "POST", "$server/api/auth/app/token",
        ApiJson.encodeToString(AppTokenRequest.serializer(), AppTokenRequest(code, verifier, deviceName)),
        SignedIn.serializer(), auth = false,
    )

    /** Ends a sign-in that isn't the app's kept one (a sign-in that couldn't be finished). */
    suspend fun logout(server: String, token: String) = attempt {
        send("POST", "$server/api/auth/logout", "{}", Unit.serializer(), bearer = token)
    }

    // ---- Devices ----

    suspend fun devices(): List<Device> = get("${base()}/api/devices", ListSerializer(Device.serializer()))

    /** Registers this device, or again when its name, key or what it can do changed. */
    suspend fun register(registration: DeviceRegistration, server: String = base(), bearer: String? = null): Device = send(
        "POST", "$server/api/devices", ApiJson.encodeToString(DeviceRegistration.serializer(), registration), Device.serializer(), bearer = bearer,
    )

    suspend fun rename(id: String, name: String): Device =
        send("PATCH", "${base()}/api/devices/${enc(id)}", ApiJson.encodeToString(DeviceChange.serializer(), DeviceChange(name)), Device.serializer())

    /** Removes a device, which signs it out (this one too). */
    suspend fun remove(id: String) = send("DELETE", "${base()}/api/devices/${enc(id)}", null, Unit.serializer())

    suspend fun status(battery: Battery?) =
        send("POST", "${base()}/api/devices/me/status", ApiJson.encodeToString(DeviceStatus.serializer(), DeviceStatus(battery)), Unit.serializer())

    // ---- The family's apps ----

    suspend fun apps(): List<AppLink> = get("${base()}/api/apps", ListSerializer(AppLink.serializer()))

    suspend fun ticket(app: String): Ticket =
        send("POST", "${base()}/api/tickets", ApiJson.encodeToString(TicketRequest.serializer(), TicketRequest(app)), Ticket.serializer())

    // ---- Messages ----

    suspend fun sendMessage(message: OutgoingMessage): SentMessage =
        send("POST", "${base()}/api/messages", ApiJson.encodeToString(OutgoingMessage.serializer(), message), SentMessage.serializer())

    suspend fun waiting(): List<IncomingMessage> = get("${base()}/api/messages", ListSerializer(IncomingMessage.serializer()))

    suspend fun ack(ids: List<String>) = send("POST", "${base()}/api/messages/ack", ApiJson.encodeToString(Ack.serializer(), Ack(ids)), Unit.serializer())

    // ---- Plumbing ----

    private suspend fun <T> get(url: String, serializer: KSerializer<T>, auth: Boolean = true): T = send("GET", url, null, serializer, auth)

    internal suspend fun <T> send(method: String, url: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true, bearer: String? = null): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            when {
                body != null -> method(method, body.toRequestBody(JSON))
                method == "GET" -> get()
                else -> method(method, ByteArray(0).toRequestBody(null))
            }
            if (auth) (bearer ?: token())?.let { header("Authorization", "Bearer $it") }
            header("Accept", "application/json")
        }.build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ServerException(0, "Can't reach the server (${e.message ?: e.javaClass.simpleName})")
        }
        // A sign-in that's being tried out ([bearer]) isn't the app's own: its 401 signs nothing out.
        response.use { parse(it, serializer, auth && bearer == null) }
    }

    private fun <T> parse(response: okhttp3.Response, serializer: KSerializer<T>, auth: Boolean): T {
        val text = response.body.string()
        if (!response.isSuccessful) throw failure(response.code, text, auth)
        @Suppress("UNCHECKED_CAST")
        if (serializer == Unit.serializer()) return Unit as T
        return try {
            AppJson.decodeFromString(serializer, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ServerException(response.code, "The server's answer didn't make sense (${e.message?.substringBefore('\n')})")
        }
    }

    /** Only the server's own "sign in first" (a 401 with its JSON error) signs the app out. */
    private fun failure(code: Int, text: String, auth: Boolean): ServerException {
        val message = attempt { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
        if (code == 401 && auth && token() != null && message != null) onSignedOut()
        return ServerException(code, message ?: "The server answered HTTP $code", fromServer = message != null)
    }

    internal fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** How the app reads answers: a value it doesn't know (a newer server's) falls back to the default. */
        internal val AppJson: Json = Json(ApiJson) { coerceInputValues = true }

        /** A server address as typed ("devices.local", "http://10.0.0.5:8080/") made whole, or null. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()?.let { withScheme }
        }

        /** Adds the app's sign-in to requests for the server (and nothing else). */
        fun authInterceptor(base: () -> String, token: () -> String?) = Interceptor { chain ->
            val request = chain.request()
            val server = base().toHttpUrlOrNull()
            val sameServer = server != null && request.url.host == server.host && request.url.port == server.port
            val tokenValue = token()
            if (sameServer && tokenValue != null && request.header("Authorization") == null) {
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $tokenValue").build())
            } else {
                chain.proceed(request)
            }
        }
    }
}
