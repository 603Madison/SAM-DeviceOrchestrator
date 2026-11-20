package com.orchestrator.app.link

import android.content.Context
import android.content.pm.PackageManager
import com.orchestrator.app.attempt
import java.security.MessageDigest

/** Working out which app is asking, in a way it can't lie about. */
object Callers {
    /**
     * The caller, when [claimedPackage] (what the call says it is) really is one of the packages of
     * [uid] (what Android's Binder says made the call); null otherwise.
     */
    fun identify(context: Context, claimedPackage: String?, uid: Int): Caller? {
        val name = claimedPackage?.takeIf { it.isNotBlank() } ?: return null
        val packages = context.packageManager.getPackagesForUid(uid) ?: return null
        if (name !in packages) return null
        return Caller(name, certificate(context, name) ?: return null)
    }

    /**
     * The SHA-256 of the certificate [packageName] is signed with now (of each, sorted, for the rare
     * app with several signers): what "the same app" means, since a package name alone can be
     * reused by anything once the real app is gone.
     */
    fun certificate(context: Context, packageName: String): String? = attempt {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        signers.map { digest(it.toByteArray()) }.sorted().joinToString(",").ifEmpty { null }
    }.getOrNull()

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** "8d:e5:70…" for showing a person. */
    fun pretty(certificate: String): String = certificate.substringBefore(',').chunked(2).joinToString(":")
}
