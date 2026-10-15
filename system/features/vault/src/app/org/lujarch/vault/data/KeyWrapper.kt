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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.lujarch.vault.TAG
import org.lujarch.vault.core.Bytes

/** The Keystore refused the key: the phone is locked (or the key is gone). */
class KeystoreLockedException(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * Wraps the KeePass composite key, the KeePass transformed key (Argon2
 * output) and the Aegis master key with one Android Keystore key
 * (AES-256-GCM, setUnlockedDeviceRequired, StrongBox when the phone has it).
 * The Keystore enforces that the key only works while the phone is unlocked;
 * no user authentication per use (VLT-1). Every access to
 * the vault goes through [load], so a locked phone stops it here.
 *
 * Blob layout: version (2), label length, label, IV length, IV, GCM
 * ciphertext and tag. The label is plaintext but authenticated: slot name
 * and label are the AAD, so a blob cannot be swapped into another slot or
 * given another label. The transformed key's label is the KDF fingerprint.
 */
class KeyWrapper(context: Context) {
    enum class Slot(val file: String) {
        KEEPASS("keepass.key"),
        TRANSFORMED("keepass-transformed.key"),
        AEGIS("aegis.key"),
    }

    /** An unwrapped secret with its label; the caller wipes [secret]. */
    class Labeled(val label: ByteArray, val secret: ByteArray)

    private val dir = File(context.noBackupFilesDir, "keys")
    private val hasStrongBox =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    fun has(slot: Slot): Boolean = File(dir, slot.file).isFile

    fun store(slot: Slot, secret: ByteArray, label: ByteArray = ByteArray(0)) {
        require(label.size <= 255) { "label too long" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val sealed = try {
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad(slot, label))
            cipher.doFinal(secret)
        } catch (e: Exception) {
            // Keystore failures surface as checked and as runtime (ProviderException) exceptions.
            throw KeystoreLockedException("Keystore refused encryption: ${e.javaClass.simpleName}", e)
        }
        val iv = cipher.iv
        val blob = ByteArray(3 + label.size + iv.size + sealed.size)
        blob[0] = VERSION
        blob[1] = label.size.toByte()
        label.copyInto(blob, 2)
        blob[2 + label.size] = iv.size.toByte()
        iv.copyInto(blob, 3 + label.size)
        sealed.copyInto(blob, 3 + label.size + iv.size)
        dir.mkdirs()
        val file = AtomicFile(File(dir, slot.file))
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: java.io.IOException) {
            file.failWrite(out)
            throw e
        }
    }

    /** The unwrapped secret; the caller wipes it. */
    fun load(slot: Slot): ByteArray = loadLabeled(slot).secret

    /** The unwrapped secret and its label; throws when the slot is empty or the Keystore refuses. */
    fun loadLabeled(slot: Slot): Labeled {
        val blob = try {
            AtomicFile(File(dir, slot.file)).readFully()
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("${slot.name} is not set up", e)
        }
        if (blob.size < 3 || blob[0] != VERSION) throw IllegalStateException("${slot.name} key blob has an unknown format")
        val labelLen = blob[1].toInt() and 0xff
        if (3 + labelLen >= blob.size) throw IllegalStateException("${slot.name} key blob is truncated")
        val label = blob.copyOfRange(2, 2 + labelLen)
        val ivLen = blob[2 + labelLen].toInt() and 0xff
        val ctStart = 3 + labelLen + ivLen
        if (ivLen == 0 || ctStart >= blob.size) throw IllegalStateException("${slot.name} key blob is truncated")
        val key = key(create = false) ?: throw KeystoreLockedException("Keystore key is missing", null)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 3 + labelLen, ivLen))
            cipher.updateAAD(aad(slot, label))
            return Labeled(label, cipher.doFinal(blob, ctStart, blob.size - ctStart))
        } catch (e: Exception) {
            // A locked phone shows up here (setUnlockedDeviceRequired); so would a replaced key.
            throw KeystoreLockedException("Keystore refused decryption: ${e.javaClass.simpleName}", e)
        } finally {
            Bytes.wipe(blob)
        }
    }

    private fun aad(slot: Slot, label: ByteArray): ByteArray =
        org.lujarch.vault.core.Bytes.concat(slot.name.toByteArray(), byteArrayOf(0), label)

    fun delete(slot: Slot) {
        AtomicFile(File(dir, slot.file)).delete()
    }

    /** Deletes both blobs and the Keystore key ("Forget keys"). */
    fun forgetAll() {
        Slot.entries.forEach { delete(it) }
        try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "deleting the Keystore key failed", e)
        }
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        if (hasStrongBox) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.w(TAG, "StrongBox unavailable, using the TEE Keystore")
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(spec)
        val key = gen.generateKey()
        Log.i(TAG, "Keystore wrapping key created (StrongBox: $strongBox)")
        return key
    }

    /** Where the wrapping key lives (StrongBox, TEE, software), for the state page; null without a key. */
    fun securityLevel(): Int? = try {
        val k = key(create = false) ?: return null
        val info = javax.crypto.SecretKeyFactory.getInstance(k.algorithm, KEYSTORE)
            .getKeySpec(k, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        info.securityLevel
    } catch (e: Exception) {
        Log.w(TAG, "reading the Keystore key info failed", e)
        null
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "lujarch_vault_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 2
    }
}
