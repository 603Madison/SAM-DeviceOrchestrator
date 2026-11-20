package com.orchestrator.server.devices

import com.orchestrator.api.Battery
import com.orchestrator.api.Device
import com.orchestrator.api.DeviceKind
import com.orchestrator.api.DeviceRegistration
import com.orchestrator.api.Seal
import com.orchestrator.server.ApiError
import com.orchestrator.server.db.Database
import com.orchestrator.server.db.Row
import com.orchestrator.server.util.Random

/** A person's devices. Each is one app sign-in (its session), so signing a device out removes it. */
class DeviceStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun list(userId: String): List<Device> =
        db.tx { query("SELECT * FROM devices WHERE user_id = ? ORDER BY name COLLATE NOCASE, created_at", userId, map = ::toDevice) }

    suspend fun byId(userId: String, id: String): Device? =
        db.tx { one("SELECT * FROM devices WHERE user_id = ? AND id = ?", userId, id, map = ::toDevice) }

    suspend fun bySession(sessionId: String): Device? = db.tx { one("SELECT * FROM devices WHERE session_id = ?", sessionId, map = ::toDevice) }

    /** The session a device signed in with: ending it signs the device out. */
    suspend fun sessionOf(userId: String, id: String): String? =
        db.tx { one("SELECT session_id FROM devices WHERE user_id = ? AND id = ?", userId, id) { it.string("session_id") } }

    /** Registers the device signed in with [sessionId], or updates it (a new name, a new key). */
    suspend fun register(userId: String, sessionId: String, request: DeviceRegistration): Device {
        val name = validName(request.name)
        val key = request.publicKey.trim()
        try {
            Seal.publicKey(key)
        } catch (e: IllegalArgumentException) {
            throw ApiError.badRequest("That isn't a device key")
        }
        val capabilities = request.capabilities.map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z0-9._-]{1,32}")) }.distinct().take(MAX_CAPABILITIES)
        val model = request.model.trim().take(MAX_NAME)
        val now = clock()
        db.tx {
            val existing = one("SELECT id FROM devices WHERE session_id = ?", sessionId) { it.string("id") }
            if (existing != null) {
                // A new key means what's waiting was sealed for the old one: nobody can read it now.
                val oldKey = one("SELECT public_key FROM devices WHERE id = ?", existing) { it.string("public_key") }
                if (oldKey != key) update("DELETE FROM messages WHERE to_device = ?", existing)
                update(
                    "UPDATE devices SET name = ?, kind = ?, model = ?, public_key = ?, capabilities = ?, last_seen_at = ? WHERE id = ?",
                    name, request.kind, model, key, capabilities.joinToString(","), now, existing,
                )
            } else {
                if (long("SELECT COUNT(*) FROM devices WHERE user_id = ?", userId) >= MAX_DEVICES) {
                    throw ApiError.badRequest("That's $MAX_DEVICES devices already: remove one you no longer use first")
                }
                update(
                    """INSERT INTO devices (id, user_id, session_id, name, kind, model, public_key, capabilities, created_at, last_seen_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Random.id(), userId, sessionId, name, request.kind, model, key, capabilities.joinToString(","), now, now,
                )
            }
        }
        return bySession(sessionId)!!
    }

    suspend fun rename(userId: String, id: String, name: String): Device {
        val valid = validName(name)
        val changed = db.tx { update("UPDATE devices SET name = ? WHERE user_id = ? AND id = ?", valid, userId, id) }
        if (changed == 0) throw ApiError.notFound("No such device")
        return byId(userId, id)!!
    }

    suspend fun seen(id: String, battery: Battery? = null) = db.tx {
        if (battery != null) {
            update(
                "UPDATE devices SET last_seen_at = ?, battery_level = ?, battery_charging = ? WHERE id = ?",
                clock(), battery.level.coerceIn(0, 100), battery.charging, id,
            )
        } else {
            update("UPDATE devices SET last_seen_at = ? WHERE id = ?", clock(), id)
        }
    }

    companion object {
        const val MAX_NAME = 64
        const val MAX_DEVICES = 50
        const val MAX_CAPABILITIES = 24

        fun validName(name: String): String =
            name.trim().filter { it.code >= 0x20 && it.code != 0x7F }.take(MAX_NAME).ifBlank { throw ApiError.badRequest("A device needs a name") }

        fun toDevice(row: Row) = Device(
            id = row.string("id"),
            name = row.string("name"),
            kind = runCatching { DeviceKind.valueOf(row.string("kind")) }.getOrDefault(DeviceKind.OTHER),
            model = row.string("model"),
            publicKey = row.string("public_key"),
            capabilities = row.string("capabilities").split(',').filter { it.isNotEmpty() },
            lastSeenAt = row.long("last_seen_at"),
            createdAt = row.long("created_at"),
            battery = row.intOrNull("battery_level")?.let { Battery(it, row.bool("battery_charging")) },
        )
    }
}
