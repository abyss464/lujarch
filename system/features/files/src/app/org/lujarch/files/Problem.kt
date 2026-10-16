package org.lujarch.files

import android.os.Bundle

/**
 * Why a request cannot be served, as the dialog shows it. Kept in the activity's saved state,
 * so the dialog comes back when the activity is recreated.
 */
internal class Problem(
    val kind: Kind,
    /** The package whose App info the dialog offers (Files' problems). */
    val pkg: String? = null,
    /** The name in the text: Files' label, or the owner of a location (NO_HOME). */
    val name: String? = null,
) {
    enum class Kind(val afterPick: Boolean = false) {
        FILES_NOT_INSTALLED,
        FILES_NOT_SYSTEM,
        FILES_DISABLED,
        /** Files is served but has no activity that picks files. */
        FILES_NO_PICKER,
        /** The folder is one ExternalStorageProvider blocks from tree access. */
        FOLDER_BLOCKED(afterPick = true),
        /** The folder is not a document of a DocumentsProvider (a network share, an archive). */
        FOLDER_NOT_LOCAL(afterPick = true),
        /** The file to save could not be created, or the file to replace is not writable. */
        WRITE_FAILED(afterPick = true),
        /** The system refused to let this app hand the picked document over. */
        GRANT_FAILED(afterPick = true),
        /** A browsed location no app on the phone can open. */
        NO_HOME,
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_KIND, kind.name)
        putString(KEY_PKG, pkg)
        putString(KEY_NAME, name)
    }

    override fun toString(): String = "$kind" + (pkg?.let { " ($it)" } ?: "") + (name?.let { " \"$it\"" } ?: "")

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_PKG = "pkg"
        private const val KEY_NAME = "name"

        fun from(b: Bundle?): Problem? {
            val kind = b?.getString(KEY_KIND)?.let { k -> Kind.values().firstOrNull { it.name == k } } ?: return null
            return Problem(kind, b.getString(KEY_PKG), b.getString(KEY_NAME))
        }

        /** Files cannot take a request; served but without a picking activity is FILES_NO_PICKER. */
        fun files(app: RoleApp): Problem {
            val kind = when (app.state) {
                RoleApp.State.NOT_INSTALLED -> Kind.FILES_NOT_INSTALLED
                RoleApp.State.NOT_SYSTEM -> Kind.FILES_NOT_SYSTEM
                RoleApp.State.DISABLED -> Kind.FILES_DISABLED
                RoleApp.State.OK -> Kind.FILES_NO_PICKER
            }
            return Problem(kind, app.pkg.takeIf { app.known }, app.name)
        }
    }
}
