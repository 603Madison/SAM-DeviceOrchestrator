package com.orchestrator.app.link

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.orchestrator.app.container

/**
 * How the family's apps on this phone reach the orchestrator: `ContentResolver.call` on
 * `content://app.superawesomemagical.orchestrator.link` with `status`, `signIn` or
 * `publishActivity` (docs/PROTOCOL.md; `kits/android/OrchestratorLink.kt` is the other side).
 *
 * Any app can call it, so each call first works out who is calling: the package Android reports,
 * checked against the calling uid, and that package's signing certificate. [LinkGate] decides
 * from there. Calls may wait on the server (callers are told to stay off their main thread), but
 * never longer than about ten seconds.
 */
class LinkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val caller = Callers.identify(context, callingPackage, Binder.getCallingUid())
        val gate = context.container.gate
        // What follows is the app's own work (its sign-in, its network), not the caller's.
        val identity = Binder.clearCallingIdentity()
        val answer = try {
            when (method) {
                "status" -> gate.status(caller)
                "signIn" -> gate.signIn(caller, arg)
                "publishActivity" -> gate.publishActivity(
                    caller, arg ?: extras?.getString("app"),
                    extras?.getString("title"), extras?.getString("detail"), extras?.getString("url"),
                )
                else -> return null
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return Bundle().apply {
            answer.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }
    }

    // Nothing to read or write: the provider only answers calls.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
