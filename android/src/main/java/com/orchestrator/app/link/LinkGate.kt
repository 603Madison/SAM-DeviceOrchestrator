package com.orchestrator.app.link

import com.orchestrator.api.Payload
import com.orchestrator.api.Ticket
import com.orchestrator.app.attempt
import com.orchestrator.app.data.Approvals

/** An app on this phone asking the orchestrator for something: its package and its signing certificate's SHA-256. */
data class Caller(val packageName: String, val certificate: String)

/** Who is signed in here, for an app that may know. */
data class Session(val server: String, val username: String, val displayName: String)

/**
 * What the family's apps may ask of the orchestrator on this phone (docs/PROTOCOL.md, "Android"),
 * and who may ask it. Every answer starts from who is calling:
 *
 * - nobody the phone can vouch for (a package name that isn't the caller's own): nothing;
 * - an app the person hasn't approved: only whether someone is signed in, and "ask them";
 * - an app the person approved, signed with the same certificate as then: who is signed in, a
 *   ticket for the server it was approved for, and saying what the person is doing.
 *
 * The server is only ever asked for a ticket for an approved caller. Plain Kotlin, so the rules
 * are tested without a phone.
 */
class LinkGate(
    private val session: () -> Session?,
    private val approvals: Approvals,
    /** Asks the server for a ticket; waits (the caller is off its main thread), and throws with words for a log. */
    private val ticket: (app: String) -> Ticket,
    /** Sends what the person is doing to their other devices; waits. */
    private val publish: (Payload.Activity) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Said(val what: Payload.Activity, val at: Long)

    private val lastSaid = HashMap<String, Said>()

    fun status(caller: Caller?): Map<String, Any> {
        val who = session() ?: return mapOf("signedIn" to false)
        if (caller == null || !approvals.knows(caller.packageName, caller.certificate)) return mapOf("signedIn" to true)
        return mapOf("signedIn" to true, "server" to who.server, "username" to who.username, "displayName" to who.displayName)
    }

    fun signIn(caller: Caller?, app: String?): Map<String, Any> {
        if (caller == null) return error("Couldn't tell which app is asking")
        val id = appId(app) ?: return error("That isn't an app")
        if (session() == null) return error("Nobody is signed in to SuperAwesomeMagicalOrchestrator")
        if (!approvals.allows(caller.packageName, caller.certificate, id)) return mapOf("needsApproval" to true)
        return attempt { ticket(id) }.fold(
            { mapOf("ok" to true, "url" to it.url, "ticket" to it.ticket) },
            { error(it.message ?: "Couldn't get a sign-in") },
        )
    }

    fun publishActivity(caller: Caller?, app: String?, title: String?, detail: String?, url: String?): Map<String, Any> {
        if (caller == null) return error("Couldn't tell which app is asking")
        val id = appId(app) ?: return error("That isn't an app")
        if (session() == null) return error("Nobody is signed in to SuperAwesomeMagicalOrchestrator")
        if (!approvals.allows(caller.packageName, caller.certificate, id)) return mapOf("needsApproval" to true)
        val at = now()
        val activity = Payload.Activity(
            app = id,
            title = title?.trim()?.take(200)?.ifBlank { null },
            detail = detail.orEmpty().trim().take(300),
            // Only what another device would open: a web address or one of the family's app links.
            url = url.orEmpty().takeIf { Links.allowed(it) }.orEmpty(),
            at = at,
        )
        synchronized(lastSaid) {
            val last = lastSaid[id]
            // The same again within a few seconds (a player ticking) isn't worth sending.
            if (last != null && at - last.at in 0 until REPEAT_MS && last.what.copy(at = 0) == activity.copy(at = 0)) return mapOf("ok" to true)
            lastSaid[id] = Said(activity, at)
        }
        return attempt { publish(activity) }.fold({ mapOf("ok" to true) }, { error(it.message ?: "Couldn't send it") })
    }

    private fun error(words: String): Map<String, Any> = mapOf("error" to words)

    companion object {
        const val REPEAT_MS = 5_000L

        /** "music", "video": lower case, short, nothing else. */
        fun appId(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,31}")) }
    }
}
