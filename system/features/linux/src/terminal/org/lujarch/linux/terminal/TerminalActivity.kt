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

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.window.OnBackInvokedDispatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lujarch.linux.ILinux
import org.lujarch.linux.Status

/**
 * The Terminal app (LNX-3): up to 16 sessions as tabs, each a shell of the owner's user, of root
 * in Arch Linux or of root in Android. A session goes on while the app is closed; it shows
 * whether a command runs, can be renamed and ended, and keeps the phone awake only when the owner
 * chooses so. The colors and the keys row above the keyboard are the owner's choices on the
 * Linux development environment's page. It is Linux mode beside the home screen: full screen with
 * Linux's own status line where Android's status bar was, the session tabs with + / Display /
 * Settings, the terminal on a card, and the keys row on the keyboard's top edge; a swipe from the
 * bottom goes home, and the back gesture does nothing (Linux has no "back").
 */
class TerminalActivity : LinuxActivity(), TerminalViewClient, TerminalSessionClient {
    private lateinit var root: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var terminal: ComposingTerminalView
    private lateinit var model: InputModel
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyButton: Button
    private lateinit var keys: KeysRow
    private lateinit var statusLine: StatusLine
    private lateinit var card: FrameLayout
    private var resumed = false

    private val tabs = mutableListOf<TerminalTab>()
    private var current: TerminalTab? = null
    private var status: Status? = null
    private var opened = false
    private var wanted: String? = null
    private var refreshing: Job? = null
    private var textSize = 0
    private var imeShown = false
    private var imeAnimating = false
    private var appliedImeBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textSize = getPreferences(Context.MODE_PRIVATE).getInt(PREF_TEXT_SIZE, dp(14))
        buildViews()
        wanted = intent.getStringExtra(Linux.EXTRA_SESSION)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Linux.EXTRA_SESSION)?.let { id ->
            tabs.firstOrNull { it.id == id }?.let { select(it) } ?: run { wanted = id }
        }
    }

    override fun onStart() {
        super.onStart()
        opened = false
        statusLine.start()
        updateKeys()
        scope.launch {
            val choice = Linux.colors(this@TerminalActivity)
            val own = if (choice == "own") {
                try {
                    Linux.call("getColors") { it.colors }
                } catch (e: LinuxException) {
                    Log.w(TAG, e.message ?: "getColors")
                    null
                }
            } else {
                null
            }
            Palette.apply(this@TerminalActivity, choice, own)
            terminal.setBackgroundColor(Palette.background())
            (card.background as GradientDrawable).setColor(Palette.background())
            for (tab in tabs) tab.session.emulator?.mColors?.reset()
            terminal.invalidate()
            refresh(reattach = true)
        }
    }

    override fun onStop() {
        statusLine.stop()
        for (tab in tabs) tab.detach()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        LinuxMode.enter(this)
    }

    override fun onPause() {
        resumed = false
        LinuxMode.leave(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed) LinuxMode.enter(this)
    }

    override fun onDestroy() {
        for (tab in tabs) tab.close()
        super.onDestroy()
    }

    // ---- views ----

    private fun buildViews() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
        }
        statusLine = StatusLine(this)
        root.addView(statusLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(STATUS_LINE_DP)))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
        }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabsAndNew = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tabsRow)
            addView(iconButton(R.drawable.ic_add, R.string.new_session) { newSession(it) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(tabsAndNew)
        }
        bar.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(R.drawable.ic_display, R.string.display) {
            startActivity(Intent(this, DesktopActivity::class.java))
        })
        bar.addView(iconButton(R.drawable.ic_settings, R.string.settings) { openSettings() })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
            clipToOutline = true
        }
        card = frame
        model = InputModel(
            send = { bytes -> current?.session?.write(bytes, 0, bytes.size) },
            applicationCursorKeys = { terminal.mEmulator?.isCursorKeysApplicationMode == true },
        )
        terminal = ComposingTerminalView(this, model).apply {
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(textSize)
            setTypeface(ownFont() ?: Typeface.MONOSPACE)
        }
        model.onChanged = {
            terminal.invalidate()
            keys.refresh()
        }
        frame.addView(terminal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        emptyText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        }
        emptyButton = Button(this)
        empty.addView(emptyText)
        empty.addView(emptyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            bottomMargin = dp(8)
        })

        keys = KeysRow(this, model)
        root.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(KEYS_ROW_DP)))
        // The layout takes the keyboard's final height at once, so the terminal resizes once; the
        // keys row glides with the keyboard's animation and stays on its top edge.
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Android's bars are hidden: the status line stands in the band of the camera cutout
            // and of Android's status bar, as tall as that bar.
            val cut = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(cut.left, 0, cut.right, maxOf(cut.bottom, ime.bottom))
            val line = maxOf(dp(STATUS_LINE_DP), cut.top,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top)
            if (statusLine.layoutParams.height != line) {
                statusLine.layoutParams = statusLine.layoutParams.apply { height = line }
            }
            appliedImeBottom = ime.bottom
            imeShown = insets.isVisible(WindowInsets.Type.ime())
            updateKeys()
            WindowInsets.CONSUMED
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
            }

            override fun onProgress(insets: WindowInsets, running: List<WindowInsetsAnimation>): WindowInsets {
                if (running.none { it.typeMask and WindowInsets.Type.ime() != 0 }) return insets
                keys.translationY = (appliedImeBottom - insets.getInsets(WindowInsets.Type.ime()).bottom).toFloat()
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                imeAnimating = false
                keys.translationY = 0f
                updateKeys()
            }
        })
        setContentView(root)
    }

    /** The owner's font (LNX-3): files/font.ttf of this app, when there is one. */
    private fun ownFont(): Typeface? {
        val file = File(filesDir, "font.ttf")
        if (!file.isFile) return null
        return try {
            Typeface.createFromFile(file)
        } catch (e: RuntimeException) {
            Log.w(TAG, "the own font cannot be read: ${e.message}")
            null
        }
    }

    private fun iconButton(icon: Int, description: Int, onClick: (View) -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener(onClick)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ---- state ----

    override fun onChanged() {
        refreshing?.cancel()
        refreshing = scope.launch {
            delay(REFRESH_DELAY_MS)
            refresh(reattach = false)
        }
    }

    override fun onSessionExited(id: String, status: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (tab.ended) return
        tab.ended = true
        tab.detach()
        tab.session.onHostExit(status)
        renderTabs()
    }

    private suspend fun refresh(reattach: Boolean) {
        val s = try {
            Linux.call("getStatus") { it.status }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "getStatus")
            null
        }
        status = s
        statusLine.setUser(s?.user)
        if (s == null) {
            showEmpty(getString(R.string.service_missing), null)
            return
        }
        val ids = s.sessions.map { it.id }.toSet()
        for (info in s.sessions) {
            val tab = tabs.firstOrNull { it.id == info.id } ?: TerminalTab(info.id, this).also { tabs.add(it) }
            tab.kind = info.kind
            tab.name = info.name
            tab.busy = info.busy
            tab.job = info.job
            tab.awake = info.awake
        }
        for (tab in tabs.filter { it.id !in ids && !it.ended }) {
            tab.ended = true
            tab.detach()
            tab.session.onHostExit(0)
        }
        if (s.state == ILinux.STATE_RUNNING && s.sessions.isEmpty() && !opened && tabs.none { !it.ended }) {
            opened = true
            create(Linux.openKind(this))
            return
        }
        opened = true
        if (current == null || current !in tabs) {
            current = tabs.firstOrNull { it.id == wanted } ?: tabs.firstOrNull { !it.ended } ?: tabs.firstOrNull()
        }
        wanted?.let { id -> tabs.firstOrNull { it.id == id }?.let { current = it; wanted = null } }
        for (tab in tabs) {
            if (!tab.ended && (reattach || !attached.contains(tab.id))) attach(tab)
        }
        current?.let { show(it) }
        renderTabs()
        when {
            tabs.isNotEmpty() -> hideEmpty()
            s.state == ILinux.STATE_RUNNING -> showEmpty(getString(R.string.no_sessions), null)
            else -> showEmpty(stateText(s), getString(R.string.open_settings))
        }
    }

    private val attached = mutableSetOf<String>()
    // Sessions this window just made: their stream has no earlier output to replay.
    private val fresh = mutableSetOf<String>()

    private fun attach(tab: TerminalTab) {
        val reference = terminal.mEmulator
        val columns = reference?.mColumns ?: DEFAULT_COLUMNS
        val rows = reference?.mRows ?: DEFAULT_ROWS
        if (tab === current) terminal.attachSession(null)
        tab.session.dropEmulator()
        tab.session.initializeEmulator(columns, rows, 0, 0)
        attached.add(tab.id)
        val replay = !fresh.remove(tab.id)
        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tab.attach(columns, rows, replay) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "attachSession")
                attached.remove(tab.id)
            }
            if (tab === current) show(tab)
        }
    }

    private fun stateText(s: Status): String =
        when (s.state) {
            ILinux.STATE_NOT_INSTALLED -> getString(R.string.state_not_installed)
            ILinux.STATE_STOPPED -> getString(R.string.state_stopped)
            ILinux.STATE_STARTING -> getString(R.string.state_starting)
            ILinux.STATE_SETUP -> getString(R.string.state_setup)
            else -> getString(R.string.state_failed)
        }

    private fun showEmpty(text: String, button: String?) {
        empty.visibility = View.VISIBLE
        terminal.visibility = View.INVISIBLE
        emptyText.text = text
        emptyButton.visibility = if (button == null) View.GONE else View.VISIBLE
        emptyButton.text = button
        emptyButton.setOnClickListener { openSettings() }
    }

    private fun hideEmpty() {
        empty.visibility = View.GONE
        terminal.visibility = View.VISIBLE
    }

    // ---- tabs ----

    private fun renderTabs() {
        tabsRow.removeAllViews()
        for (tab in tabs) {
            tabsRow.addView(
                Chrome.tabView(
                    this, tab, tab === current,
                    onClick = { if (tab.ended) closeTab(tab) else select(tab) },
                    onLongClick = { tabSheet(tab) },
                )
            )
        }
    }

    private fun select(tab: TerminalTab) {
        // What is composing goes to the session it was typed in.
        if (tab !== current) model.finishComposingText()
        current = tab
        show(tab)
        renderTabs()
    }

    private fun show(tab: TerminalTab) {
        val other = terminal.mTermSession !== tab.session
        terminal.attachSession(tab.session)
        if (other) terminal.resetInput()
        terminal.onScreenUpdated()
        terminal.requestFocus()
    }

    private fun closeTab(tab: TerminalTab) {
        tab.close()
        tabs.remove(tab)
        attached.remove(tab.id)
        if (current === tab) {
            current = tabs.firstOrNull { !it.ended } ?: tabs.firstOrNull()
            current?.let { show(it) } ?: terminal.attachSession(null)
        }
        renderTabs()
        if (tabs.isEmpty()) scope.launch { refresh(reattach = false) }
    }

    private fun tabSheet(tab: TerminalTab) {
        Chrome.showSessionSheet(
            this, tab,
            onAwake = { awake -> act("setSessionAwake") { it.setSessionAwake(tab.id, awake) } },
            onRename = { name -> act("renameSession") { it.renameSession(tab.id, name) } },
            onEnd = { if (tab.ended) closeTab(tab) else act("endSession") { it.endSession(tab.id) } },
        )
    }

    private fun newSession(anchor: View) {
        Chrome.showNewSessionMenu(
            this, anchor, status?.user,
            linuxRuns = status?.state == ILinux.STATE_RUNNING,
            rootOk = Linux.rootSessions(this),
            onPick = ::create,
        )
    }

    private fun create(kind: String) {
        val columns = terminal.mEmulator?.mColumns ?: DEFAULT_COLUMNS
        val rows = terminal.mEmulator?.mRows ?: DEFAULT_ROWS
        scope.launch {
            try {
                wanted = Linux.call("createSession") { it.createSession(kind, "", columns, rows) }
                    .also { fresh.add(it) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "createSession")
                val message = when (e.code) {
                    ILinux.ERROR_ROOT_SESSIONS_OFF -> R.string.error_root_sessions_off
                    ILinux.ERROR_TOO_MANY_SESSIONS -> R.string.error_too_many_sessions
                    ILinux.ERROR_NOT_RUNNING -> R.string.state_stopped
                    else -> R.string.error_session
                }
                android.widget.Toast.makeText(this@TerminalActivity, message, android.widget.Toast.LENGTH_LONG).show()
            }
            refresh(reattach = false)
        }
    }

    private fun act(what: String, block: (ILinux) -> Unit) {
        scope.launch {
            try {
                Linux.call(what, block)
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: what)
            }
            refresh(reattach = false)
        }
    }

    // ---- the keys row ----

    /** The keys row is up while the on-screen keyboard is, with no hardware keyboard, when the owner chooses so. */
    private fun updateKeys() {
        val wanted = Linux.keysRow(this) && !hasHardwareKeyboard() && (imeShown || imeAnimating)
        keys.visibility = if (wanted) View.VISIBLE else View.GONE
    }

    private fun hasHardwareKeyboard(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        !d.isVirtual && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    }

    // ---- TerminalViewClient ----

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            textSize = (textSize + if (scale > 1f) 2 else -2).coerceIn(dp(6), dp(40))
            terminal.setTextSize(textSize)
            getPreferences(Context.MODE_PRIVATE).edit().putInt(PREF_TEXT_SIZE, textSize).apply()
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val tab = current
        if (tab != null && tab.ended) {
            closeTab(tab)
            return
        }
        getSystemService(InputMethodManager::class.java)?.showSoftInput(terminal, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val tab = current
        if (tab != null && tab.ended && keyCode == KeyEvent.KEYCODE_ENTER) {
            closeTab(tab)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = model.ctrlLatched
    override fun readAltKey() = model.altLatched
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false

    override fun onEmulatorSet() {
        terminal.setTerminalCursorBlinkerState(true, true)
    }

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === current?.session) terminal.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = renderTabs()

    override fun onSessionFinished(finishedSession: TerminalSession) = renderTabs()

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) =
        copyToClipboard(text, session.isHostOutput)

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val text = clipboardText() ?: return
        session.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    override fun onColorsChanged(session: TerminalSession) {
        if (session === current?.session) terminal.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) {
        Log.e(TAG, "$tag: $message")
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(TAG, "$tag: $message")
    }

    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(TAG, tag, e)
    }

    private companion object {
        const val PREF_TEXT_SIZE = "text_size"
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        const val REFRESH_DELAY_MS = 300L
        const val STATUS_LINE_DP = 28
        const val KEYS_ROW_DP = 40
    }
}
