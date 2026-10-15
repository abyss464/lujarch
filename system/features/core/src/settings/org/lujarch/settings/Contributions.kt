package org.lujarch.settings

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object Contributions {
    private const val TAG = "lujarch_core"
    private const val PREFIX = "org.lujarch.settings.contribution."

    fun all(context: Context): List<Contribution> {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
        val meta = info.metaData ?: return emptyList()
        return meta.keySet().filter { it.startsWith(PREFIX) }.sorted().mapNotNull { key ->
            val name = meta.getString(key)
            try {
                Class.forName(name).getDeclaredConstructor().newInstance() as Contribution
            } catch (e: ReflectiveOperationException) {
                Log.e(TAG, "cannot load contribution $key: $name", e)
                null
            } catch (e: ClassCastException) {
                Log.e(TAG, "$name is not a Contribution", e)
                null
            }
        }
    }
}
