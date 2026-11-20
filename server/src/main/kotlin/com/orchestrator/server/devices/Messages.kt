package com.orchestrator.server.devices

import com.orchestrator.api.ApiJson
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.Sealed
import com.orchestrator.server.db.Database
import com.orchestrator.server.util.Random

/**
 * What's waiting for each device. Everything here is sealed for the device it's going to: the
 * server keeps it until the device says it arrived (or it gets too old), and can't read it.
 */
class MessageStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Keeps [sealed] for [toDevice]; an undelivered older one with the same [collapseKey] from the same sender goes. */
    suspend fun add(toDevice: String, fromDevice: String?, fromName: String, sealed: Sealed, ttlMs: Long, collapseKey: String?): IncomingMessage {
        val now = clock()
        val message = IncomingMessage(Random.id(), fromDevice, fromName, now, sealed)
        db.tx {
            if (collapseKey != null) {
                update("DELETE FROM messages WHERE to_device = ? AND collapse_key = ? AND from_device IS ?", toDevice, collapseKey, fromDevice)
            }
            update(
                "INSERT INTO messages (id, to_device, from_device, from_name, sent_at, expires_at, collapse_key, sealed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                message.id, toDevice, fromDevice, fromName, now, now + ttlMs, collapseKey, ApiJson.encodeToString(Sealed.serializer(), sealed),
            )
            // A device that never comes back mustn't fill the disk: the oldest go first.
            val over = long("SELECT COUNT(*) FROM messages WHERE to_device = ?", toDevice) - MAX_WAITING
            if (over > 0) {
                update("DELETE FROM messages WHERE id IN (SELECT id FROM messages WHERE to_device = ? ORDER BY sent_at LIMIT ?)", toDevice, over)
            }
        }
        return message
    }

    suspend fun waiting(deviceId: String, limit: Int = 200): List<IncomingMessage> = db.tx {
        query("SELECT * FROM messages WHERE to_device = ? AND expires_at > ? ORDER BY sent_at, rowid LIMIT ?", deviceId, clock(), limit) { row ->
            IncomingMessage(
                id = row.string("id"),
                from = row.stringOrNull("from_device"),
                fromName = row.string("from_name"),
                sentAt = row.long("sent_at"),
                sealed = ApiJson.decodeFromString(Sealed.serializer(), row.string("sealed")),
            )
        }
    }

    suspend fun acknowledge(deviceId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        db.tx { ids.take(1000).forEach { update("DELETE FROM messages WHERE to_device = ? AND id = ?", deviceId, it) } }
    }

    suspend fun purgeExpired() = db.tx { update("DELETE FROM messages WHERE expires_at <= ?", clock()) }

    companion object {
        const val MAX_WAITING = 500
    }
}
