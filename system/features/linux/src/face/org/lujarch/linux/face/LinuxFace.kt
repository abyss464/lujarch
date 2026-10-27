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

package org.lujarch.linux.face

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxEvents
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * Linux mode as the home screen's other face (LNX-3): the home screen shows Android's home or
 * this, one at a time, and while it shows this, this is the home. It holds the terminal and the
 * desktop as two pages of one window, takes the bottom swipe the home screen hands it (the terminal
 * goes to Android's home; on the desktop the swipe is the desktop's own touch), hides Android's bars and locks the
 * notification shade while on screen, answers what Linux asks of the owner (an authorization, an
 * NFC card, the clipboard) and keeps the Terminal app's service of the lent parts bound, with this
 * window's capabilities, for as long as Linux is on screen.
 *
 * The home screen creates it with its activity, puts [root] over its own views full screen (the
 * face takes the window's insets itself), and calls the lifecycle methods of its activity on it.
 */
class LinuxFace(val activity: Activity, private val host: Host) : LinuxEvents.Sink {
    /** What the face asks of the home screen. */
    fun interface Host {
        /** The desktop may follow the phone's rotation while it is on screen. */
        fun allowRotation(allow: Boolean)
    }

    enum class Page { TERMINAL, DESKTOP }

    /** The face's window content, over the home screen's own. */
    val root = FrameLayout(activity)
    val prefs: SharedPreferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var shown = false
        private set
    var page = Page.TERMINAL
        private set
    /** Linux on screen in front: resumed and focused. */
    var inFront = false
        private set
    /** Told when Linux comes to the front or leaves it: what floats over the home steps aside. */
    var onFront: ((Boolean) -> Unit)? = null
    /** The intent that last opened the face (a session to select). */
    var openIntent: Intent? = null
        private set

    private val pages = HashMap<Page, FacePage>()
    private var started = false
    private var resumed = false
    private var focused = false
    private val events = LinuxEvents(this)
    private var listening = false
    private var nfc: NfcReader? = null
    private val parts = PartsLink(activity)
    private var softInputMode: Int? = null
    private var reveal: Animator? = null

    init {
        root.visibility = View.GONE
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        // An edge swipe shows Android's bars again; Linux has its own, so they go at once.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (inFront && (insets.isVisible(WindowInsets.Type.statusBars()) ||
                    insets.isVisible(WindowInsets.Type.navigationBars()))) {
                v.post { if (inFront) hideBars() }
            }
            v.onApplyWindowInsets(insets)
        }
        if (prefs.getBoolean(PREF_SHOWN, false)) {
            page = if (prefs.getString(PREF_PAGE, null) == Page.DESKTOP.name) Page.DESKTOP else Page.TERMINAL
            shown = true
            root.visibility = View.VISIBLE
            pageFor(page).view?.visibility = View.VISIBLE
        }
    }

    // ---- turning the face ----

    /** Shows Linux at [which]; from Android's home the screen grows from ([x], [y]) when given. */
    fun show(which: Page, x: Float = Float.NaN, y: Float = Float.NaN, intent: Intent? = null) {
        if (intent != null) openIntent = intent
        val wasShown = shown
        if (wasShown && which == page) {
            intent?.let { pages[page]?.onNewIntent(it) }
            return
        }
        if (wasShown) leavePage(page)
        page = which
        shown = true
        save()
        val growing = !wasShown && !x.isNaN() && !y.isNaN()
        // A growing face is laid out unseen and first drawn already clipped to the tap point.
        root.visibility = if (growing) View.INVISIBLE else View.VISIBLE
        val p = pageFor(which)
        p.view?.visibility = View.VISIBLE
        enterPage(p)
        if (!wasShown) {
            follow()
            if (growing) grow(x, y)
        }
        intent?.let { p.onNewIntent(it) }
    }

    /** Turns the home screen to Android's home; Linux goes on. */
    fun leave() {
        if (!shown) return
        reveal?.cancel()
        leavePage(page)
        shown = false
        save()
        root.visibility = View.GONE
        follow()
    }

    /** The bottom swipe while the face is the home: true when it took it. */
    fun onSwipeUp(): Boolean {
        if (!shown) return false
        leave()
        return true
    }

    /** A key of the home screen's window: true when the page in front took it (the desktop's). */
    fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        shown && pages[page]?.dispatchKeyEvent(event) == true

    /** Linux has no "back": true while the face is shown. */
    fun onBack(): Boolean = shown

    /** The home screen's activity got [intent]: true when it was for Linux. */
    fun onNewIntent(intent: Intent): Boolean =
        when (intent.action) {
            ACTION_TERMINAL -> { show(Page.TERMINAL, intent = intent); true }
            ACTION_DESKTOP -> { show(Page.DESKTOP, intent = intent); true }
            else -> false
        }

    private fun grow(x: Float, y: Float) {
        val w = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: root.resources.displayMetrics.heightPixels
        val radius = maxOf(hypot(x, y), hypot(w - x, y), hypot(x, h - y), hypot(w - x, h - y))
        root.post {
            if (!root.isAttachedToWindow || !shown) return@post
            root.visibility = View.VISIBLE
            reveal = ViewAnimationUtils.createCircularReveal(root, x.toInt(), y.toInt(), 0f, radius).apply {
                duration = GROW_MS
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { reveal = null }
                })
                start()
            }
        }
    }

    // ---- the home screen's activity ----

    fun onStart() {
        started = true
        follow()
    }

    fun onStop() {
        started = false
        follow()
    }

    fun onResume() {
        resumed = true
        follow()
    }

    fun onPause() {
        resumed = false
        follow()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        follow()
        // Coming back from an app the window gets the bars' control only with its focus.
        if (hasFocus && inFront) hideBars()
        pages[page]?.takeIf { shown }?.onWindowFocusChanged(hasFocus)
    }

    fun onDestroy() {
        if (shown) leavePage(page)
        for (p in pages.values) p.onDestroy()
        pages.clear()
        stopListening()
        parts.unbind()
    }

    // ---- pages ----

    private fun pageFor(which: Page): FacePage =
        pages.getOrPut(which) {
            val p = when (which) {
                Page.TERMINAL -> TerminalPage(this)
                Page.DESKTOP -> DesktopPage(this)
            }
            p.onCreate()
            p.view?.let { v ->
                v.visibility = View.GONE
                root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            p
        }

    private fun enterPage(p: FacePage) {
        if (started) p.start()
        if (resumed) p.resume()
    }

    private fun leavePage(which: Page) {
        val p = pages[which] ?: return
        p.pause()
        p.stop()
        p.view?.let { v ->
            // The keyboard is the page's: it closes as the page leaves.
            v.findFocus()?.clearFocus()
            activity.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
            v.visibility = View.GONE
        }
    }

    /** Brings everything in line with shown, started, resumed and focused. */
    private fun follow() {
        val p = if (shown) pageFor(page) else null
        if (p != null) {
            if (started) p.start() else p.stop()
            if (started && resumed) p.resume() else p.pause()
        }
        val visible = shown && started
        if (visible) startListening() else stopListening()
        if (visible) parts.bind() else parts.unbind()
        val front = shown && resumed
        if (front != inFront) {
            inFront = front
            onFront?.invoke(front)
            bars(front)
            if (front && Linux.lent(activity, "nfc")) {
                nfc = NfcReader(activity).also { it.start() }
            } else if (!front) {
                nfc?.stop()
                nfc = null
            }
        }
        parts.mode(front && focused)
        host.allowRotation(front && page == Page.DESKTOP)
    }

    private fun save() {
        prefs.edit().putBoolean(PREF_SHOWN, shown).putString(PREF_PAGE, page.name).apply()
    }

    /** Android's bars hidden and the shade locked while the face is in front. */
    private fun bars(front: Boolean) {
        val window: Window = activity.window
        if (front) hideBars() else window.insetsController?.show(WindowInsets.Type.systemBars())
        if (front) {
            if (softInputMode == null) softInputMode = window.attributes.softInputMode
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        } else {
            softInputMode?.let { window.setSoftInputMode(it) }
            softInputMode = null
        }
        try {
            activity.getSystemService(StatusBarManager::class.java)
                ?.disable(if (front) StatusBarManager.DISABLE_EXPAND else StatusBarManager.DISABLE_NONE)
        } catch (e: SecurityException) {
            Log.e(TAG, "the status bar cannot be locked: ${e.message}")
        }
    }

    /** Android's bars out of the way of Linux's own status line. */
    private fun hideBars() {
        val controller = activity.window.insetsController ?: return
        // A hide under the default behavior fades the bars for 1.5 s over Linux's status line;
        // under the swipe behavior they move out in a third of a second. The hide takes the
        // behavior when it starts, and the default one comes back at once: with it the bottom
        // swipe reaches the home screen without first showing the bars.
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsets.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
    }

    // ---- what Linux asks of the owner ----

    private fun startListening() {
        if (listening) return
        listening = true
        background.execute { events.register() }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        background.execute { events.unregister() }
    }

    override fun onChanged() {
        pages[Page.TERMINAL]?.onChanged()
    }

    override fun onSessionExited(id: String, status: Int) {
        pages[Page.TERMINAL]?.onSessionExited(id, status)
    }

    override fun onAuthRequest(id: Int, text: String) {
        if (!inFront || !Linux.lent(activity, "fingerprint")) {
            answerAuth(id, false)
            return
        }
        val manager = activity.getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.w(TAG, "authorization $id: no fingerprint or screen lock to confirm with")
            answerAuth(id, false)
            return
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.lnx_auth_title))
            .setSubtitle(text)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                answerAuth(id, true)
            }

            override fun onAuthenticationError(code: Int, message: CharSequence?) {
                answerAuth(id, false)
            }
        })
    }

    override fun onNfcRequest(id: Int, command: ByteArray) {
        val reader = nfc
        if (reader == null) answerNfc(id, ByteArray(0), "no card") else reader.transceive(id, command)
    }

    /** The desktop's Terminal key (LNX-6). */
    override fun onShowTerminal() {
        if (shown && page == Page.DESKTOP) show(Page.TERMINAL)
    }

    override fun onClipboard(text: String) {
        copyToClipboard(text, fromLinux = true)
    }

    private fun answerAuth(id: Int, allowed: Boolean) {
        background.execute {
            try {
                Linux.blocking("answerAuth") { it.answerAuth(id, allowed) }
            } catch (e: LinuxException) {
                Log.w(TAG, e.message ?: "answerAuth")
            }
        }
    }

    /** Sets Android's clipboard from Linux when it is lent; [fromLinux] false: the owner's own copy. */
    fun copyToClipboard(text: String, fromLinux: Boolean) {
        if (fromLinux && !Linux.lent(activity, "clipboard")) {
            Log.i(TAG, "Linux set the clipboard while it is not lent; ignored")
            return
        }
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.lnx_app_name), text))
    }

    fun clipboardText(): String? =
        activity.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(activity)?.toString()

    fun openSettings() {
        try {
            // Its own task, as the home screen opens every app: never one of the home's; the page
            // itself, not one opened from it before.
            activity.startActivity(Intent(Linux.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "no page of the Linux development environment in Settings")
        }
    }

    // ---- the bottom swipe the home screen hands over ----

    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    /**
     * A motion of the home screen's bottom gesture (its touches from the navigation region) while
     * the face is shown: true when the face takes it. One swipe up past the touch slop, or a fling
     * up, is [onSwipeUp]; nothing else of Android's gestures (overview, quick switch) starts.
     */
    fun onGestureMotion(ev: MotionEvent): Boolean {
        if (!shown) return false
        if (pages[page]?.onEdgeTouch(ev) == true) return true
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x
                downY = ev.y
                swiping = true
            }
            MotionEvent.ACTION_MOVE -> velocity?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val v = velocity
                v?.addMovement(ev)
                v?.computeCurrentVelocity(1000)
                val up = downY - ev.y
                val fling = v != null && -v.yVelocity > ViewConfiguration.get(activity).scaledMinimumFlingVelocity &&
                    abs(v.yVelocity) > abs(v.xVelocity)
                if (swiping && ((up > slop && up > abs(ev.x - downX)) || fling)) onSwipeUp()
                v?.recycle()
                velocity = null
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
                swiping = false
            }
        }
        return true
    }

    companion object {
        const val ACTION_TERMINAL = "org.lujarch.linux.action.TERMINAL"
        const val ACTION_DESKTOP = "org.lujarch.linux.action.DESKTOP"
        private const val PREFS = "org.lujarch.linux.face"
        private const val PREF_SHOWN = "shown"
        private const val PREF_PAGE = "page"
        private const val GROW_MS = 350L

        /** Calls to lujarch_linux that may block, one after the other. */
        val background = Executors.newSingleThreadExecutor()

        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        fun answerNfc(id: Int, response: ByteArray, error: String?) {
            background.execute {
                try {
                    Linux.blocking("answerNfc") { it.answerNfc(id, response, error) }
                } catch (e: LinuxException) {
                    Log.w(TAG, e.message ?: "answerNfc")
                }
            }
        }
    }
}

/**
 * A page of the face (the terminal or the desktop): what was an activity of Linux mode, with its
 * lifecycle driven by [LinuxFace] and the home screen's window as its window.
 */
abstract class FacePage(val face: LinuxFace) : ContextWrapper(face.activity) {
    protected val scope = MainScope()
    val window: Window get() = face.activity.window
    protected val prefs: SharedPreferences get() = face.prefs
    protected val inFront: Boolean get() = face.inFront
    var view: View? = null
        private set
    private var started = false
    private var resumed = false

    protected fun setContentView(v: View) {
        view = v
    }

    fun start() {
        if (started) return
        started = true
        onStart()
    }

    fun stop() {
        if (!started) return
        pause()
        started = false
        onStop()
    }

    fun resume() {
        if (resumed || !started) return
        resumed = true
        onResume()
    }

    fun pause() {
        if (!resumed) return
        resumed = false
        onPause()
    }

    open fun onCreate() {}
    open fun onStart() {}
    open fun onStop() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onWindowFocusChanged(hasFocus: Boolean) {}
    open fun onNewIntent(intent: Intent) {}
    open fun onChanged() {}
    open fun onSessionExited(id: String, status: Int) {}
    /** A key of the window before its views: true when the page took it. */
    open fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = false
    /** A touch of the home screen's bottom gesture: true when the page takes it as its own touch. */
    open fun onEdgeTouch(event: MotionEvent): Boolean = false

    open fun onDestroy() {
        scope.cancel()
    }

    fun openSettings() = face.openSettings()
}

/**
 * The Terminal app's service of the parts Linux is lent (LNX-7), bound while the face is on
 * screen with the home screen's capabilities (microphone, camera, location while in use), and told
 * when Linux is in front for the Mode Linux.
 */
private class PartsLink(private val context: Context) {
    private var bound = false
    private var messenger: Messenger? = null
    private var mode = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service)
            send()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            messenger = null
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent().setComponent(SERVICE)
        bound = try {
            context.bindService(
                intent, connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_INCLUDE_CAPABILITIES,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "the Terminal app's parts: ${e.message}")
            false
        }
        if (!bound) Log.e(TAG, "the Terminal app's parts cannot be bound")
    }

    fun unbind() {
        if (!bound) return
        mode(false)
        bound = false
        messenger = null
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "the Terminal app's parts were not bound")
        }
    }

    /** The Mode Linux follows Linux being in front. */
    fun mode(on: Boolean) {
        if (mode == on) return
        mode = on
        send()
    }

    private fun send() {
        val m = messenger ?: return
        try {
            m.send(Message.obtain(null, MSG_MODE, if (mode) 1 else 0, 0))
        } catch (e: RemoteException) {
            Log.w(TAG, "the Terminal app's parts: ${e.message}")
        }
    }

    companion object {
        val SERVICE = ComponentName("org.lujarch.linux.terminal", "org.lujarch.linux.terminal.LinuxPartsService")
        const val MSG_MODE = 1
    }
}
