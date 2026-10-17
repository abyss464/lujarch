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

package org.lujarch.vault.data

import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.lujarch.vault.TAG
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PasswordDatabase
import org.lujarch.vault.core.KeystoreRefusal
import org.lujarch.vault.core.aegis.AegisEntry
import org.lujarch.vault.core.aegis.AegisException
import org.lujarch.vault.core.aegis.AegisVault
import org.lujarch.vault.core.formats.CodeFiles
import org.lujarch.vault.core.formats.FileFormatException
import org.lujarch.vault.core.formats.PasswordFiles
import org.lujarch.vault.core.kdbx.KdbxException
import org.lujarch.vault.core.kdbx.KdbxHeader
import org.lujarch.vault.core.kdbx.KdbxReader
import org.lujarch.vault.core.kdbx.KeyFile
import org.lujarch.vault.ui.Errors

/** Where the time of one open of the passwords went, in milliseconds. */
data class LoadTiming(
    /** Keystore unwrap of the cached transformed key. */
    val unwrapMs: Long,
    /** vault.read through the sync service. */
    val readMs: Long,
    /** Argon2 (or AES-KDF); 0 when the cached transformed key applied. */
    val kdfMs: Long,
    /** HMAC checks, decryption, decompression, XML. */
    val openMs: Long,
    val totalMs: Long,
    /** The cached transformed key did not apply (new seed or parameters) and the KDF ran. */
    val cold: Boolean,
)

/**
 * The decrypted passwords of one access and the facts of their file.
 * Whoever opened it wipes it ([wipe] is idempotent); [Vault.liveCount]
 * counts those not wiped yet.
 */
class PasswordData(
    val db: PasswordDatabase,
    val source: DbSource,
    val fileName: String,
    val size: Long,
    val mtime: Long,
    val sha256: String,
    val timing: LoadTiming,
    val loadedAt: Long,
) {
    private val wiped = AtomicBoolean(false)

    init {
        Vault.live.incrementAndGet()
    }

    fun wipe() {
        if (wiped.compareAndSet(false, true)) {
            db.wipe()
            Vault.live.decrementAndGet()
        }
    }
}

class CodesData(val entries: List<AegisEntry>, val loadedAt: Long, val millis: Long) {
    private val wiped = AtomicBoolean(false)

    init {
        Vault.live.incrementAndGet()
    }

    fun wipe() {
        if (wiped.compareAndSet(false, true)) {
            entries.forEach { it.wipe() }
            Vault.live.decrementAndGet()
        }
    }
}

/** What a setup reports ([skipped]: accounts of the file that could not be read); the decrypted data itself goes to the page session or is wiped. */
data class SetupResult(val entries: Int, val kdfMillis: Long, val totalMillis: Long, val skipped: Int = 0)

/** The stored key does not open the chosen password file: its setup needs the password. */
class PasswordNeededException(message: String) : Exception(message)

/** State of one source (passwords or 2FA codes) in the page session. */
sealed class Source<out T> {
    object NotSetUp : Source<Nothing>()

    /** Set up and turned off by the user: not opened, not used for autofill; the keys stay. */
    object Off : Source<Nothing>()

    /** Set up, nothing decrypted: no vault page is visible. */
    object Idle : Source<Nothing>()

    object Loading : Source<Nothing>()

    class Ready<T>(val data: T) : Source<T>()

    class Failed(val error: Throwable) : Source<Nothing>()
}

data class VaultState(
    val passwords: Source<PasswordData>,
    val codes: Source<CodesData>,
    /** A changed database is being loaded while the previous one stays shown. */
    val reloading: Boolean = false,
) {
    val loading: Boolean
        get() = passwords is Source.Loading || codes is Source.Loading

    val passwordsData: PasswordData?
        get() = (passwords as? Source.Ready)?.data

    val codesData: CodesData?
        get() = (codes as? Source.Ready)?.data

    val anySetUp: Boolean
        get() = passwords !is Source.NotSetUp || codes !is Source.NotSetUp

    /** A source set up and turned on. */
    val anyActive: Boolean
        get() = passwords.active || codes.active
}

/** Set up and turned on. */
val Source<*>.active: Boolean
    get() = this !is Source.NotSetUp && this !is Source.Off

/** The key file a KDBX setup uses (VLT-1). */
sealed class KeyFileChoice {
    object None : KeyFileChoice()

    /** The file `fos vault-keyfile` copied into the app's files; deleted after a successful setup. */
    object Imported : KeyFileChoice()

    /**
     * A file picked with the system file picker. Only its derived key (what
     * the composite key needs) is held, in memory, until setup ends; the file
     * itself is never stored.
     */
    class Picked(val name: String, val size: Long, val key: ByteArray) : KeyFileChoice() {
        fun wipe() = Bytes.wipe(key)
    }
}

/** The stored keys were deleted by the automatic forget policy; the vault needs setting up again. */
class KeysForgottenException : Exception("keys forgotten by the forget policy")


/**
 * Access to the vault (VLT-1). Nothing decrypted is kept at
 * rest; the lock guarantee is the Keystore's alone:
 *
 * - Every access unwraps a key first (the cached derived key, the stored
 *   credential of the passwords, the master key of the 2FA codes); a
 *   password file without a password of its own has a random key stored
 *   for this alone. While the phone is locked the Keystore refuses,
 *   whatever state this process is in (frozen, cached, never told about
 *   the lock).
 * - Pages hold the decrypted vault in a session: the first visible page
 *   opens it ([acquire] from onStart), the last one to stop drops and wipes
 *   it ([release] from onStop), so leaving the app or turning the screen off
 *   wipes it through the activity lifecycle, not through broadcasts.
 * - An autofill request opens the passwords for itself ([withPasswords]) and
 *   wipes it when its response is built.
 * - The KDF output (Argon2, AES-KDF, PBKDF2) is cached wrapped (slot
 *   TRANSFORMED, labeled with the KDF fingerprint): an access costs an
 *   unwrap, a read and a parse; the KDF runs again only after the salt or
 *   parameters changed (a save or an export on the PC).
 * - The passwords' file is read from its source on every open: a synced folder
 *   through the system service, or a picked document through its persisted
 *   read grant, so a file another app updated is picked up.
 * - Before any unwrap, the forget policy may delete the keys (after a
 *   restart, after days without use).
 * - A page's open refused by the Keystore while the phone is locked leaves
 *   the source idle (pages say "Locked"), not failed, and it opens once the
 *   phone is unlocked while a page still holds the session (KeystoreRefusal:
 *   a page on another display, or one the unlock itself started).
 *
 * Session state changes happen on the main thread.
 */
object Vault {
    /** The vault's own file of 2FA accounts (Aegis' vault format). */
    const val CODES_FILE = "aegis.json"

    /** What the stored passwords' key is, its label: what opens the file again. */
    private val CREDENTIAL_KDBX = ByteArray(0)
    private val CREDENTIAL_PASSWORD = "password".toByteArray()
    private val CREDENTIAL_NONE = "none".toByteArray()

    /** The kinds of password file (VaultPrefs.passwordsKind). */
    const val KIND_KDBX = "kdbx"
    const val KIND_ENCRYPTED = "encrypted"
    const val KIND_PLAIN = "plain"

    /** One-time import of the KDBX key file (`fos vault-keyfile`); deleted after a successful setup. */
    const val KEY_FILE_IMPORT = "keyfile.import"
    private const val CHANGE_CHECK_INTERVAL_MS = 10_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Largest key file read from a document; KeePass hashes files of any other shape whole. */
    const val MAX_KEY_FILE_BYTES = 16 * 1024 * 1024

    /** Decrypted passwords and 2FA codes not wiped yet (module tests observe it). */
    val live = AtomicInteger(0)

    val liveCount: Int
        get() = live.get()

    lateinit var prefs: VaultPrefs
        private set
    lateinit var keys: KeyWrapper
        private set
    private lateinit var appContext: Context
    private lateinit var keyguard: KeyguardManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(VaultState(Source.NotSetUp, Source.NotSetUp))
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** Visible pages holding the session. */
    private var holders = 0

    /** Bumped when the session is dropped or a source set up again; a load of an older epoch wipes its result. */
    private var passwordsEpoch = 0L
    private var codesEpoch = 0L
    private var passwordsJob: Job? = null
    private var passwordsJobEpoch = -1L
    private var codesJob: Job? = null
    private var codesJobEpoch = -1L
    private var lastChangeCheck = 0L
    private var initialized = false

    /** The source's last open was refused by the Keystore on an unlocked phone and is being retried. */
    private var passwordsRetried = false
    private var codesRetried = false

    /** Opens waiting sources again once the phone is unlocked ([waitForUnlock]). */
    private var unlockWatch: Job? = null

    /**
     * Idempotent: the Application calls it, and so does the test provider,
     * whose calls can arrive before Application.onCreate has run (providers
     * are published first).
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = VaultPrefs(appContext)
        keys = KeyWrapper(appContext)
        keyguard = appContext.getSystemService(KeyguardManager::class.java)
        _state.value = idleState()
        initialized = true
    }

    val codesFile: File
        get() = File(appContext.filesDir, CODES_FILE)

    val importedKeyFile: File
        get() = File(appContext.filesDir, KEY_FILE_IMPORT)

    fun passwordsSetUp(): Boolean = keys.has(KeyWrapper.Slot.KEEPASS) && prefs.passwordsSource != null

    fun codesSetUp(): Boolean = keys.has(KeyWrapper.Slot.AEGIS) && codesFile.isFile

    /** Set up and turned on: opened for pages, codes and autofill. */
    fun passwordsActive(): Boolean = passwordsSetUp() && prefs.passwordsEnabled

    fun codesActive(): Boolean = codesSetUp() && prefs.codesEnabled

    /** For status texts only; access control is the Keystore's. */
    val deviceLocked: Boolean
        get() = keyguard.isDeviceLocked

    val sessionHolders: Int
        get() = holders

    private fun idle(setUp: Boolean, enabled: Boolean): Source<Nothing> = when {
        !setUp -> Source.NotSetUp
        !enabled -> Source.Off
        else -> Source.Idle
    }

    private fun passwordsIdle() = idle(passwordsSetUp(), prefs.passwordsEnabled)

    private fun codesIdle() = idle(codesSetUp(), prefs.codesEnabled)

    private fun idleState() = VaultState(passwordsIdle(), codesIdle())

    /** The state pages show while they are stopped: nothing decrypted. */
    fun emptyState(): VaultState = idleState()

    private fun update(block: VaultState.() -> VaultState) {
        _state.value = _state.value.block()
    }

    // ---- forget policy ----

    private fun bootCount(): Int = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Whether the forget policy asks for the stored keys to go now. */
    fun keysExpired(): Boolean {
        if (!keys.has(KeyWrapper.Slot.KEEPASS) && !keys.has(KeyWrapper.Slot.AEGIS)) return false
        val policy = prefs.forgetPolicy
        return when (policy) {
            VaultPrefs.ForgetPolicy.NEVER -> false
            VaultPrefs.ForgetPolicy.RESTART -> {
                val stored = prefs.keysBoot
                val now = bootCount()
                stored >= 0 && now >= 0 && stored != now
            }
            else -> {
                val last = prefs.lastUsedAt
                last > 0 && System.currentTimeMillis() - last > policy.days * DAY_MS
            }
        }
    }

    /** Chosen in Settings; counts from now, so choosing it never deletes the keys at once. */
    fun setForgetPolicy(policy: VaultPrefs.ForgetPolicy) {
        prefs.forgetPolicy = policy
        markKeysFresh()
    }

    private fun markKeysFresh() {
        prefs.keysBoot = bootCount()
        prefs.lastUsedAt = System.currentTimeMillis()
    }

    /** Before any unwrap: deletes the keys when the policy says so, and refuses the access. */
    private suspend fun enforceForgetPolicy() {
        if (!keysExpired()) return
        withContext(Dispatchers.Main.immediate) {
            if (keysExpired()) forgetKeys("forget policy ${prefs.forgetPolicy.key}")
        }
        throw KeysForgottenException()
    }

    // ---- one access ----

    /**
     * Opens the passwords for one access; the caller wipes the result.
     * Order: the forget policy, the Keystore (a locked phone stops here), the
     * file from its source, then the cached derived key if it was derived for
     * the file's KDF parameters, else the stored credential and the KDF, whose
     * output replaces the cache. A file without a password of its own is read
     * once a key was unwrapped.
     */
    suspend fun openPasswords(): PasswordData = withContext(Dispatchers.Default + NonCancellable) {
        enforceForgetPolicy()
        val source = prefs.passwordsSource ?: throw IllegalStateException("the passwords are not set up")
        val t0 = System.nanoTime()
        val cached = if (keys.has(KeyWrapper.Slot.TRANSFORMED)) keys.loadLabeled(KeyWrapper.Slot.TRANSFORMED) else null
        val credential = if (cached == null) keys.loadLabeled(KeyWrapper.Slot.KEEPASS) else null
        val t1 = System.nanoTime()
        try {
            val file = Sources.read(appContext, source)
            val t2 = System.nanoTime()
            try {
                val opened = openFile(file, cached) {
                    credential?.let { KeyWrapper.Labeled(it.label, it.secret.copyOf()) } ?: keys.loadLabeled(KeyWrapper.Slot.KEEPASS)
                }
                try {
                    if (opened.transformed != null && opened.fingerprint != null) {
                        keys.store(KeyWrapper.Slot.TRANSFORMED, opened.transformed, opened.fingerprint)
                        Log.i(TAG, "KDF ran (${opened.kdfMs} ms); derived key cached for the new parameters")
                    }
                } catch (e: Throwable) {
                    opened.db.wipe()
                    throw e
                } finally {
                    opened.wipeKey()
                }
                val t3 = System.nanoTime()
                val timing = LoadTiming(
                    unwrapMs = (t1 - t0) / 1_000_000,
                    readMs = (t2 - t1) / 1_000_000,
                    kdfMs = opened.kdfMs,
                    openMs = (t3 - t2) / 1_000_000 - opened.kdfMs,
                    totalMs = (t3 - t0) / 1_000_000,
                    cold = opened.transformed != null,
                )
                val data = PasswordData(opened.db, source, file.name, file.size, file.mtime, file.sha256, timing, System.currentTimeMillis())
                prefs.passwordsKind = opened.kind
                recordPasswords(data)
                prefs.lastUsedAt = data.loadedAt
                data
            } finally {
                Bytes.wipe(file.data)
            }
        } catch (e: Throwable) {
            recordFailure(VaultPrefs.Source.PASSWORDS, "opening the passwords", e)
            throw e
        } finally {
            cached?.let { Bytes.wipe(it.secret) }
            credential?.let { Bytes.wipe(it.secret) }
        }
    }

    /** Opens the 2FA codes for one access with the wrapped master key; the caller wipes the result. */
    suspend fun openCodes(): CodesData = withContext(Dispatchers.Default + NonCancellable) {
        val t0 = System.nanoTime()
        try {
            enforceForgetPolicy()
            val master = keys.load(KeyWrapper.Slot.AEGIS)
            try {
                val bytes = AtomicFile(codesFile).readFully()
                val entries = AegisVault.parse(bytes).open(master)
                val data = CodesData(entries, System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
                prefs.putStatus(
                    VaultPrefs.Source.CODES,
                    VaultPrefs.LoadStatus(
                        loadedAt = data.loadedAt, fileSize = codesFile.length(), fileMtime = codesFile.lastModified(),
                        entries = entries.size, totalMillis = data.millis,
                    ),
                )
                prefs.lastUsedAt = data.loadedAt
                data
            } finally {
                Bytes.wipe(master)
            }
        } catch (e: Throwable) {
            recordFailure(VaultPrefs.Source.CODES, "opening the 2FA codes", e)
            throw e
        }
    }

    /**
     * Opens the database for [block] and wipes it afterwards (autofill: one
     * request, one open). The open itself is not cancellable, so a cancelled
     * caller still wipes what was decrypted.
     */
    suspend fun <T> withPasswords(block: suspend (PasswordData) -> T): T {
        val data = openPasswords()
        try {
            currentCoroutineContext().ensureActive()
            return block(data)
        } finally {
            data.wipe()
        }
    }

    // ---- page session ----

    /** A vault page became visible (onStart); main thread. */
    fun acquire(reason: String) {
        holders++
        requestLoad(reason)
    }

    /** A vault page stopped (onStop); the last one drops the decrypted vault. Main thread. */
    fun release() {
        if (holders > 0) holders--
        if (holders == 0) drop("no vault page visible")
    }

    /** Loads what the session lacks; only while a page holds it. Main thread. */
    fun requestLoad(reason: String) {
        if (holders == 0) return
        if (keysExpired()) forgetKeys("forget policy ${prefs.forgetPolicy.key}")
        val s = _state.value
        if (passwordsActive()) {
            if (s.passwords !is Source.Ready && (passwordsJob?.isActive != true || passwordsJobEpoch != passwordsEpoch)) {
                startPasswords(reason, reload = false)
            }
        } else {
            settlePasswords()
        }
        if (codesActive()) {
            if (s.codes !is Source.Ready && (codesJob?.isActive != true || codesJobEpoch != codesEpoch)) {
                startCodes(reason)
            }
        } else {
            settleCodes()
        }
    }

    /** The passwords are not active: their state becomes Off or NotSetUp, and whatever was shown is wiped. */
    private fun settlePasswords() {
        val want = passwordsIdle()
        val s = _state.value
        if (s.passwords === want) return
        passwordsEpoch++
        update { copy(passwords = want, reloading = false) }
        s.passwordsData?.wipe()
    }

    private fun settleCodes() {
        val want = codesIdle()
        val s = _state.value
        if (s.codes === want) return
        codesEpoch++
        update { copy(codes = want) }
        s.codesData?.wipe()
    }

    /** Turns the passwords on or off; off wipes what the session shows. Main thread. */
    fun setPasswordsEnabled(on: Boolean) {
        prefs.passwordsEnabled = on
        if (on) {
            if (_state.value.passwords === Source.Off) update { copy(passwords = passwordsIdle()) }
            requestLoad("passwords turned on")
        } else {
            settlePasswords()
        }
        Log.i(TAG, "passwords turned ${if (on) "on" else "off"}")
    }

    fun setCodesEnabled(on: Boolean) {
        prefs.codesEnabled = on
        if (on) {
            if (_state.value.codes === Source.Off) update { copy(codes = codesIdle()) }
            requestLoad("2FA codes turned on")
        } else {
            settleCodes()
        }
        Log.i(TAG, "2FA codes turned ${if (on) "on" else "off"}")
    }

    private fun startPasswords(reason: String, reload: Boolean) {
        val epoch = passwordsEpoch
        Log.i(TAG, "opening the passwords ($reason)")
        if (reload) update { copy(reloading = true) } else update { copy(passwords = Source.Loading) }
        passwordsJobEpoch = epoch
        passwordsJob = scope.launch {
            val result = try {
                Result.success(openPasswords())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (epoch != passwordsEpoch || holders == 0) {
                // The session was dropped (or the passwords set up again) meanwhile.
                result.getOrNull()?.wipe()
                return@launch
            }
            result.fold(
                onSuccess = {
                    passwordsRetried = false
                    installPasswords(it)
                },
                onFailure = { e ->
                    val refusal = (e as? KeystoreLockedException)?.let { KeystoreRefusal.next(deviceLocked, passwordsRetried) }
                    when {
                        e is KeysForgottenException -> Unit // forgetKeys has set the state
                        reload -> update { copy(reloading = false) }
                        refusal == null || refusal == KeystoreRefusal.Next.FAIL -> update { copy(passwords = Source.Failed(e)) }
                        else -> {
                            // Locked: the source waits (pages say "Locked"); unlocked: one retry.
                            passwordsRetried = refusal == KeystoreRefusal.Next.RETRY
                            update { copy(passwords = if (passwordsRetried) Source.Loading else passwordsIdle()) }
                            waitForUnlock()
                        }
                    }
                },
            )
        }
    }

    /**
     * A source waits after a Keystore refusal (KeystoreRefusal): every second while a page
     * holds the session, once the phone is unlocked, the waiting sources are opened again
     * (requestLoad opens what is not ready). Polling while a page is visible, not a keyguard
     * listener: access control stays the Keystore's alone.
     */
    private fun waitForUnlock() {
        if (unlockWatch?.isActive == true) return
        unlockWatch = scope.launch {
            while (holders > 0) {
                delay(KeystoreRefusal.RETRY_MS)
                if (holders > 0 && !deviceLocked) {
                    requestLoad("phone unlocked")
                    break
                }
            }
        }
    }

    private fun installPasswords(data: PasswordData) {
        val old = _state.value.passwordsData
        update { copy(passwords = Source.Ready(data), reloading = false) }
        if (old != null && old !== data) old.wipe()
    }

    private fun installCodes(data: CodesData) {
        val old = _state.value.codesData
        update { copy(codes = Source.Ready(data)) }
        if (old != null && old !== data) old.wipe()
    }

    private fun startCodes(reason: String) {
        val epoch = codesEpoch
        Log.i(TAG, "opening the 2FA codes ($reason)")
        update { copy(codes = Source.Loading) }
        codesJobEpoch = epoch
        codesJob = scope.launch {
            val result = try {
                Result.success(openCodes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (epoch != codesEpoch || holders == 0) {
                result.getOrNull()?.wipe()
                return@launch
            }
            result.fold(
                onSuccess = {
                    codesRetried = false
                    installCodes(it)
                },
                onFailure = { e ->
                    val refusal = (e as? KeystoreLockedException)?.let { KeystoreRefusal.next(deviceLocked, codesRetried) }
                    when {
                        e is KeysForgottenException -> Unit
                        refusal == null || refusal == KeystoreRefusal.Next.FAIL -> update { copy(codes = Source.Failed(e)) }
                        else -> {
                            codesRetried = refusal == KeystoreRefusal.Next.RETRY
                            update { copy(codes = if (codesRetried) Source.Loading else codesIdle()) }
                            waitForUnlock()
                        }
                    }
                },
            )
        }
    }

    /**
     * While a page shows the database: asks its source for size and mtime and
     * opens the file again when they differ. At most every 10 s; main thread.
     */
    fun checkForChanges() {
        val data = _state.value.passwordsData ?: return
        val now = System.currentTimeMillis()
        if (now - lastChangeCheck < CHANGE_CHECK_INTERVAL_MS || passwordsJob?.isActive == true) return
        lastChangeCheck = now
        val epoch = passwordsEpoch
        scope.launch {
            val stat = try {
                Sources.stat(appContext, data.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "checking the passwords' file for changes failed; keeping the shown copy: ${e.message}")
                return@launch
            }
            if (epoch != passwordsEpoch || _state.value.passwordsData !== data) return@launch
            // A provider that reports no size or time leaves the check to the next open.
            val sizeChanged = stat.size >= 0 && stat.size != data.size
            val timeChanged = stat.mtime > 0 && stat.mtime != data.mtime
            if ((sizeChanged || timeChanged) && passwordsJob?.isActive != true) {
                startPasswords("file changed", reload = true)
            }
        }
    }

    /** Wipes the session's decrypted data; main thread. */
    private fun drop(reason: String) {
        passwordsEpoch++
        codesEpoch++
        passwordsRetried = false
        codesRetried = false
        unlockWatch?.cancel()
        unlockWatch = null
        val s = _state.value
        val had = s.passwordsData != null || s.codesData != null
        _state.value = idleState()
        s.passwordsData?.wipe()
        s.codesData?.wipe()
        if (had) Log.i(TAG, "decrypted vault dropped ($reason)")
    }

    // ---- setup ----

    /**
     * Deletes the imported key file: after a successful setup, or on request.
     * Overwritten first as a matter of course (f2fs may keep old blocks; the
     * file-based encryption of /data covers those).
     */
    fun deleteImportedKeyFile(reason: String) {
        val f = importedKeyFile
        if (!f.exists()) return
        try {
            java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.write(ByteArray(raf.length().toInt()))
                raf.fd.sync()
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "overwriting the imported key file failed: ${e.message}")
        }
        if (f.delete()) Log.i(TAG, "imported key file deleted ($reason)") else Log.e(TAG, "deleting the imported key file failed")
    }

    /** The key a key file contributes to the composite key; [raw] stays the caller's to wipe. */
    fun keyFileKey(raw: ByteArray): ByteArray = KeyFile.load(raw)

    private fun importedKeyFileKey(): ByteArray {
        val raw = try {
            AtomicFile(importedKeyFile).readFully()
        } catch (e: java.io.FileNotFoundException) {
            throw KeyFile.InvalidKeyFile("no imported key file")
        }
        return try {
            KeyFile.load(raw)
        } finally {
            Bytes.wipe(raw)
        }
    }

    /**
     * A password file opened, and what of it a later open needs: its [kind], and when a key was
     * derived ([transformed], to be cached under [fingerprint]) and how long that took.
     */
    private class Opened(
        val db: PasswordDatabase,
        val kind: String,
        val transformed: ByteArray? = null,
        val fingerprint: ByteArray? = null,
        val kdfMs: Long = 0,
    ) {
        fun wipeKey() = Bytes.wipe(transformed)
    }

    private fun requireCredential(credential: KeyWrapper.Labeled, label: ByteArray) {
        if (!credential.label.contentEquals(label)) {
            throw FileFormatException(FileFormatException.Reason.NEEDS_SETUP, "the stored key is not one this file's format takes")
        }
    }

    /**
     * Opens [file] in its format: with [cached] when it was derived for the
     * file's KDF parameters, else with the stored credential [credential]
     * loads (the caller wipes [cached]; this wipes what [credential] returns).
     */
    private fun openFile(file: FileContent, cached: KeyWrapper.Labeled?, credential: () -> KeyWrapper.Labeled): Opened {
        when (val kind = PasswordFiles.detect(file.data)) {
            PasswordFiles.Kind.Kdbx -> {
                val header = KdbxHeader.parse(file.data)
                val fingerprint = header.kdfFingerprint()
                if (cached != null && Bytes.constantTimeEquals(cached.label, fingerprint)) {
                    try {
                        return Opened(KdbxReader.openWithKey(file.data, header, cached.secret), KIND_KDBX)
                    } catch (e: KdbxException) {
                        // A cache left from an earlier composite key: derive again.
                        if (e.reason != KdbxException.Reason.WRONG_KEY) throw e
                        Log.w(TAG, "cached transformed key does not open the database; deriving it again")
                    }
                }
                val c = credential()
                try {
                    requireCredential(c, CREDENTIAL_KDBX)
                    return tryKdbx(file.data, header, c.secret)
                        ?: throw KdbxException(KdbxException.Reason.WRONG_KEY, "the stored key does not open the database")
                } finally {
                    Bytes.wipe(c.secret)
                }
            }
            is PasswordFiles.Kind.Encrypted -> {
                val fingerprint = kind.export.fingerprint()
                if (cached != null && Bytes.constantTimeEquals(cached.label, fingerprint)) {
                    try {
                        return Opened(kind.open(cached.secret, file.name), KIND_ENCRYPTED)
                    } catch (e: FileFormatException) {
                        if (e.reason != FileFormatException.Reason.WRONG_PASSWORD) throw e
                        Log.w(TAG, "cached derived key does not open the export; deriving it again")
                    }
                }
                val c = credential()
                try {
                    requireCredential(c, CREDENTIAL_PASSWORD)
                    return openEncrypted(kind, file.name, c.secret)
                } finally {
                    Bytes.wipe(c.secret)
                }
            }
            is PasswordFiles.Kind.Plain -> {
                // The file has no password: the stored key's unwrap is what opens it.
                Bytes.wipe(credential().secret)
                return Opened(kind.read(file.name), KIND_PLAIN)
            }
        }
    }

    /** A KDBX database opened with [composite]; null when the key does not open it. */
    private fun tryKdbx(file: ByteArray, header: KdbxHeader, composite: ByteArray): Opened? {
        val k0 = System.nanoTime()
        val transformed = KdbxReader.transformKey(header.kdf, composite)
        val kdfMs = (System.nanoTime() - k0) / 1_000_000
        return try {
            Opened(KdbxReader.openWithKey(file, header, transformed), KIND_KDBX, transformed, header.kdfFingerprint(), kdfMs)
        } catch (e: KdbxException) {
            Bytes.wipe(transformed)
            if (e.reason != KdbxException.Reason.WRONG_KEY) throw e
            null
        } catch (e: Throwable) {
            Bytes.wipe(transformed)
            throw e
        }
    }

    /** A password-protected export opened with [password]; FileFormatException(WRONG_PASSWORD) when it does not open it. */
    private fun openEncrypted(kind: PasswordFiles.Kind.Encrypted, fileName: String, password: ByteArray): Opened {
        val k0 = System.nanoTime()
        val key = kind.export.deriveKey(password)
        val kdfMs = (System.nanoTime() - k0) / 1_000_000
        return try {
            Opened(kind.open(key, fileName), KIND_ENCRYPTED, key, kind.export.fingerprint(), kdfMs)
        } catch (e: Throwable) {
            Bytes.wipe(key)
            throw e
        }
    }

    /** What the password file needs to be set up: KDBX (password and key file), a password, or nothing. */
    enum class PasswordFileKind { KDBX, ENCRYPTED, PLAIN }

    /** Reads [source] and tells its kind; throws when it cannot be read or holds no passwords. */
    suspend fun passwordFileKind(source: DbSource): PasswordFileKind = withContext(Dispatchers.Default) {
        val file = Sources.read(appContext, source)
        try {
            when (PasswordFiles.detect(file.data)) {
                PasswordFiles.Kind.Kdbx -> PasswordFileKind.KDBX
                is PasswordFiles.Kind.Encrypted -> PasswordFileKind.ENCRYPTED
                is PasswordFiles.Kind.Plain -> PasswordFileKind.PLAIN
            }
        } finally {
            Bytes.wipe(file.data)
        }
    }

    /**
     * Makes [source] the passwords' file: stores the derived key for it (or
     * drops a cached one when nothing was derived), the source and its kind,
     * releases grants of documents no longer used, and hands the opened
     * passwords to a visible page (or wipes them).
     */
    private suspend fun adoptPasswords(source: DbSource, file: FileContent, opened: Opened, t0: Long): SetupResult {
        val data = try {
            if (opened.transformed != null && opened.fingerprint != null) {
                keys.store(KeyWrapper.Slot.TRANSFORMED, opened.transformed, opened.fingerprint)
            } else {
                keys.delete(KeyWrapper.Slot.TRANSFORMED)
            }
            val total = (System.nanoTime() - t0) / 1_000_000
            PasswordData(
                opened.db, source, file.name, file.size, file.mtime, file.sha256,
                LoadTiming(0, 0, opened.kdfMs, total - opened.kdfMs, total, cold = opened.transformed != null), System.currentTimeMillis(),
            )
        } catch (e: Throwable) {
            opened.db.wipe()
            throw e
        }
        prefs.passwordsSource = source
        prefs.passwordsKind = opened.kind
        Sources.releaseGrantsExcept(appContext, (source as? DbSource.Document)?.uri)
        recordPasswords(data)
        val result = SetupResult(data.db.entries.size, data.timing.kdfMs, data.timing.totalMs)
        withContext(Dispatchers.Main.immediate) {
            passwordsEpoch++
            if (holders > 0 && prefs.passwordsEnabled) installPasswords(data) else data.wipe()
        }
        return result
    }

    /** Stores [credential] (labeled [label]) as the passwords' key; wipes [opened] when the Keystore refuses. */
    private fun storeCredential(opened: Opened, credential: ByteArray, label: ByteArray) {
        try {
            keys.store(KeyWrapper.Slot.KEEPASS, credential, label)
        } catch (e: Throwable) {
            opened.db.wipe()
            opened.wipeKey()
            throw e
        }
    }

    /**
     * Opens [source] in its format and stores what opens it again, wrapped:
     * for a KDBX database the composite key of the password and key file
     * (the KDF runs once to prove them right; an empty password means no
     * password component, and like KeePassXC an empty password is tried as
     * well when that fails and a key file is used) and an imported key file
     * is deleted; for a password-protected export its password; for a file
     * without a password a random key, whose unwrap opens it.
     */
    suspend fun setupPasswords(source: DbSource, keyFile: KeyFileChoice, password: ByteArray): SetupResult =
        withContext(Dispatchers.Default) {
            val file = Sources.read(appContext, source)
            try {
                val t0 = System.nanoTime()
                when (val kind = PasswordFiles.detect(file.data)) {
                    PasswordFiles.Kind.Kdbx -> setupKdbx(source, file, keyFile, password, t0)
                    is PasswordFiles.Kind.Encrypted -> {
                        val opened = openEncrypted(kind, file.name, password)
                        try {
                            storeCredential(opened, password, CREDENTIAL_PASSWORD)
                            prefs.usesKeyFile = false
                            markKeysFresh()
                            adoptPasswords(source, file, opened, t0)
                        } finally {
                            opened.wipeKey()
                        }
                    }
                    is PasswordFiles.Kind.Plain -> {
                        val opened = Opened(kind.read(file.name), KIND_PLAIN)
                        val marker = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        try {
                            storeCredential(opened, marker, CREDENTIAL_NONE)
                        } finally {
                            Bytes.wipe(marker)
                        }
                        prefs.usesKeyFile = false
                        markKeysFresh()
                        adoptPasswords(source, file, opened, t0)
                    }
                }.also { Log.i(TAG, "passwords set up with ${source.fileName} (${prefs.passwordsKind}, ${it.entries} entries)") }
            } finally {
                Bytes.wipe(file.data)
            }
        }

    private suspend fun setupKdbx(source: DbSource, file: FileContent, keyFile: KeyFileChoice, password: ByteArray, t0: Long): SetupResult {
        val keyFileKey = when (keyFile) {
            KeyFileChoice.None -> null
            KeyFileChoice.Imported -> importedKeyFileKey()
            is KeyFileChoice.Picked -> keyFile.key.copyOf()
        }
        val candidates = ArrayList<ByteArray>()
        try {
            if (password.isNotEmpty() || keyFileKey == null) {
                candidates.add(KeyFile.compositeKey(password, keyFileKey))
            } else {
                candidates.add(KeyFile.compositeKey(null, keyFileKey))
                candidates.add(KeyFile.compositeKey(ByteArray(0), keyFileKey))
            }
            val header = KdbxHeader.parse(file.data)
            for (composite in candidates) {
                val opened = tryKdbx(file.data, header, composite) ?: continue
                try {
                    storeCredential(opened, composite, CREDENTIAL_KDBX)
                    prefs.usesKeyFile = keyFileKey != null
                    markKeysFresh()
                    val result = adoptPasswords(source, file, opened, t0)
                    // The key file is not kept on the phone: the wrapped composite key replaces it.
                    if (keyFile == KeyFileChoice.Imported) deleteImportedKeyFile("setup done")
                    return result
                } finally {
                    opened.wipeKey()
                }
            }
            throw KdbxException(KdbxException.Reason.WRONG_KEY, "password or key file does not open the database")
        } finally {
            candidates.forEach { Bytes.wipe(it) }
            Bytes.wipe(keyFileKey)
        }
    }

    /**
     * Replaces the passwords' file with [source] if the stored key opens it
     * (the same file elsewhere, or a copy) or it needs none; throws
     * [PasswordNeededException] when it does not, and the setup then needs
     * the password.
     */
    suspend fun switchPasswordsSource(source: DbSource): SetupResult = withContext(Dispatchers.Default) {
        enforceForgetPolicy()
        val credential = keys.loadLabeled(KeyWrapper.Slot.KEEPASS)
        try {
            val file = Sources.read(appContext, source)
            try {
                val t0 = System.nanoTime()
                val opened = when (val kind = PasswordFiles.detect(file.data)) {
                    PasswordFiles.Kind.Kdbx -> credential.takeIf { it.label.contentEquals(CREDENTIAL_KDBX) }
                        ?.let { tryKdbx(file.data, KdbxHeader.parse(file.data), it.secret) }
                    is PasswordFiles.Kind.Encrypted -> credential.takeIf { it.label.contentEquals(CREDENTIAL_PASSWORD) }?.let {
                        try {
                            openEncrypted(kind, file.name, it.secret)
                        } catch (e: FileFormatException) {
                            if (e.reason != FileFormatException.Reason.WRONG_PASSWORD) throw e
                            null
                        }
                    }
                    is PasswordFiles.Kind.Plain -> Opened(kind.read(file.name), KIND_PLAIN).also { opened ->
                        val marker = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        try {
                            storeCredential(opened, marker, CREDENTIAL_NONE)
                        } finally {
                            Bytes.wipe(marker)
                        }
                        prefs.usesKeyFile = false
                    }
                } ?: throw PasswordNeededException("the stored key does not open this file")
                try {
                    val result = adoptPasswords(source, file, opened, t0)
                    Log.i(TAG, "passwords' file replaced by ${source.fileName} (${opened.kind})")
                    result
                } finally {
                    opened.wipeKey()
                }
            } finally {
                Bytes.wipe(file.data)
            }
        } finally {
            Bytes.wipe(credential.secret)
        }
    }

    /**
     * Sets up the 2FA codes: with [bytes], a newly picked encrypted Aegis
     * vault file (named [sourceName]) that becomes the vault's own file once
     * [password] has opened it; without, the current file again. Derives the
     * master key from the password slot (scrypt), checks it and stores it
     * wrapped.
     */
    suspend fun setupCodes(password: ByteArray, bytes: ByteArray? = null, sourceName: String = ""): SetupResult =
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val raw = bytes ?: AtomicFile(codesFile).readFully()
            val vault = AegisVault.parse(raw)
            if (!vault.encrypted) throw AegisException(AegisException.Reason.NO_PASSWORD_SLOT, "the vault file is not encrypted")
            val master = vault.unlockWithPassword(password)
            try {
                adoptCodes(vault.open(master), if (bytes != null) bytes else null, master, sourceName, t0, 0)
            } finally {
                Bytes.wipe(master)
            }
        }

    /**
     * Sets up the 2FA codes from the accounts of a file in any format
     * [CodeFiles] reads ([filePassword] opens an encrypted one): a new file
     * of the vault's own holds them, encrypted with [password] (the picked
     * file's own, or one chosen for a file without), and replaces the
     * current one.
     */
    suspend fun setupCodesFrom(bytes: ByteArray, sourceName: String, filePassword: ByteArray?, password: ByteArray): SetupResult =
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val detected = CodeFiles.detect(bytes)
            val accounts = detected.accounts(filePassword)
            val (file, master) = AegisVault.create(password, accounts.entries)
            try {
                adoptCodes(AegisVault.parse(file).open(master), file, master, sourceName, t0, accounts.skipped).also {
                    Log.i(TAG, "2FA codes set up from a ${detected.format} file: ${it.entries} accounts, ${accounts.skipped} not readable")
                }
            } finally {
                Bytes.wipe(master, file)
            }
        }

    /** Makes [entries] the shown 2FA codes, [file] (when new) the vault's own file and [master] its stored key. */
    private suspend fun adoptCodes(
        entries: List<AegisEntry>,
        file: ByteArray?,
        master: ByteArray,
        sourceName: String,
        t0: Long,
        skipped: Int,
    ): SetupResult {
        val data = CodesData(entries, System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
        try {
            if (file != null) writeCodesFile(file)
            keys.store(KeyWrapper.Slot.AEGIS, master)
        } catch (e: Throwable) {
            data.wipe()
            throw e
        }
        if (file != null) prefs.codesSourceName = sourceName
        markKeysFresh()
        prefs.putStatus(
            VaultPrefs.Source.CODES,
            VaultPrefs.LoadStatus(
                loadedAt = data.loadedAt, fileSize = codesFile.length(), fileMtime = codesFile.lastModified(),
                entries = data.entries.size, totalMillis = data.millis,
            ),
        )
        val result = SetupResult(data.entries.size, 0, data.millis, skipped)
        withContext(Dispatchers.Main.immediate) {
            codesEpoch++
            if (holders > 0 && prefs.codesEnabled) installCodes(data) else data.wipe()
        }
        Log.i(TAG, "2FA codes set up" + if (file != null) " from a new file" else "")
        return result
    }

    private val codesWrite = Mutex()

    /**
     * Changes the accounts of the 2FA codes ([what] names the change for
     * the log): decrypts the vault with the wrapped master key, applies
     * [change] to its entries, encrypts it again (fresh nonce; same master
     * key and slots, so the password and Aegis' import keep working), checks
     * that the result opens and replaces the file. Pages then show the new
     * entries.
     */
    suspend fun changeCodes(what: String, change: (MutableList<MutableMap<String, Any?>>) -> Unit) =
        withContext(Dispatchers.Default + NonCancellable) {
            codesWrite.withLock {
                val t0 = System.nanoTime()
                try {
                    enforceForgetPolicy()
                    val master = keys.load(KeyWrapper.Slot.AEGIS)
                    try {
                        val file = AegisVault.parse(AtomicFile(codesFile).readFully()).edit(master, change)
                        val data = CodesData(AegisVault.parse(file).open(master), System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
                        try {
                            writeCodesFile(file)
                        } catch (e: Throwable) {
                            data.wipe()
                            throw e
                        }
                        prefs.putStatus(
                            VaultPrefs.Source.CODES,
                            prefs.status(VaultPrefs.Source.CODES).copy(
                                fileSize = codesFile.length(), fileMtime = codesFile.lastModified(), entries = data.entries.size,
                            ),
                        )
                        withContext(Dispatchers.Main.immediate) {
                            codesEpoch++
                            if (holders > 0 && codesActive()) installCodes(data) else data.wipe()
                        }
                        Log.i(TAG, "2FA codes changed: $what (${data.entries.size} entries)")
                    } finally {
                        Bytes.wipe(master)
                    }
                } catch (e: Throwable) {
                    recordFailure(VaultPrefs.Source.CODES, "changing the 2FA codes ($what)", e)
                    throw e
                }
            }
        }

    /** Accounts of another file merged into the 2FA codes: added, read from the file, and not readable there. */
    class MergeResult(val added: Int, val total: Int, val skipped: Int)

    /**
     * Adds the accounts of another file in any format [CodeFiles] reads
     * ([password] for an encrypted one, null otherwise) that the 2FA codes
     * do not hold yet. The other file is only read; the 2FA codes keep their
     * password.
     */
    suspend fun importCodes(bytes: ByteArray, password: ByteArray?): MergeResult {
        val accounts = withContext(Dispatchers.Default) { CodeFiles.detect(bytes).accounts(password) }
        return addCodes(accounts)
    }

    /** Adds [accounts] (in the vault file's shape) that the 2FA codes do not hold yet. */
    suspend fun addCodes(accounts: CodeFiles.Accounts): MergeResult {
        var added = 0
        changeCodes("accounts imported") { entries -> added = AegisVault.merge(entries, accounts.entries) }
        return MergeResult(added, accounts.entries.size, accounts.skipped)
    }

    private fun writeCodesFile(bytes: ByteArray) {
        val f = AtomicFile(codesFile)
        val out = f.startWrite()
        try {
            out.write(bytes)
            f.finishWrite(out)
        } catch (e: java.io.IOException) {
            f.failWrite(out)
            throw e
        }
    }

    /**
     * "Forget keys": wipes the session, deletes the wrapped keys and the
     * Keystore key; the vault files and the chosen database stay. Main thread.
     */
    fun forgetKeys(reason: String = "on request") {
        drop("forget keys")
        keys.forgetAll()
        prefs.clearSetup()
        _state.value = idleState()
        Log.i(TAG, "keys forgotten ($reason)")
    }

    private fun recordPasswords(data: PasswordData) {
        prefs.putStatus(
            VaultPrefs.Source.PASSWORDS,
            VaultPrefs.LoadStatus(
                loadedAt = data.loadedAt, fileSize = data.size, fileMtime = data.mtime, sha256 = data.sha256,
                entries = data.db.entries.size, format = data.db.format,
                kdfMillis = if (prefs.passwordsKind == KIND_PLAIN) -1 else data.timing.kdfMs, totalMillis = data.timing.totalMs,
            ),
        )
        if (data.timing.cold) {
            Log.i(TAG, "passwords opened: ${data.db.entries.size} entries, ${data.db.format}, " +
                "KDF ${data.timing.kdfMs} ms, total ${data.timing.totalMs} ms")
        }
    }

    private fun recordFailure(source: VaultPrefs.Source, what: String, e: Throwable) {
        if (e is KeysForgottenException) {
            Log.i(TAG, "$what refused: the keys were forgotten by the forget policy")
            return
        }
        if (e is KeystoreLockedException) {
            // The expected result while the phone is locked; the Keystore did its job.
            Log.i(TAG, "$what refused by the Keystore${if (deviceLocked) " (phone locked)" else ""}")
            if (!deviceLocked) recordError(source, e)
            return
        }
        // Messages of our exceptions name formats and reasons, never values.
        Log.e(TAG, "$what failed: ${e.javaClass.simpleName}: ${e.message}", e)
        recordError(source, e)
    }

    /** The state page shows the error as the pages do (localized, without internal names); logcat has the details. */
    private fun recordError(source: VaultPrefs.Source, e: Throwable) {
        prefs.putStatus(source, prefs.status(source).copy(error = Errors.describe(appContext, e), errorAt = System.currentTimeMillis()))
    }
}
