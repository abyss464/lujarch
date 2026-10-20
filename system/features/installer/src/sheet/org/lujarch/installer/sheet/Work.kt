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
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lujarch.installer.InstallOptions
import org.lujarch.installer.TAG

/** Where one app of an install is. */
sealed interface AppStatus {
    data object Waiting : AppStatus

    data class Working(val step: Step, val progress: Float) : AppStatus

    data class Done(val notes: List<String>) : AppStatus

    data class Failed(val failure: Outcome.Failed) : AppStatus

    data object Stopped : AppStatus

    data object Skipped : AppStatus
}

/** Why the files cannot be installed from where they came from (INS-1, PERM-12). */
sealed interface Gate {
    /** The source app is not allowed Install unknown apps; the owner can allow it. */
    data class UnknownSource(val label: String, val packageName: String) : Gate

    /** The source app does not ask Android for Install unknown apps. */
    data class NoRequest(val label: String) : Gate

    /** This user may not install apps, or not from unknown sources. */
    data object Blocked : Gate
}

/**
 * One opening of the installer: the files it was given, the apps read from them, the owner's
 * choices and each app's install. It outlives the sheet, so that an install continues in the
 * background (INS-4).
 */
class Work(
    private val context: Context,
    val id: String,
    val sources: List<SourceFile>,
    val origin: Origin,
    val returnResult: Boolean,
    /** Another app's session that waits for the owner; it installs as that app staged it. */
    val session: Int? = null,
) {
    var gate by mutableStateOf<Gate?>(null)
    var readDone by mutableStateOf(0L)
    var readTotal by mutableStateOf(0L)
    var reading by mutableStateOf<Reading?>(null)
        private set
    var options by mutableStateOf(InstallOptions.read(context))
    /** The parts chosen of each app (INS-2) and whether each app is to be installed (INS-4). */
    val parts = mutableStateMapOf<String, Set<String>>()
    val chosen = mutableStateMapOf<String, Boolean>()
    val status = mutableStateMapOf<String, AppStatus>()
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    /** The sheet is closed and the install goes on with its notification. */
    var inBackground by mutableStateOf(false)
    /** Android's result of the last install, for a caller that asked for it. */
    var legacyResult = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.cacheDir, "install/$id")
    private var job: Job? = null
    private var stopping = false

    val apps: List<AppFile>
        get() = reading?.apps.orEmpty()

    fun app(packageName: String): AppFile? = apps.firstOrNull { it.packageName == packageName }

    fun read() {
        scope.launch {
            val r =
                withContext(Dispatchers.IO) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    var shown = 0L
                    Reader(context, dir).read(sources) { done, total ->
                        if (done - shown >= total / 200 || done == total) {
                            shown = done
                            main.post {
                                readDone = done
                                readTotal = total
                            }
                        }
                    }
                }
            for (app in r.apps) {
                parts[app.packageName] = app.defaultParts()
                chosen[app.packageName] = true
            }
            r.unreadable.forEach { Log.e(TAG, "unreadable: ${it.name}: ${it.reason}") }
            reading = r
        }
    }

    /** The size of [app] with the parts chosen. */
    fun size(app: AppFile): Long =
        app.partsToInstall(parts[app.packageName].orEmpty()).sumOf { it.size } + app.obbs.sumOf { it.size }

    fun install() {
        if (running) return
        running = true
        finished = false
        stopping = false
        val todo = apps.filter { chosen[it.packageName] == true }
        for (app in apps) status[app.packageName] = if (app in todo) AppStatus.Waiting else AppStatus.Skipped
        InstallService.follow(context, id)
        job =
            scope.launch {
                for (app in todo) {
                    if (stopping) {
                        status[app.packageName] = AppStatus.Stopped
                        continue
                    }
                    installOne(app)
                }
                finish()
            }
    }

    /** Uninstalls the installed app whose signature differs, then installs [app] (INS-5). */
    fun uninstallAndInstall(app: AppFile) {
        if (running) return
        running = true
        finished = false
        InstallService.follow(context, id)
        job =
            scope.launch {
                status[app.packageName] = AppStatus.Working(Step.Uninstalling, 0f)
                when (val out = Installer(context).uninstall(app.packageName, 0)) {
                    is Outcome.Failed -> status[app.packageName] = AppStatus.Failed(out)
                    is Outcome.Installed -> installOne(app.copy(installed = null, signatureMatches = null))
                }
                finish()
            }
    }

    private suspend fun installOne(app: AppFile) {
        val pkg = app.packageName
        status[pkg] = AppStatus.Working(if (session != null) Step.Installing else Step.Copying(0, size(app)), 0f)
        var shown = -1f
        val onStep = { step: Step, p: Float ->
            if (p - shown >= 0.005f || step !is Step.Copying) {
                shown = p
                main.post { if (status[pkg] is AppStatus.Working) status[pkg] = AppStatus.Working(step, p) }
            }
        }
        val outcome =
            if (session != null) Installer(context).confirm(session, onStep)
            else Installer(context).install(app, app.partsToInstall(parts[pkg].orEmpty()), options, origin, onStep)
        when (outcome) {
            is Outcome.Installed -> {
                legacyResult = android.content.pm.PackageManager.INSTALL_SUCCEEDED
                val notes = ArrayList(outcome.notes)
                if (options.deleteFile && session == null) {
                    val left = withContext(Dispatchers.IO) { Installer(context).deleteSources(app.sources) }
                    left.forEach { notes += context.getString(R.string.ins_note_not_deleted, it) }
                }
                status[pkg] = AppStatus.Done(notes)
            }
            is Outcome.Failed -> {
                legacyResult = outcome.legacy
                withContext(Dispatchers.IO) {
                    HistoryStore.get(context).recordFailure(app, context.packageName, outcome.legacy, outcome.message)
                }
                status[pkg] = AppStatus.Failed(outcome)
            }
        }
    }

    private fun finish() {
        running = false
        finished = true
        job = null
    }

    /** Stop the rest: the app being installed finishes, those waiting do not start (INS-4). */
    fun stopRest() {
        stopping = true
    }

    /** Opens what the owner chose for after installing; false when the app has nothing to open. */
    fun openAfter(packageName: String, what: Int): Boolean {
        val intent =
            when (what) {
                InstallOptions.AFTER_OPEN -> context.packageManager.getLaunchIntentForPackage(packageName)
                InstallOptions.AFTER_INFO ->
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                else -> null
            } ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(TAG, "$packageName: after installing not opened", e)
            false
        }
    }

    /**
     * The sheet is gone: a running install goes on in the background, kept with its result for
     * its notification; otherwise this work is forgotten.
     */
    fun close() {
        if (running) {
            inBackground = true
            return
        }
        // The app that staged the session hears that the owner did not install it.
        if (session != null && !finished) Installer(context).decline(session)
        job?.cancel()
        works.remove(id)
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val works = java.util.concurrent.ConcurrentHashMap<String, Work>()
        private val started = System.currentTimeMillis()

        fun create(context: Context, sources: List<SourceFile>, origin: Origin, returnResult: Boolean, session: Int? = null): Work {
            val app = context.applicationContext
            val work = Work(app, UUID.randomUUID().toString(), sources, origin, returnResult, session)
            works[work.id] = work
            // The files of works gone with an earlier process.
            scope.launch(Dispatchers.IO) {
                File(app.cacheDir, "install").listFiles()
                    ?.filter { it.name !in works.keys && it.lastModified() < started }
                    ?.forEach { it.deleteRecursively() }
            }
            return work
        }

        fun find(id: String?): Work? = id?.let { works[it] }
    }
}
