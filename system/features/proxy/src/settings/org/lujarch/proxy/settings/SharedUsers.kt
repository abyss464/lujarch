/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.proxy.settings

import android.content.pm.PackageManager
import android.util.Log

/**
 * Apps that share a uid (android:sharedUserId), read from PackageManager.
 * Android tells traffic apart by uid only, so the apps of one uid have one
 * route. The proxy core names a uid of one package by the package and a uid
 * of several by the shared user, and a PROCESS-NAME rule on the shared user
 * matches the uid either way (mihomo patch 0002). Per-app routing therefore
 * saves the rule of such an app under its shared user: it keeps matching
 * however many of the uid's packages are installed, also those in
 * packages.xml that this user does not see. A rule on the package itself
 * (saved before) matches only while it is the uid's only package. TUN list
 * entries are uids in the core (sing-tun), so an entry on any package of the
 * uid, or on the shared user, applies to all of them.
 */
class SharedUsers private constructor(
    /** Package -> its shared user. */
    private val userOf: Map<String, String>,
    /** Shared user -> its installed packages, sorted. */
    private val packagesOf: Map<String, List<String>>,
) {
    fun sharedUser(packageName: String): String? = userOf[packageName]

    /** The installed packages that share [packageName]'s uid, itself included; empty without a shared user. */
    fun members(packageName: String): List<String> = userOf[packageName]?.let { packagesOf[it] }.orEmpty()

    /** The installed packages of [sharedUser]; empty for a name that is no shared user here. */
    fun packagesOf(sharedUser: String): List<String> = packagesOf[sharedUser].orEmpty()

    /** The name [packageName]'s rule is saved under: its shared user, or the package. */
    fun ruleKey(packageName: String): String = userOf[packageName] ?: packageName

    companion object {
        val NONE = SharedUsers(emptyMap(), emptyMap())

        fun of(userOf: Map<String, String>): SharedUsers {
            val packagesOf = HashMap<String, MutableList<String>>()
            userOf.forEach { (pkg, user) -> packagesOf.getOrPut(user) { ArrayList() }.add(pkg) }
            return SharedUsers(HashMap(userOf), packagesOf.mapValues { it.value.sorted() })
        }

        /** The installed packages' shared users (one PackageManager call; call off the main thread). */
        fun load(pm: PackageManager): SharedUsers = try {
            val userOf = HashMap<String, String>()
            for (info in pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))) {
                // Deprecated because new apps may not declare a shared user; the apps
                // that declare one keep it, and it is the name packages.xml and the
                // core use for their uid.
                @Suppress("DEPRECATION")
                val user = info.sharedUserId ?: continue
                userOf[info.packageName] = user
            }
            of(userOf)
        } catch (e: RuntimeException) {
            // Without the list every app is its own: routes are saved per package,
            // which the core matches for a shared uid of one package.
            Log.e(TAG, "shared users: reading the installed packages failed: ${e.message}", e)
            NONE
        }
    }
}
