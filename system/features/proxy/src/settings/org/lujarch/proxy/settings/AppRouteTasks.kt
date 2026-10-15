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

import android.app.ActivityThread
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the user chose for an app on the per-app routing page. */
sealed interface RouteChoice {
    /** Remove the app's own rule: the routing rules decide. */
    data object FollowRules : RouteChoice

    /** DIRECT, REJECT or a proxy group target in manager syntax. */
    data class Target(val target: String) : RouteChoice

    /** Keep the app out of the TUN (`app tun exclude`). */
    data object Bypass : RouteChoice

    /**
     * Put the app on the TUN include list (`app tun include`) or take it off
     * (`app tun clear`). While the list has apps, only they use the proxy.
     */
    data class Include(val on: Boolean) : RouteChoice
}

/**
 * Per-app route changes: saves the choice with mihomo-manager (`app add`,
 * `app remove`, `app tun ...`) and applies it to the running core (`build`:
 * generate, validate, install, reload, health check, rollback on failure).
 * Runs in the process scope, so leaving the page does not abort it; pages
 * observe [run] and [routing]. All of this page's manager commands take
 * [lock], and a command refused as busy (another page's manager task) is
 * retried for a while.
 */
object AppRouteTasks {
    enum class Step {
        /** Saving the route in the manager's rules. */
        SAVING,
        /** Rebuilding the config and reloading the core. */
        APPLYING,
        DONE,
        FAILED,
    }

    data class Run(
        val packageName: String,
        val label: String,
        /** null for a rebuild of saved changes. */
        val choice: RouteChoice?,
        val step: Step,
        /** Commands of the saving step and how many have finished. */
        val commands: Int = 0,
        val finishedCommands: Int = 0,
        /** The step that failed. */
        val failedStep: Step? = null,
        val error: Throwable? = null,
        val finishedAt: Long = 0,
    ) {
        val running: Boolean
            get() = step == Step.SAVING || step == Step.APPLYING

        /** Output of the failed manager command. */
        val output: String?
            get() = (error as? ManagerException)?.output
    }

    private val BUILD = listOf("build")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val runState = MutableStateFlow<Run?>(null)
    private val routingState = MutableStateFlow<AppRouting?>(null)
    private val pendingState = MutableStateFlow(false)

    /** The latest change, running or finished. */
    val run: StateFlow<Run?> = runState

    /** The latest per-app routing read from the manager. */
    val routing: StateFlow<AppRouting?> = routingState

    /** Saved changes the running core does not have yet: the last rebuild failed. */
    val pendingBuild: StateFlow<Boolean> = pendingState

    fun running(): Boolean = runState.value?.running == true

    /** Reads the saved per-app routing and publishes it in [routing]. */
    suspend fun load(): AppRouting = lock.withLock { loadLocked() }

    suspend fun targets(): List<RouteTarget> = lock.withLock {
        AppRoutingClient.parseTargets(runManager(AppRoutingClient.TARGETS).output)
    }

    /**
     * Saves [choice] for [packageName] and applies it. Returns false when a
     * change is already running.
     */
    fun apply(packageName: String, label: String, choice: RouteChoice): Boolean {
        if (running()) return false
        runState.value = Run(packageName, label, choice, Step.SAVING)
        scope.launch {
            lock.withLock {
                var current = runState.value ?: return@withLock
                try {
                    val commands = commandsFor(packageName, choice, loadLocked())
                    current = current.copy(commands = commands.size)
                    runState.value = current
                    for (args in commands) {
                        runManager(args)
                        // The saved rules now differ from what the core runs.
                        pendingState.value = true
                        current = current.copy(finishedCommands = current.finishedCommands + 1)
                        runState.value = current
                    }
                    if (pendingState.value) {
                        current = current.copy(step = Step.APPLYING)
                        runState.value = current
                        runManager(BUILD)
                        pendingState.value = false
                    }
                    current = current.copy(step = Step.DONE, finishedAt = System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "per-app route of $packageName: ${current.step.name.lowercase()} failed: ${e.message}", e)
                    current = current.copy(
                        step = Step.FAILED,
                        failedStep = current.step,
                        error = e,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                runState.value = current
                reloadAfterChange()
            }
        }
        return true
    }

    /** Rebuilds the config after a failed rebuild. Returns false when a change is already running. */
    fun rebuild(packageName: String, label: String): Boolean {
        if (running()) return false
        runState.value = Run(packageName, label, null, Step.APPLYING)
        scope.launch {
            lock.withLock {
                var current = runState.value ?: return@withLock
                try {
                    runManager(BUILD)
                    pendingState.value = false
                    current = current.copy(step = Step.DONE, finishedAt = System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "per-app routing: rebuild failed: ${e.message}", e)
                    current = current.copy(
                        step = Step.FAILED,
                        failedStep = Step.APPLYING,
                        error = e,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
                runState.value = current
                reloadAfterChange()
            }
        }
        return true
    }

    /**
     * The manager commands that turn the saved state into [choice]. A route
     * is either a rule or a TUN exclude entry, never both; an app on the TUN
     * include list stays on it unless it bypasses the TUN. An app that shares
     * its uid ([SharedUsers]) gets its rule under the shared user, which
     * replaces a rule saved for the package itself, and every exclude entry
     * of the uid counts as its own.
     */
    fun commandsFor(packageName: String, choice: RouteChoice, state: AppRouting): List<List<String>> {
        val key = state.ruleKey(packageName)
        // Names with saved rules for the app: the package, its shared user.
        val ruled = state.rulesOf(packageName).map { it.app }.distinct()
        val names = state.namesOf(packageName)
        val excluded = state.tunExclude.filter { it in names }
        val included = packageName in state.tunInclude
        // `app remove` also takes the name off the TUN lists: the include entry goes back.
        fun removeRules(names: List<String>): List<List<String>> = buildList {
            names.forEach { add(listOf("app", "remove", it)) }
            if (included && packageName in names) add(listOf("app", "tun", "include", packageName))
        }
        return when (choice) {
            RouteChoice.FollowRules -> buildList {
                addAll(removeRules(ruled))
                excluded.filter { it !in ruled }.forEach { add(listOf("app", "tun", "clear", it)) }
            }
            is RouteChoice.Target -> buildList {
                excluded.forEach { add(listOf("app", "tun", "clear", it)) }
                val other = ruled.filter { it != key }
                addAll(removeRules(other))
                val current = state.route(packageName)
                val same = other.isEmpty() && current is AppRoute.OwnRule && current.rule.target == choice.target &&
                    state.rulesOf(packageName).size == 1
                if (!same) add(listOf("app", "add", key, choice.target))
            }
            RouteChoice.Bypass -> buildList {
                ruled.forEach { add(listOf("app", "remove", it)) }
                if (packageName !in excluded || packageName in ruled) add(listOf("app", "tun", "exclude", packageName))
            }
            // include also takes the app off the exclude list; clear takes it off both.
            is RouteChoice.Include -> buildList {
                if (choice.on && !included) add(listOf("app", "tun", "include", packageName))
                if (!choice.on && included) add(listOf("app", "tun", "clear", packageName))
            }
        }
    }

    private suspend fun loadLocked(): AppRouting {
        val saved = AppRoutingClient.parseList(runManager(AppRoutingClient.LIST).output)
        val routing = saved.withSharedUsers(sharedUsers())
        routingState.value = routing
        return routing
    }

    /** The installed apps' shared users, read at every load (apps come and go). */
    private suspend fun sharedUsers(): SharedUsers {
        val pm = ActivityThread.currentApplication()?.packageManager ?: return SharedUsers.NONE
        return withContext(Dispatchers.IO) { SharedUsers.load(pm) }
    }

    /** Publishes the saved state after a change; a failure here leaves the last one. */
    private suspend fun reloadAfterChange() {
        try {
            loadLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "per-app routing: reading the rules after a change failed: ${e.message}")
        }
    }

    /**
     * One manager command; waits (up to 30 s) while another page's manager
     * command holds the proxy daemon ([ManagerCalls]).
     */
    private suspend fun runManager(args: List<String>): ManagerResult = AppRoutingClient.run(args)
}
