package com.orchestrator.app.devices

import com.orchestrator.api.Device
import com.orchestrator.api.Payload
import com.orchestrator.api.SentMessage
import com.orchestrator.app.attempt
import com.orchestrator.app.data.Settings
import com.orchestrator.app.net.ServerApi
import com.orchestrator.app.net.ServerException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The person's devices, as the server last said: from the line as they change, or asked for. */
class DeviceBook(private val api: ServerApi) {
    private val _devices = MutableStateFlow<List<Device>?>(null)

    /** Null until the server has been heard from. */
    val devices: StateFlow<List<Device>?> = _devices.asStateFlow()

    fun set(devices: List<Device>) {
        _devices.value = devices
    }

    suspend fun refresh(): List<Device> = api.devices().also(::set)

    /** The list, asking the server when it isn't known yet. */
    suspend fun known(): List<Device> = _devices.value ?: refresh()

    fun clear() {
        _devices.value = null
    }
}

/** Sends something, sealed, to the person's other devices. */
class Messenger(private val api: ServerApi, private val book: DeviceBook, private val settings: () -> Settings) {
    /**
     * Seals [payload] for each of the other devices that can take it (or only those in [only]) and
     * hands it to the server. Null when there's nobody to send it to.
     */
    suspend fun send(payload: Payload, only: Set<String>? = null): SentMessage? {
        val self = settings().deviceId
        if (!settings().signedIn) throw ServerException(0, "Sign in first")
        var to = Envelope.recipients(payload, book.known(), self, only)
        // A device the list doesn't have yet (it signed in a moment ago): look once more.
        if (to.isEmpty() || (only != null && to.size < only.size)) to = Envelope.recipients(payload, book.refresh(), self, only)
        val message = Envelope.seal(payload, to) ?: return null
        return api.sendMessage(message)
    }

    /** For things nobody is waiting on (a mirrored notification): failure is quiet. */
    suspend fun sendQuietly(payload: Payload, only: Set<String>? = null) {
        attempt { send(payload, only) }
    }
}
