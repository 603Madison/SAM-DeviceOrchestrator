package com.orchestrator.app.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends at most one value for each key every [intervalMs]: the first at once, and of those that
 * follow quickly only the newest, once the interval is up. A notification that updates ten times a
 * second (a download, a chat being typed) goes out as it started and as it ended.
 */
class Coalescer<T>(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val now: () -> Long,
    private val send: suspend (key: String, value: T) -> Unit,
) {
    private val lastSent = HashMap<String, Long>()
    private val lastValue = HashMap<String, T>()
    private val waiting = HashMap<String, Job>()

    @Synchronized
    fun offer(key: String, value: T) {
        waiting.remove(key)?.cancel()
        // The same again (an app re-posting what it already showed) isn't news.
        if (lastValue[key] == value) return
        val wait = (lastSent[key] ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) 0 else it + intervalMs - now() }
        if (wait <= 0) {
            go(key, value)
        } else {
            waiting[key] = scope.launch {
                delay(wait)
                release(key, value)
            }
        }
    }

    @Synchronized
    private fun release(key: String, value: T) {
        waiting.remove(key)
        go(key, value)
    }

    private fun go(key: String, value: T) {
        lastSent[key] = now()
        lastValue[key] = value
        scope.launch { send(key, value) }
    }

    /** The key is gone (the notification was removed): nothing more of it goes out. True if anything of it ever did. */
    @Synchronized
    fun forget(key: String): Boolean {
        waiting.remove(key)?.cancel()
        lastValue.remove(key)
        return lastSent.remove(key) != null
    }

    @Synchronized
    fun clear() {
        waiting.values.forEach { it.cancel() }
        waiting.clear()
        lastSent.clear()
        lastValue.clear()
    }
}
