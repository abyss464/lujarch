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
import org.lujarch.vault.core.KdbxDatabase
import org.lujarch.vault.core.KeystoreRefusal
import org.lujarch.vault.core.aegis.AegisEntry
import org.lujarch.vault.core.aegis.AegisException
import org.lujarch.vault.core.aegis.AegisVault
import org.lujarch.vault.core.kdbx.KdbxException
import org.lujarch.vault.core.kdbx.KdbxHeader
import org.lujarch.vault.core.kdbx.KdbxReader
import org.lujarch.vault.core.kdbx.KeyFile
import org.lujarch.vault.ui.Errors

/** Where the time of one KeePass open went, in milliseconds. */
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
 * The decrypted KeePass database of one access and the facts of its file.
 * Whoever opened it wipes it ([wipe] is idempotent); [Vault.liveCount]
 * counts those not wiped yet.
 */
class KeePassData(
    val db: KdbxDatabase,
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

class AegisData(val entries: List<AegisEntry>, val loadedAt: Long, val millis: Long) {
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

/** What a setup reports; the decrypted data itself goes to the page session or is wiped. */
data class SetupResult(val entries: Int, val kdfMillis: Long, val totalMillis: Long)

/** State of one source (KeePass database or Aegis vault) in the page session. */
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
    val keepass: Source<KeePassData>,
    val aegis: Source<AegisData>,
    /** A changed database is being loaded while the previous one stays shown. */
    val reloading: Boolean = false,
) {
    val loading: Boolean
        get() = keepass is Source.Loading || aegis is Source.Loading

    val keepassData: KeePassData?
        get() = (keepass as? Source.Ready)?.data

    val aegisData: AegisData?
        get() = (aegis as? Source.Ready)?.data

    val anySetUp: Boolean
        get() = keepass !is Source.NotSetUp || aegis !is Source.NotSetUp

    /** A source set up and turned on. */
    val anyActive: Boolean
        get() = keepass.active || aegis.active
}

/** Set up and turned on. */
val Source<*>.active: Boolean
    get() = this !is Source.NotSetUp && this !is Source.Off

/** The key file a KeePass setup uses (VLT-1). */
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
 * - Every access unwraps a key first (the cached transformed key, the
 *   composite key, the Aegis master key). While the phone is locked the
 *   Keystore refuses, whatever state this process is in (frozen, cached,
 *   never told about the lock).
 * - Pages hold the decrypted vault in a session: the first visible page
 *   opens it ([acquire] from onStart), the last one to stop drops and wipes
 *   it ([release] from onStop), so leaving the app or turning the screen off
 *   wipes it through the activity lifecycle, not through broadcasts.
 * - An autofill request opens the database for itself ([withKeePass]) and
 *   wipes it when its response is built.
 * - The Argon2 output is cached wrapped (slot TRANSFORMED, labeled with the
 *   KDF fingerprint): an access costs an unwrap, a read and a parse; the KDF
 *   runs again only after the seed or parameters changed (a save on the PC).
 * - The database is read from its source on every open: a synced folder
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
    const val AEGIS_FILE = "aegis.json"

    /** One-time import of the KeePass key file (`fos vault-keyfile`); deleted after a successful setup. */
    const val KEY_FILE_IMPORT = "keyfile.import"
    private const val CHANGE_CHECK_INTERVAL_MS = 10_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Largest key file read from a document; KeePass hashes files of any other shape whole. */
    const val MAX_KEY_FILE_BYTES = 16 * 1024 * 1024

    /** Decrypted KeePass and Aegis objects not wiped yet (module tests observe it). */
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
    private var keepassEpoch = 0L
    private var aegisEpoch = 0L
    private var keepassJob: Job? = null
    private var keepassJobEpoch = -1L
    private var aegisJob: Job? = null
    private var aegisJobEpoch = -1L
    private var lastChangeCheck = 0L
    private var initialized = false

    /** The source's last open was refused by the Keystore on an unlocked phone and is being retried. */
    private var keepassRetried = false
    private var aegisRetried = false

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

    val aegisFile: File
        get() = File(appContext.filesDir, AEGIS_FILE)

    val importedKeyFile: File
        get() = File(appContext.filesDir, KEY_FILE_IMPORT)

    fun keePassSetUp(): Boolean = keys.has(KeyWrapper.Slot.KEEPASS) && prefs.keepassSource != null

    fun aegisSetUp(): Boolean = keys.has(KeyWrapper.Slot.AEGIS) && aegisFile.isFile

    /** Set up and turned on: opened for pages, codes and autofill. */
    fun keePassActive(): Boolean = keePassSetUp() && prefs.keepassEnabled

    fun aegisActive(): Boolean = aegisSetUp() && prefs.aegisEnabled

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

    private fun keePassIdle() = idle(keePassSetUp(), prefs.keepassEnabled)

    private fun aegisIdle() = idle(aegisSetUp(), prefs.aegisEnabled)

    private fun idleState() = VaultState(keePassIdle(), aegisIdle())

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
     * Opens the KeePass database for one access; the caller wipes the result.
     * Order: the forget policy, the Keystore (a locked phone stops here), the
     * file from its source, then the cached transformed key if its KDF
     * fingerprint matches the file's, else the composite key and the KDF,
     * whose output replaces the cache.
     */
    suspend fun openKeePass(): KeePassData = withContext(Dispatchers.Default + NonCancellable) {
        enforceForgetPolicy()
        val source = prefs.keepassSource ?: throw IllegalStateException("KeePass is not set up")
        val t0 = System.nanoTime()
        val cached = if (keys.has(KeyWrapper.Slot.TRANSFORMED)) keys.loadLabeled(KeyWrapper.Slot.TRANSFORMED) else null
        val composite = if (cached == null) keys.load(KeyWrapper.Slot.KEEPASS) else null
        val t1 = System.nanoTime()
        var transformed: ByteArray? = null
        var compositeKey = composite
        try {
            val file = Sources.read(appContext, source)
            val t2 = System.nanoTime()
            try {
                val header = KdbxHeader.parse(file.data)
                val fingerprint = header.kdfFingerprint()
                var kdfNs = 0L
                var cold = false
                var db: KdbxDatabase? = null
                if (cached != null && Bytes.constantTimeEquals(cached.label, fingerprint)) {
                    transformed = cached.secret
                    try {
                        db = KdbxReader.openWithKey(file.data, header, cached.secret)
                    } catch (e: KdbxException) {
                        // A cache left from an earlier composite key: derive again.
                        if (e.reason != KdbxException.Reason.WRONG_KEY) throw e
                        Log.w(TAG, "cached transformed key does not open the database; deriving it again")
                    }
                } else {
                    cached?.secret?.let { Bytes.wipe(it) }
                }
                if (db == null) {
                    Bytes.wipe(transformed)
                    val c = compositeKey ?: keys.load(KeyWrapper.Slot.KEEPASS).also { compositeKey = it }
                    val k0 = System.nanoTime()
                    transformed = KdbxReader.transformKey(header.kdf, c)
                    kdfNs = System.nanoTime() - k0
                    cold = true
                    db = KdbxReader.openWithKey(file.data, header, transformed!!)
                    keys.store(KeyWrapper.Slot.TRANSFORMED, transformed!!, fingerprint)
                    Log.i(TAG, "KDF ran (${kdfNs / 1_000_000} ms); transformed key cached for the new parameters")
                }
                val t3 = System.nanoTime()
                val timing = LoadTiming(
                    unwrapMs = (t1 - t0) / 1_000_000,
                    readMs = (t2 - t1) / 1_000_000,
                    kdfMs = kdfNs / 1_000_000,
                    openMs = (t3 - t2 - kdfNs) / 1_000_000,
                    totalMs = (t3 - t0) / 1_000_000,
                    cold = cold,
                )
                val data = KeePassData(db, source, file.name, file.size, file.mtime, file.sha256, timing, System.currentTimeMillis())
                recordKeePass(data)
                prefs.lastUsedAt = data.loadedAt
                data
            } finally {
                Bytes.wipe(file.data)
            }
        } catch (e: Throwable) {
            recordFailure(VaultPrefs.Source.KEEPASS, "opening the KeePass database", e)
            throw e
        } finally {
            Bytes.wipe(transformed, compositeKey)
        }
    }

    /** Opens the Aegis vault for one access with the wrapped master key; the caller wipes the result. */
    suspend fun openAegis(): AegisData = withContext(Dispatchers.Default + NonCancellable) {
        val t0 = System.nanoTime()
        try {
            enforceForgetPolicy()
            val master = keys.load(KeyWrapper.Slot.AEGIS)
            try {
                val bytes = AtomicFile(aegisFile).readFully()
                val entries = AegisVault.parse(bytes).open(master)
                val data = AegisData(entries, System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
                prefs.putStatus(
                    VaultPrefs.Source.AEGIS,
                    VaultPrefs.LoadStatus(
                        loadedAt = data.loadedAt, fileSize = aegisFile.length(), fileMtime = aegisFile.lastModified(),
                        entries = entries.size, format = "Aegis", totalMillis = data.millis,
                    ),
                )
                prefs.lastUsedAt = data.loadedAt
                data
            } finally {
                Bytes.wipe(master)
            }
        } catch (e: Throwable) {
            recordFailure(VaultPrefs.Source.AEGIS, "opening the Aegis vault", e)
            throw e
        }
    }

    /**
     * Opens the database for [block] and wipes it afterwards (autofill: one
     * request, one open). The open itself is not cancellable, so a cancelled
     * caller still wipes what was decrypted.
     */
    suspend fun <T> withKeePass(block: suspend (KeePassData) -> T): T {
        val data = openKeePass()
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
        if (keePassActive()) {
            if (s.keepass !is Source.Ready && (keepassJob?.isActive != true || keepassJobEpoch != keepassEpoch)) {
                startKeePass(reason, reload = false)
            }
        } else {
            settleKeePass()
        }
        if (aegisActive()) {
            if (s.aegis !is Source.Ready && (aegisJob?.isActive != true || aegisJobEpoch != aegisEpoch)) {
                startAegis(reason)
            }
        } else {
            settleAegis()
        }
    }

    /** KeePass is not active: its state becomes Off or NotSetUp, and whatever was shown is wiped. */
    private fun settleKeePass() {
        val want = keePassIdle()
        val s = _state.value
        if (s.keepass === want) return
        keepassEpoch++
        update { copy(keepass = want, reloading = false) }
        s.keepassData?.wipe()
    }

    private fun settleAegis() {
        val want = aegisIdle()
        val s = _state.value
        if (s.aegis === want) return
        aegisEpoch++
        update { copy(aegis = want) }
        s.aegisData?.wipe()
    }

    /** Turns KeePass on or off; off wipes what the session shows. Main thread. */
    fun setKeePassEnabled(on: Boolean) {
        prefs.keepassEnabled = on
        if (on) {
            if (_state.value.keepass === Source.Off) update { copy(keepass = keePassIdle()) }
            requestLoad("KeePass turned on")
        } else {
            settleKeePass()
        }
        Log.i(TAG, "KeePass turned ${if (on) "on" else "off"}")
    }

    fun setAegisEnabled(on: Boolean) {
        prefs.aegisEnabled = on
        if (on) {
            if (_state.value.aegis === Source.Off) update { copy(aegis = aegisIdle()) }
            requestLoad("Aegis turned on")
        } else {
            settleAegis()
        }
        Log.i(TAG, "Aegis turned ${if (on) "on" else "off"}")
    }

    private fun startKeePass(reason: String, reload: Boolean) {
        val epoch = keepassEpoch
        Log.i(TAG, "opening the KeePass database ($reason)")
        if (reload) update { copy(reloading = true) } else update { copy(keepass = Source.Loading) }
        keepassJobEpoch = epoch
        keepassJob = scope.launch {
            val result = try {
                Result.success(openKeePass())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (epoch != keepassEpoch || holders == 0) {
                // The session was dropped (or KeePass set up again) meanwhile.
                result.getOrNull()?.wipe()
                return@launch
            }
            result.fold(
                onSuccess = {
                    keepassRetried = false
                    installKeePass(it)
                },
                onFailure = { e ->
                    val refusal = (e as? KeystoreLockedException)?.let { KeystoreRefusal.next(deviceLocked, keepassRetried) }
                    when {
                        e is KeysForgottenException -> Unit // forgetKeys has set the state
                        reload -> update { copy(reloading = false) }
                        refusal == null || refusal == KeystoreRefusal.Next.FAIL -> update { copy(keepass = Source.Failed(e)) }
                        else -> {
                            // Locked: the source waits (pages say "Locked"); unlocked: one retry.
                            keepassRetried = refusal == KeystoreRefusal.Next.RETRY
                            update { copy(keepass = if (keepassRetried) Source.Loading else keePassIdle()) }
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

    private fun installKeePass(data: KeePassData) {
        val old = _state.value.keepassData
        update { copy(keepass = Source.Ready(data), reloading = false) }
        if (old != null && old !== data) old.wipe()
    }

    private fun installAegis(data: AegisData) {
        val old = _state.value.aegisData
        update { copy(aegis = Source.Ready(data)) }
        if (old != null && old !== data) old.wipe()
    }

    private fun startAegis(reason: String) {
        val epoch = aegisEpoch
        Log.i(TAG, "opening the Aegis vault ($reason)")
        update { copy(aegis = Source.Loading) }
        aegisJobEpoch = epoch
        aegisJob = scope.launch {
            val result = try {
                Result.success(openAegis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (epoch != aegisEpoch || holders == 0) {
                result.getOrNull()?.wipe()
                return@launch
            }
            result.fold(
                onSuccess = {
                    aegisRetried = false
                    installAegis(it)
                },
                onFailure = { e ->
                    val refusal = (e as? KeystoreLockedException)?.let { KeystoreRefusal.next(deviceLocked, aegisRetried) }
                    when {
                        e is KeysForgottenException -> Unit
                        refusal == null || refusal == KeystoreRefusal.Next.FAIL -> update { copy(aegis = Source.Failed(e)) }
                        else -> {
                            aegisRetried = refusal == KeystoreRefusal.Next.RETRY
                            update { copy(aegis = if (aegisRetried) Source.Loading else aegisIdle()) }
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
        val data = _state.value.keepassData ?: return
        val now = System.currentTimeMillis()
        if (now - lastChangeCheck < CHANGE_CHECK_INTERVAL_MS || keepassJob?.isActive == true) return
        lastChangeCheck = now
        val epoch = keepassEpoch
        scope.launch {
            val stat = try {
                Sources.stat(appContext, data.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "checking the KeePass database for changes failed; keeping the shown copy: ${e.message}")
                return@launch
            }
            if (epoch != keepassEpoch || _state.value.keepassData !== data) return@launch
            // A provider that reports no size or time leaves the check to the next open.
            val sizeChanged = stat.size >= 0 && stat.size != data.size
            val timeChanged = stat.mtime > 0 && stat.mtime != data.mtime
            if ((sizeChanged || timeChanged) && keepassJob?.isActive != true) {
                startKeePass("file changed", reload = true)
            }
        }
    }

    /** Wipes the session's decrypted data; main thread. */
    private fun drop(reason: String) {
        keepassEpoch++
        aegisEpoch++
        keepassRetried = false
        aegisRetried = false
        unlockWatch?.cancel()
        unlockWatch = null
        val s = _state.value
        val had = s.keepassData != null || s.aegisData != null
        _state.value = idleState()
        s.keepassData?.wipe()
        s.aegisData?.wipe()
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

    /** The database opened with [composite]; null when the key does not open it. The caller wipes both. */
    private class Opened(val db: KdbxDatabase, val transformed: ByteArray, val kdfMs: Long)

    private fun tryOpen(file: ByteArray, header: KdbxHeader, composite: ByteArray): Opened? {
        val k0 = System.nanoTime()
        val transformed = KdbxReader.transformKey(header.kdf, composite)
        val kdfMs = (System.nanoTime() - k0) / 1_000_000
        return try {
            Opened(KdbxReader.openWithKey(file, header, transformed), transformed, kdfMs)
        } catch (e: KdbxException) {
            Bytes.wipe(transformed)
            if (e.reason != KdbxException.Reason.WRONG_KEY) throw e
            null
        } catch (e: Throwable) {
            Bytes.wipe(transformed)
            throw e
        }
    }

    /**
     * Makes [source] the database: stores the transformed key for it, the
     * source, releases grants of documents no longer used, and hands the
     * opened database to a visible page (or wipes it).
     */
    private suspend fun adoptKeePass(source: DbSource, file: FileContent, header: KdbxHeader, opened: Opened, t0: Long): SetupResult {
        val data = try {
            keys.store(KeyWrapper.Slot.TRANSFORMED, opened.transformed, header.kdfFingerprint())
            val total = (System.nanoTime() - t0) / 1_000_000
            KeePassData(
                opened.db, source, file.name, file.size, file.mtime, file.sha256,
                LoadTiming(0, 0, opened.kdfMs, total - opened.kdfMs, total, cold = true), System.currentTimeMillis(),
            )
        } catch (e: Throwable) {
            opened.db.wipe()
            throw e
        }
        prefs.keepassSource = source
        Sources.releaseGrantsExcept(appContext, (source as? DbSource.Document)?.uri)
        recordKeePass(data)
        val result = SetupResult(data.db.entries.size, data.timing.kdfMs, data.timing.totalMs)
        withContext(Dispatchers.Main.immediate) {
            keepassEpoch++
            if (holders > 0 && prefs.keepassEnabled) installKeePass(data) else data.wipe()
        }
        return result
    }

    /**
     * Opens [source] with the password and key file (running the KDF once to
     * prove them right), then stores the wrapped composite key and the
     * wrapped transformed key, and deletes an imported key file. An empty
     * password means no password component; like KeePassXC, an empty
     * password is tried as well when that fails and a key file is used.
     */
    suspend fun setupKeePass(source: DbSource, keyFile: KeyFileChoice, password: ByteArray): SetupResult =
        withContext(Dispatchers.Default) {
            val keyFileKey = when (keyFile) {
                KeyFileChoice.None -> null
                KeyFileChoice.Imported -> importedKeyFileKey()
                is KeyFileChoice.Picked -> keyFile.key.copyOf()
            }
            val file = Sources.read(appContext, source)
            val candidates = ArrayList<ByteArray>()
            try {
                if (password.isNotEmpty() || keyFileKey == null) {
                    candidates.add(KeyFile.compositeKey(password, keyFileKey))
                } else {
                    candidates.add(KeyFile.compositeKey(null, keyFileKey))
                    candidates.add(KeyFile.compositeKey(ByteArray(0), keyFileKey))
                }
                val header = KdbxHeader.parse(file.data)
                val t0 = System.nanoTime()
                for (composite in candidates) {
                    val opened = tryOpen(file.data, header, composite) ?: continue
                    try {
                        try {
                            keys.store(KeyWrapper.Slot.KEEPASS, composite)
                        } catch (e: Throwable) {
                            opened.db.wipe()
                            throw e
                        }
                        prefs.usesKeyFile = keyFileKey != null
                        markKeysFresh()
                        val result = adoptKeePass(source, file, header, opened, t0)
                        // The key file is not kept on the phone: the wrapped composite key replaces it.
                        if (keyFile == KeyFileChoice.Imported) deleteImportedKeyFile("setup done")
                        Log.i(TAG, "KeePass set up with ${source.fileName}" + if (keyFileKey != null) " and a key file" else "")
                        return@withContext result
                    } finally {
                        Bytes.wipe(opened.transformed)
                    }
                }
                throw KdbxException(KdbxException.Reason.WRONG_KEY, "password or key file does not open the database")
            } finally {
                candidates.forEach { Bytes.wipe(it) }
                Bytes.wipe(keyFileKey, file.data)
            }
        }

    /**
     * Replaces the database of a set-up KeePass with [source] if the stored
     * composite key opens it (the same database elsewhere, or a copy);
     * throws KdbxException(WRONG_KEY) when it does not, and the setup then
     * needs the password.
     */
    suspend fun switchKeePassSource(source: DbSource): SetupResult = withContext(Dispatchers.Default) {
        enforceForgetPolicy()
        val composite = keys.load(KeyWrapper.Slot.KEEPASS)
        try {
            val file = Sources.read(appContext, source)
            try {
                val header = KdbxHeader.parse(file.data)
                val t0 = System.nanoTime()
                val opened = tryOpen(file.data, header, composite)
                    ?: throw KdbxException(KdbxException.Reason.WRONG_KEY, "the stored key does not open this database")
                try {
                    val result = adoptKeePass(source, file, header, opened, t0)
                    Log.i(TAG, "KeePass database replaced by ${source.fileName} (stored key)")
                    result
                } finally {
                    Bytes.wipe(opened.transformed)
                }
            } finally {
                Bytes.wipe(file.data)
            }
        } finally {
            Bytes.wipe(composite)
        }
    }

    /**
     * Sets up Aegis: with [bytes], a newly picked vault file (named
     * [sourceName]) that replaces the current one only once [password] has
     * opened it; without, the current file. Derives the master key from the
     * password slot (scrypt), checks it and stores it wrapped.
     */
    suspend fun setupAegis(password: ByteArray, bytes: ByteArray? = null, sourceName: String = ""): SetupResult =
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val raw = bytes ?: AtomicFile(aegisFile).readFully()
            val vault = AegisVault.parse(raw)
            if (!vault.encrypted) throw AegisException(AegisException.Reason.NO_PASSWORD_SLOT, "the vault file is not encrypted")
            val master = vault.unlockWithPassword(password)
            try {
                val data = AegisData(vault.open(master), System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
                try {
                    if (bytes != null) writeAegisFile(bytes)
                    keys.store(KeyWrapper.Slot.AEGIS, master)
                } catch (e: Throwable) {
                    data.wipe()
                    throw e
                }
                if (bytes != null) prefs.aegisSourceName = sourceName
                markKeysFresh()
                prefs.putStatus(
                    VaultPrefs.Source.AEGIS,
                    VaultPrefs.LoadStatus(
                        loadedAt = data.loadedAt, fileSize = aegisFile.length(), fileMtime = aegisFile.lastModified(),
                        entries = data.entries.size, format = "Aegis", totalMillis = data.millis,
                    ),
                )
                val result = SetupResult(data.entries.size, 0, data.millis)
                withContext(Dispatchers.Main.immediate) {
                    aegisEpoch++
                    if (holders > 0 && prefs.aegisEnabled) installAegis(data) else data.wipe()
                }
                Log.i(TAG, "Aegis vault set up" + if (bytes != null) " from a new file (${bytes.size} bytes)" else "")
                result
            } finally {
                Bytes.wipe(master)
            }
        }

    private val aegisWrite = Mutex()

    /**
     * Changes the accounts of the Aegis vault ([what] names the change for
     * the log): decrypts the vault with the wrapped master key, applies
     * [change] to its entries, encrypts it again (fresh nonce; same master
     * key and slots, so the password and Aegis' import keep working), checks
     * that the result opens and replaces the file. Pages then show the new
     * entries.
     */
    suspend fun changeAegis(what: String, change: (MutableList<MutableMap<String, Any?>>) -> Unit) =
        withContext(Dispatchers.Default + NonCancellable) {
            aegisWrite.withLock {
                val t0 = System.nanoTime()
                try {
                    enforceForgetPolicy()
                    val master = keys.load(KeyWrapper.Slot.AEGIS)
                    try {
                        val file = AegisVault.parse(AtomicFile(aegisFile).readFully()).edit(master, change)
                        val data = AegisData(AegisVault.parse(file).open(master), System.currentTimeMillis(), (System.nanoTime() - t0) / 1_000_000)
                        try {
                            writeAegisFile(file)
                        } catch (e: Throwable) {
                            data.wipe()
                            throw e
                        }
                        prefs.putStatus(
                            VaultPrefs.Source.AEGIS,
                            prefs.status(VaultPrefs.Source.AEGIS).copy(
                                fileSize = aegisFile.length(), fileMtime = aegisFile.lastModified(), entries = data.entries.size,
                            ),
                        )
                        withContext(Dispatchers.Main.immediate) {
                            aegisEpoch++
                            if (holders > 0 && aegisActive()) installAegis(data) else data.wipe()
                        }
                        Log.i(TAG, "Aegis vault changed: $what (${data.entries.size} entries)")
                    } finally {
                        Bytes.wipe(master)
                    }
                } catch (e: Throwable) {
                    recordFailure(VaultPrefs.Source.AEGIS, "changing the Aegis vault ($what)", e)
                    throw e
                }
            }
        }

    /** Accounts of another Aegis vault file merged into this one. */
    class MergeResult(val added: Int, val total: Int)

    /**
     * Adds the accounts of another Aegis vault file ([bytes]; [password]
     * for an encrypted one, null for an unencrypted export) that this vault
     * does not hold yet. The other file is only read; this vault keeps its
     * password.
     */
    suspend fun mergeAegis(bytes: ByteArray, password: ByteArray?): MergeResult {
        val incoming = withContext(Dispatchers.Default) {
            val other = AegisVault.parse(bytes)
            if (other.encrypted) {
                val master = other.unlockWithPassword(password ?: throw AegisException(AegisException.Reason.WRONG_PASSWORD, "no password"))
                try {
                    other.rawEntries(master)
                } finally {
                    Bytes.wipe(master)
                }
            } else {
                other.rawEntries(null)
            }
        }
        var added = 0
        changeAegis("accounts imported") { entries -> added = AegisVault.merge(entries, incoming) }
        return MergeResult(added, incoming.size)
    }

    private fun writeAegisFile(bytes: ByteArray) {
        val f = AtomicFile(aegisFile)
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

    private fun recordKeePass(data: KeePassData) {
        val info = data.db.info
        prefs.putStatus(
            VaultPrefs.Source.KEEPASS,
            VaultPrefs.LoadStatus(
                loadedAt = data.loadedAt, fileSize = data.size, fileMtime = data.mtime, sha256 = data.sha256,
                entries = data.db.entries.size,
                format = "KDBX ${info.version} · ${info.cipher} · ${info.kdf.name}",
                kdfMillis = data.timing.kdfMs, totalMillis = data.timing.totalMs,
            ),
        )
        if (data.timing.cold) {
            Log.i(TAG, "KeePass database opened: ${data.db.entries.size} entries, KDBX ${info.version}, " +
                "${info.kdf.name}, KDF ${data.timing.kdfMs} ms, total ${data.timing.totalMs} ms")
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
