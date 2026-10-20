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

package org.lujarch.installer.sheet

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.pm.parsing.ApkLite
import android.content.pm.parsing.ApkLiteParseUtils
import android.content.pm.parsing.result.ParseTypeImpl
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.lujarch.installer.TAG

/** A file the installer was given, as the app that gave it names it. */
data class SourceFile(val uri: Uri, val name: String, val size: Long)

enum class PartKind { BASE, FEATURE, LANGUAGE, DENSITY, ABI, OTHER }

/** One APK of an app: its base or one of its splits. */
data class Part(
    val splitName: String?,
    val kind: PartKind,
    val file: File,
    val size: Long,
    /** The language, density or processor type it is for, for showing. */
    val detail: String?,
    /** Whether it fits this phone: chosen unless the owner leaves it out (INS-2). */
    val fits: Boolean,
    /** The feature split a configuration split belongs to. */
    val configFor: String?,
) {
    val key: String
        get() = splitName ?: ""

    val shownName: String
        get() = splitName ?: "base.apk"
}

/** An expansion file of a bundle, copied into Android/obb/<package>/ (INS-2). */
data class Obb(val name: String, val file: File, val size: Long)

data class Permission(val name: String, val label: String?, val consent: Boolean, val new: Boolean)

enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

data class Exported(val name: String, val kind: ComponentKind, val guard: String?)

data class Components(
    val activities: Int,
    val services: Int,
    val receivers: Int,
    val providers: Int,
    val exported: List<Exported>,
) {
    fun count(kind: ComponentKind) =
        when (kind) {
            ComponentKind.ACTIVITY -> activities
            ComponentKind.SERVICE -> services
            ComponentKind.RECEIVER -> receivers
            ComponentKind.PROVIDER -> providers
        }
}

/** What is installed of an app now. */
data class Installed(
    val versionName: String?,
    val versionCode: Long,
    val permissions: Set<String>,
    val certificates: List<String>,
    val system: Boolean,
)

enum class InstallKind { NEW, UPDATE, DOWNGRADE, SAME }

/** One app read from the files: everything INS-1 shows of it. */
data class AppFile(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val installed: Installed?,
    val parts: List<Part>,
    val obbs: List<Obb>,
    val abis: List<String>,
    val debuggable: Boolean,
    val testOnly: Boolean,
    /** SHA-256 of each current signing certificate, as colon-separated hex. */
    val certificates: List<String>,
    val schemes: List<Scheme>,
    /** Null for a new app. */
    val signatureMatches: Boolean?,
    val permissions: List<Permission>,
    val components: Components,
    val sources: List<SourceFile>,
) {
    val kind: InstallKind
        get() =
            when {
                installed == null -> InstallKind.NEW
                versionCode > installed.versionCode -> InstallKind.UPDATE
                versionCode < installed.versionCode -> InstallKind.DOWNGRADE
                else -> InstallKind.SAME
            }

    val isBundle: Boolean
        get() = parts.size > 1 || obbs.isNotEmpty()

    /** The parts chosen by default (INS-2). */
    fun defaultParts(): Set<String> = parts.filter { it.fits }.map { it.key }.toSet()

    /** [chosen] with the base, and without the configurations of features left out. */
    fun partsToInstall(chosen: Set<String>): List<Part> {
        val features =
            parts.filter { it.kind == PartKind.FEATURE && it.key in chosen }.map { it.key }.toSet()
        return parts.filter {
            it.kind == PartKind.BASE ||
                (it.key in chosen && (it.configFor == null || it.configFor in features))
        }
    }
}

/** A file the installer could not read, and why. */
data class Unreadable(val name: String, val reason: String)

data class Reading(val apps: List<AppFile>, val unreadable: List<Unreadable>)

/** The signature schemes, with the ids of their blocks in the APK Signing Block. */
enum class Scheme(val shown: String) { V1("v1"), V2("v2"), V3("v3"), V31("v3.1"), V4("v4") }

/** Reads files given to the installer into the apps they hold (INS-1, INS-2, INS-4). */
class Reader(private val context: Context, private val work: File) {
    private val pm = context.packageManager

    /** Reads [sources]; [onProgress] gets the bytes copied so far and in all. */
    fun read(sources: List<SourceFile>, onProgress: (Long, Long) -> Unit): Reading {
        val unreadable = ArrayList<Unreadable>()
        val apks = ArrayList<Pair<SourceFile, ApkFile>>()
        val obbs = HashMap<String, MutableList<Obb>>()
        val total = sources.sumOf { it.size.coerceAtLeast(0) }
        var copied = 0L
        sources.forEachIndexed { i, source ->
            val dir = File(work, "$i").apply { mkdirs() }
            val file = File(dir, "file")
            try {
                (context.contentResolver.openInputStream(source.uri)
                        ?: throw IOException("no stream"))
                    .use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                onProgress(copied, total)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "${source.name} not opened", e)
                unreadable += Unreadable(source.name, context.getString(R.string.ins_unreadable_open, e.message ?: e.javaClass.simpleName))
                return@forEachIndexed
            }
            try {
                unpack(source, file, dir, apks, obbs, unreadable)
            } catch (e: IOException) {
                Log.e(TAG, "${source.name} not read", e)
                unreadable += Unreadable(source.name, context.getString(R.string.ins_unreadable_not_zip))
            }
        }
        val apps = ArrayList<AppFile>()
        for ((pkg, group) in apks.groupBy { it.second.lite.packageName }) {
            val base = group.firstOrNull { it.second.lite.splitName == null }
            if (base == null) {
                unreadable += Unreadable(group.first().first.name, context.getString(R.string.ins_unreadable_no_base, pkg))
                continue
            }
            try {
                apps += app(pkg, base.second, group.map { it.second }, obbs[pkg].orEmpty(), group.map { it.first }.distinct())
            } catch (e: Exception) {
                Log.e(TAG, "$pkg not read", e)
                unreadable += Unreadable(base.first.name, context.getString(R.string.ins_unreadable_parse, e.message ?: e.javaClass.simpleName))
            }
        }
        return Reading(apps, unreadable)
    }

    private class ApkFile(val file: File, val lite: ApkLite, val abis: Set<String>, val schemes: List<Scheme>)

    private fun unpack(
        source: SourceFile,
        file: File,
        dir: File,
        apks: MutableList<Pair<SourceFile, ApkFile>>,
        obbs: MutableMap<String, MutableList<Obb>>,
        unreadable: MutableList<Unreadable>,
    ) {
        val zip =
            try {
                ZipFile(file)
            } catch (e: ZipException) {
                Log.e(TAG, "${source.name} is not a ZIP file", e)
                unreadable += Unreadable(source.name, context.getString(R.string.ins_unreadable_not_zip))
                return
            }
        zip.use {
            if (zip.getEntry("AndroidManifest.xml") != null) {
                val apk = File(dir, "0.apk")
                file.renameTo(apk)
                parse(source.name, apk, null, unreadable)?.let { apks += source to it }
                return
            }
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val apkEntries =
                entries.filter { it.name.endsWith(".apk", ignoreCase = true) }.let { all ->
                    val splits = all.filter { it.name.startsWith("splits/") }
                    if (splits.isNotEmpty()) splits else all.filter { !it.name.startsWith("standalones/") }
                }
            if (apkEntries.isEmpty()) {
                unreadable += Unreadable(source.name, context.getString(R.string.ins_unreadable_no_apk))
                return
            }
            val names = entries.map { it.name }.toSet()
            apkEntries.forEachIndexed { n, entry ->
                val apk = File(dir, "$n.apk")
                zip.getInputStream(entry).use { input -> apk.outputStream().use { input.copyTo(it) } }
                parse("${source.name}: ${entry.name}", apk, entry.name + ".idsig" in names, unreadable)
                    ?.let { apks += source to it }
            }
            for (entry in entries) {
                val m = OBB_RE.matchEntire(entry.name) ?: continue
                val out = File(dir, "obb-" + m.groupValues[2])
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                obbs.getOrPut(m.groupValues[1]) { ArrayList() } += Obb(m.groupValues[2], out, out.length())
            }
        }
    }

    private fun parse(shown: String, apk: File, v4: Boolean?, unreadable: MutableList<Unreadable>): ApkFile? {
        val result = ApkLiteParseUtils.parseApkLite(ParseTypeImpl.forDefaultParsing().reset(), apk, 0)
        if (result.isError) {
            Log.e(TAG, "$shown not parsed: ${result.errorMessage}", result.exception)
            unreadable += Unreadable(shown, context.getString(R.string.ins_unreadable_parse, result.errorMessage ?: ""))
            return null
        }
        val abis = HashSet<String>()
        val schemes = ArrayList<Scheme>()
        ZipFile(apk).use { zip ->
            for (e in zip.entries()) {
                val m = LIB_RE.matchEntire(e.name) ?: continue
                abis += m.groupValues[1]
            }
            if (zip.entries().asSequence().any { SF_RE.matches(it.name) }) schemes += Scheme.V1
        }
        schemes += signingBlockSchemes(apk)
        if (v4 == true || File(apk.path + ".idsig").exists()) schemes += Scheme.V4
        return ApkFile(apk, result.result, abis, schemes)
    }

    private fun app(pkg: String, base: ApkFile, all: List<ApkFile>, obbs: List<Obb>, sources: List<SourceFile>): AppFile {
        val flags =
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES or
                PackageManager.MATCH_DISABLED_COMPONENTS
        val info: PackageInfo =
            pm.getPackageArchiveInfo(base.file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
                ?: throw IOException(context.getString(R.string.ins_unreadable_manifest))
        val ai = info.applicationInfo!!
        ai.sourceDir = base.file.path
        ai.publicSourceDir = base.file.path
        val installed = installed(pkg)
        val certificates = certificates(info)
        val parts = all.map { part(it) }.sortedWith(compareBy({ it.kind.ordinal }, { it.splitName ?: "" }))
        val chosenParts = choose(parts)
        return AppFile(
            packageName = pkg,
            label = ai.loadLabel(pm).toString(),
            icon = try { ai.loadIcon(pm) } catch (e: Exception) { Log.e(TAG, "$pkg: no icon", e); null },
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = base.lite.minSdkVersion,
            targetSdk = base.lite.targetSdkVersion,
            installed = installed,
            parts = chosenParts,
            obbs = obbs,
            abis = all.flatMap { it.abis }.toSortedSet(compareBy { abiOrder(it) }).toList(),
            debuggable = ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            testOnly = ai.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            certificates = certificates,
            schemes = base.schemes.distinct().sortedBy { it.ordinal },
            signatureMatches = installed?.let { signatureMatches(it, info) },
            permissions = permissions(info, installed),
            components = components(info),
            sources = sources,
        )
    }

    private fun installed(pkg: String): Installed? =
        try {
            val i =
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES).toLong()
                    ),
                )
            Installed(
                i.versionName,
                i.longVersionCode,
                i.requestedPermissions.orEmpty().toSet(),
                certificates(i),
                i.applicationInfo!!.isSystemApp,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun certificates(info: PackageInfo): List<String> {
        val si = info.signingInfo ?: return emptyList()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        return certs.orEmpty().map { sha256(it.toByteArray()) }
    }

    private fun signatureMatches(installed: Installed, info: PackageInfo): Boolean {
        val si = info.signingInfo ?: return false
        if (installed.certificates.toSet() == certificates(info).toSet()) return true
        if (si.hasMultipleSigners()) return false
        val history = si.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }
        return installed.certificates.size == 1 && installed.certificates[0] in history
    }

    private fun part(apk: ApkFile): Part {
        val split = apk.lite.splitName
        val size = apk.file.length()
        if (split == null) return Part(null, PartKind.BASE, apk.file, size, null, true, null)
        if (apk.lite.isFeatureSplit) return Part(split, PartKind.FEATURE, apk.file, size, null, true, null)
        val config = split.substringAfterLast("config.", "")
        val configFor = apk.lite.configForSplit?.takeIf { it.isNotEmpty() }
        if (config.isEmpty()) return Part(split, PartKind.OTHER, apk.file, size, null, true, configFor)
        ABI_SPLITS[config]?.let { abi -> return Part(split, PartKind.ABI, apk.file, size, abi, false, configFor) }
        DENSITIES[config]?.let { return Part(split, PartKind.DENSITY, apk.file, size, config, false, configFor) }
        if (LANGUAGE_RE.matches(config)) {
            val locale = Locale.forLanguageTag(config.replace('_', '-'))
            val shown = locale.getDisplayName(Locale.getDefault()).takeIf { it.isNotEmpty() } ?: config
            return Part(split, PartKind.LANGUAGE, apk.file, size, shown, false, configFor)
        }
        return Part(split, PartKind.OTHER, apk.file, size, null, true, configFor)
    }

    /** Marks the language, density and processor parts that fit this phone. */
    private fun choose(parts: List<Part>): List<Part> {
        val languages = LocaleList.getDefault().let { l -> (0 until l.size()).map { l[it].language } }.toSet()
        val dpi = context.resources.displayMetrics.densityDpi
        val densities = parts.filter { it.kind == PartKind.DENSITY }.map { DENSITIES[it.splitName!!.substringAfterLast("config.")]!! }
        val density = densities.filter { it >= dpi }.minOrNull() ?: densities.maxOrNull()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { a -> parts.any { it.kind == PartKind.ABI && it.detail == a } }
        return parts.map { p ->
            when (p.kind) {
                PartKind.LANGUAGE ->
                    p.copy(fits = Locale.forLanguageTag(p.splitName!!.substringAfterLast("config.").replace('_', '-')).language in languages)
                PartKind.DENSITY -> p.copy(fits = DENSITIES[p.splitName!!.substringAfterLast("config.")] == density)
                PartKind.ABI -> p.copy(fits = p.detail == abi)
                else -> p
            }
        }
    }

    private fun permissions(info: PackageInfo, installed: Installed?): List<Permission> {
        val own = info.permissions.orEmpty().associateBy { it.name }
        return info.requestedPermissions.orEmpty().distinct().map { name ->
            val p: PermissionInfo? =
                try {
                    pm.getPermissionInfo(name, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    own[name]
                }
            val consent =
                p != null &&
                    (p.protection == PermissionInfo.PROTECTION_DANGEROUS ||
                        p.protectionFlags and PermissionInfo.PROTECTION_FLAG_APPOP != 0)
            val label = p?.loadLabel(pm)?.toString()?.takeIf { it != name && it.isNotBlank() }
            Permission(name, label?.replaceFirstChar { it.titlecase(Locale.getDefault()) }, consent, installed != null && name !in installed.permissions)
        }.sortedWith(compareBy({ !it.new }, { !it.consent }, { it.label ?: it.name }))
    }

    private fun components(info: PackageInfo): Components {
        val exported = ArrayList<Exported>()
        fun add(list: Array<out ComponentInfo>?, kind: ComponentKind, guard: (ComponentInfo) -> String?) {
            for (c in list.orEmpty()) if (c.exported) exported += Exported(shortName(info.packageName, c.name), kind, guard(c))
        }
        add(info.activities, ComponentKind.ACTIVITY) { (it as ActivityInfo).permission }
        add(info.services, ComponentKind.SERVICE) { (it as ServiceInfo).permission }
        add(info.receivers, ComponentKind.RECEIVER) { (it as ActivityInfo).permission }
        add(info.providers, ComponentKind.PROVIDER) { (it as ProviderInfo).let { p -> p.readPermission ?: p.writePermission } }
        return Components(
            info.activities?.size ?: 0,
            info.services?.size ?: 0,
            info.receivers?.size ?: 0,
            info.providers?.size ?: 0,
            exported,
        )
    }

    companion object {
        private val OBB_RE = Regex("""Android/obb/([^/]+)/([^/]+\.obb)""", RegexOption.IGNORE_CASE)
        private val LIB_RE = Regex("""lib/([^/]+)/[^/]+\.so""")
        private val SF_RE = Regex("""META-INF/[^/]+\.SF""", RegexOption.IGNORE_CASE)
        private val LANGUAGE_RE = Regex("""[a-z]{2,3}(_[A-Za-z0-9]{2,8})*""")
        private val ABI_SPLITS =
            mapOf(
                "arm64_v8a" to "arm64-v8a",
                "armeabi_v7a" to "armeabi-v7a",
                "armeabi" to "armeabi",
                "x86" to "x86",
                "x86_64" to "x86_64",
                "riscv64" to "riscv64",
                "mips" to "mips",
                "mips64" to "mips64",
            )
        private val DENSITIES =
            mapOf(
                "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
                "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
            )

        private fun abiOrder(abi: String) =
            Build.SUPPORTED_ABIS.indexOf(abi).let { if (it < 0) 100 + abi.hashCode().and(0xff) else it }

        private fun shortName(pkg: String, name: String) =
            if (name.startsWith("$pkg.")) name.substring(pkg.length) else name

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

        private const val EOCD = 0x06054b50
        private const val V2_ID = 0x7109871a
        private const val V3_ID = 0xf05368c0.toInt()
        private const val V31_ID = 0x1b93ad61

        /** The schemes whose blocks the APK Signing Block before the central directory holds. */
        fun signingBlockSchemes(apk: File): List<Scheme> {
            RandomAccessFile(apk, "r").use { f ->
                val len = f.length()
                val tail = minOf(len, 65_557L).toInt()
                val buf = ByteArray(tail)
                f.seek(len - tail)
                f.readFully(buf)
                var eocd = -1
                for (i in tail - 22 downTo 0) {
                    if (le32(buf, i) == EOCD) { eocd = i; break }
                }
                if (eocd < 0) return emptyList()
                val cd = le32(buf, eocd + 16).toLong() and 0xffffffffL
                if (cd < 32) return emptyList()
                val footer = ByteArray(24)
                f.seek(cd - 24)
                f.readFully(footer)
                if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return emptyList()
                val size = le64(footer, 0)
                val start = cd - size - 8
                if (start < 0) return emptyList()
                val out = ArrayList<Scheme>()
                var pos = start + 8
                val end = cd - 24
                val head = ByteArray(12)
                while (pos + 12 <= end) {
                    f.seek(pos)
                    f.readFully(head)
                    val pairLen = le64(head, 0)
                    when (le32(head, 8)) {
                        V2_ID -> out += Scheme.V2
                        V3_ID -> out += Scheme.V3
                        V31_ID -> out += Scheme.V31
                    }
                    if (pairLen < 4) break
                    pos += 8 + pairLen
                }
                return out
            }
        }

        private fun le32(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
                ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

        private fun le64(b: ByteArray, i: Int) =
            (le32(b, i).toLong() and 0xffffffffL) or (le32(b, i + 4).toLong() shl 32)
    }
}
