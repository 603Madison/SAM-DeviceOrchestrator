package com.orchestrator.server.devices

import com.orchestrator.api.Device
import com.orchestrator.api.IncomingMessage
import com.orchestrator.api.StreamFrame
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/** The devices connected right now, and a way to hand each what's for it at once. */
class Hub {
    /** One open stream. [frames] is what's on its way to the device; a full queue means it has stalled. */
    class Connection(val userId: String, val deviceId: String) {
        val frames = Channel<StreamFrame>(capacity = 256)
    }

    private val connections = ConcurrentHashMap<String, Connection>()

    /** [deviceId] connected; an earlier connection of the same device is closed (it reconnected). */
    fun connect(userId: String, deviceId: String): Connection {
        val connection = Connection(userId, deviceId)
        connections.put(deviceId, connection)?.frames?.close()
        return connection
    }

    fun disconnect(connection: Connection) {
        connections.remove(connection.deviceId, connection)
        connection.frames.close()
    }

    fun online(deviceId: String): Boolean = connections.containsKey(deviceId)

    /** Whether [message] went out to a connected [deviceId] (it stays stored until the device says it arrived). */
    fun deliver(deviceId: String, message: IncomingMessage): Boolean =
        connections[deviceId]?.frames?.trySend(StreamFrame.Message(message))?.isSuccess == true

    /** Tells every connected device of [userId] what the devices are now. */
    fun devicesChanged(userId: String, devices: (String) -> List<Device>) {
        connections.values.filter { it.userId == userId }.forEach { it.frames.trySend(StreamFrame.Devices(devices(it.deviceId))) }
    }

    /** Ends the stream of a device that was signed out or removed. */
    fun drop(deviceId: String) {
        connections.remove(deviceId)?.frames?.close()
    }
}
