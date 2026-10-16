package org.lujarch.files

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Files (FILE-1) as the target of a request: the system's Files app, the activity that picks files,
 * and the authorities of its own content providers.
 * [component] is null when the request cannot go to Files; [problem] then says why.
 */
internal class Files(val app: RoleApp, val component: ComponentName?, val authorities: Set<String>) {
    val problem: Problem
        get() = Problem.files(app)

    companion object {
        /**
         * Files now. Its picking activity is the one answering OPEN_DOCUMENT in its package
         * (Material Files' FileListActivity, which handles every picker action and
         * OPEN_DOCUMENT_TREE when started explicitly, though its manifest leaves that filter
         * out). Blocking (PackageManager).
         */
        fun find(context: Context): Files {
            val app = SystemApps.find(context, SystemApps.FILES)
            val pkg = app.pkg
            if (!app.ok) return Files(app, null, emptySet())
            val pm = context.packageManager
            val probe = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .setPackage(pkg)
            val ri = pm.resolveActivity(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            if (ri?.activityInfo == null) {
                val disabled = pm.resolveActivity(
                    probe,
                    PackageManager.ResolveInfoFlags.of(
                        (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
                    ),
                ) != null
                Log.w(TAG, "files: $pkg has no ${if (disabled) "enabled " else ""}activity for OPEN_DOCUMENT")
                val state = if (disabled) RoleApp(RoleApp.State.DISABLED, pkg, app.name, true) else app
                return Files(state, null, emptySet())
            }
            val authorities = try {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
                    ),
                ).providers.orEmpty().flatMap { it.authority?.split(';').orEmpty() }.toSet()
            } catch (e: PackageManager.NameNotFoundException) {
                emptySet()
            }
            return Files(app, ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), authorities)
        }
    }
}
