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

package org.lujarch.linux.terminal

import android.app.Activity
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Linux on screen (LNX-6): the desktop full screen without Android's bars, the status bar locked,
 * and the Mode Linux on, which lets calls, repeat callers, alarms and media through and holds
 * other notifications. The Mode is an automatic rule of this app in Settings > Modes, which the
 * owner can change there; it is on only while the desktop is on screen.
 */
object LinuxMode {
    private val CONDITION: Uri = Uri.parse("condition://org.lujarch.linux.terminal/linux")

    /** The desktop is on screen: the Mode is on. */
    @Volatile private var active = false

    fun enter(activity: Activity) {
        activity.window.insetsController?.let {
            // The default behavior: a swipe from the bottom goes home at once.
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
            it.hide(WindowInsets.Type.systemBars())
        }
        lockStatusBar(activity, true)
        active = true
        setActive(activity, true)
    }

    fun leave(activity: Activity) {
        lockStatusBar(activity, false)
        active = false
        setActive(activity, false)
    }

    /** Turns the Mode on again while the desktop is on screen: turning Do Not Disturb off snoozed it. */
    fun renew(context: Context) {
        if (!active) return
        setActive(context, false)
        setActive(context, true)
    }

    private fun lockStatusBar(context: Context, locked: Boolean) {
        try {
            context.getSystemService(StatusBarManager::class.java)
                ?.disable(if (locked) StatusBarManager.DISABLE_EXPAND else StatusBarManager.DISABLE_NONE)
        } catch (e: SecurityException) {
            Log.e(TAG, "the status bar cannot be locked: ${e.message}")
        }
    }

    private fun setActive(context: Context, active: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            val id = ruleId(context, nm, create = active) ?: return
            nm.setAutomaticZenRuleState(
                id,
                Condition(
                    CONDITION, context.getString(R.string.mode_summary),
                    if (active) Condition.STATE_TRUE else Condition.STATE_FALSE,
                    Condition.SOURCE_CONTEXT,
                ),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "the Mode Linux: ${e.message}")
        }
    }

    private fun ruleId(context: Context, nm: NotificationManager, create: Boolean): String? {
        nm.automaticZenRules.entries.firstOrNull { it.value.conditionId == CONDITION }?.let { return it.key }
        if (!create) return null
        val policy = ZenPolicy.Builder()
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
            .allowAlarms(true)
            .allowMedia(true)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowEvents(false)
            .allowReminders(false)
            .allowSystem(false)
            .build()
        val rule = AutomaticZenRule.Builder(context.getString(R.string.mode_name), CONDITION)
            .setType(AutomaticZenRule.TYPE_IMMERSIVE)
            .setConfigurationActivity(ComponentName(context, ModesActivity::class.java))
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(policy)
            .setTriggerDescription(context.getString(R.string.mode_trigger))
            .setManualInvocationAllowed(false)
            .build()
        Log.i(TAG, "the Mode Linux is made")
        return nm.addAutomaticZenRule(rule)
    }
}
