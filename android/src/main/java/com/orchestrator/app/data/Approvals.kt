package com.orchestrator.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.orchestrator.api.ApiJson
import com.orchestrator.app.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * An app on this phone the person let sign in as them: its package, the SHA-256 of the certificate
 * it was signed with when they said yes, and which of the family's servers it may sign in to.
 */
@Serializable
data class ApprovedApp(
    val packageName: String,
    val certificate: String,
    /** "music", "video"…: what the person was asked about. */
    val apps: Set<String>,
    val label: String = "",
    val approvedAt: Long = 0,
)

/**
 * Which apps may sign in through the orchestrator. Kept in SharedPreferences (not the DataStore):
 * the provider answers other apps on their thread and has to read this without waiting.
 */
class Approvals(private val store: SharedPreferences) {
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<ApprovedApp>> = _list.asStateFlow()

    private fun load(): List<ApprovedApp> = store.all.values.mapNotNull { value ->
        (value as? String)?.let { attempt { ApiJson.decodeFromString(ApprovedApp.serializer(), it) }.getOrNull() }
    }.sortedBy { it.label.lowercase() }

    /** Whether this very app (the package, signed with this certificate) may sign in to [app]. */
    fun allows(packageName: String, certificate: String, app: String): Boolean =
        _list.value.any { it.packageName == packageName && it.certificate == certificate && app in it.apps }

    /** Whether this very app was approved for anything: it may then know who is signed in. */
    fun knows(packageName: String, certificate: String): Boolean =
        _list.value.any { it.packageName == packageName && it.certificate == certificate }

    /** One entry for each package: the same app approved for another server adds to it; another certificate starts again. */
    @Synchronized
    fun approve(packageName: String, certificate: String, app: String, label: String, now: Long = System.currentTimeMillis()) {
        val before = _list.value.firstOrNull { it.packageName == packageName && it.certificate == certificate }
        val next = ApprovedApp(packageName, certificate, before?.apps.orEmpty() + app, label, now)
        store.edit { putString(packageName, ApiJson.encodeToString(ApprovedApp.serializer(), next)) }
        _list.value = load()
    }

    @Synchronized
    fun remove(packageName: String) {
        store.edit { remove(packageName) }
        _list.value = load()
    }

    @Synchronized
    fun clear() {
        store.edit { clear() }
        _list.value = emptyList()
    }
}
