package com.orchestrator.app.devices

import android.content.SharedPreferences
import androidx.core.content.edit
import com.orchestrator.api.ApiJson
import com.orchestrator.api.Payload
import com.orchestrator.app.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** What the person is doing in an app on another device: "Carry on" opens [url] here. */
@Serializable
data class Happening(
    val device: String,
    val deviceName: String,
    val app: String,
    val title: String,
    val detail: String = "",
    val url: String = "",
    /** When this phone heard of it. */
    val at: Long,
)

/** The latest for each device and app, forgotten after 15 minutes (docs/PROTOCOL.md, `activity`). */
class Activities(private val store: SharedPreferences?, private val now: () -> Long = System::currentTimeMillis) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Happening>> = _all.asStateFlow()

    private fun load(): List<Happening> = store?.getString(KEY, null)
        ?.let { attempt { ApiJson.decodeFromString(ListSerializer(Happening.serializer()), it) }.getOrNull() }
        .orEmpty()

    /** What's still fresh, newest first. */
    fun current(): List<Happening> = fresh(_all.value, now())

    @Synchronized
    fun heard(device: String, deviceName: String, activity: Payload.Activity) {
        val others = _all.value.filterNot { it.device == device && it.app == activity.app }
        val title = activity.title
        // A null title: they stopped.
        val next = if (title == null) others else others + Happening(device, deviceName, activity.app, title, activity.detail, activity.url, now())
        save(fresh(next, now()))
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Happening>) {
        _all.value = list
        store?.edit { putString(KEY, ApiJson.encodeToString(ListSerializer(Happening.serializer()), list)) }
    }

    companion object {
        private const val KEY = "activities"
        const val KEEP_MS = 15 * 60_000L

        fun fresh(list: List<Happening>, now: Long): List<Happening> = list.filter { now - it.at < KEEP_MS }.sortedByDescending { it.at }
    }
}
