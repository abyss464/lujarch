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

package org.lujarch.vault.core.otp

import java.net.URLDecoder
import java.util.Base64
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PasswordEntry
import org.lujarch.vault.core.Secret

enum class OtpType { TOTP, HOTP, STEAM }

/**
 * One OTP generator. [secret] holds the raw key bytes (not Base32) so it can
 * be wiped with the vault.
 */
class OtpParams(
    val type: OtpType,
    val secret: Secret,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val counter: Long,
    val issuer: String,
    val account: String,
) {
    fun wipe() = secret.wipe()
}

class InvalidOtp(message: String) : Exception(message)

object Otp {
    private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"
    private const val STEAM_SCHEME = "steam://"

    /** The code at [timeMillis] (TOTP, Steam) or for the stored counter (HOTP). */
    fun code(p: OtpParams, timeMillis: Long): String {
        val counter = when (p.type) {
            OtpType.HOTP -> p.counter
            else -> Math.floorDiv(timeMillis / 1000, p.period.toLong())
        }
        val key = p.secret.revealBytes()
        try {
            return generate(key, counter, p.algorithm, p.digits, p.type == OtpType.STEAM)
        } finally {
            Bytes.wipe(key)
        }
    }

    /** Milliseconds until the current TOTP code changes. */
    fun millisLeft(p: OtpParams, timeMillis: Long): Long {
        val periodMs = p.period * 1000L
        return periodMs - Math.floorMod(timeMillis, periodMs)
    }

    /** RFC 4226 HOTP value for [counter]; Steam uses its own 5-character alphabet. */
    fun generate(key: ByteArray, counter: Long, algorithm: String, digits: Int, steam: Boolean = false): String {
        val mac = when (algorithm.uppercase().replace("-", "")) {
            "SHA1", "HMACSHA1" -> "HmacSHA1"
            "SHA256", "HMACSHA256" -> "HmacSHA256"
            "SHA512", "HMACSHA512" -> "HmacSHA512"
            else -> throw InvalidOtp("unsupported algorithm $algorithm")
        }
        val h = Bytes.hmac(mac, if (key.isEmpty()) ByteArray(1) else key, Bytes.u64beBytes(counter))
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)
        Bytes.wipe(h)
        if (steam) {
            var v = bin
            val sb = StringBuilder()
            repeat(digits) {
                sb.append(STEAM_ALPHABET[v % STEAM_ALPHABET.length])
                v /= STEAM_ALPHABET.length
            }
            return sb.toString()
        }
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (bin % mod).toString().padStart(digits, '0')
    }

    /**
     * otpauth://totp|hotp|steam/Label?secret=...&issuer=&algorithm=&digits=&period=&counter=
     * as KeePassXC, KeePassDX and Aegis write it; `encoder=steam` marks Steam
     * codes in KeePassXC and KeePassDX.
     */
    fun parseUri(uri: String): OtpParams {
        val t = uri.trim()
        if (!t.startsWith("otpauth://", ignoreCase = true)) throw InvalidOtp("not an otpauth URI")
        val rest = t.substring("otpauth://".length)
        val slash = rest.indexOf('/')
        if (slash < 0) throw InvalidOtp("otpauth URI without label")
        val typeName = rest.substring(0, slash).lowercase()
        val q = rest.indexOf('?', slash)
        val label = decode(if (q < 0) rest.substring(slash + 1) else rest.substring(slash + 1, q))
        val params = HashMap<String, String>()
        if (q >= 0) {
            for (part in rest.substring(q + 1).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val k = decode(if (eq < 0) part else part.substring(0, eq)).lowercase()
                // A query value takes '+' for a space, as Android's Uri reads it (Ente Auth writes them so).
                val v = if (eq < 0) "" else decode(part.substring(eq + 1).replace('+', ' '))
                params[k] = v
            }
        }
        val steam = typeName == "steam" || params["encoder"].equals("steam", ignoreCase = true)
        val type = when {
            steam -> OtpType.STEAM
            typeName == "totp" -> OtpType.TOTP
            typeName == "hotp" -> OtpType.HOTP
            else -> throw InvalidOtp("unsupported OTP type $typeName")
        }
        val secretText = params["secret"] ?: throw InvalidOtp("otpauth URI without secret")
        val key = Base32.decode(secretText) ?: throw InvalidOtp("secret is not Base32")
        val colon = label.indexOf(':')
        val labelIssuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val account = if (colon >= 0) label.substring(colon + 1).trim() else label.trim()
        return OtpParams(
            type = type,
            secret = Secret(key),
            algorithm = params["algorithm"]?.takeIf { it.isNotBlank() } ?: "SHA1",
            digits = params["digits"]?.toIntOrNull() ?: if (steam) 5 else 6,
            period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
            counter = params["counter"]?.toLongOrNull() ?: 0,
            issuer = params["issuer"]?.takeIf { it.isNotBlank() } ?: labelIssuer,
            account = account,
        ).also { validate(it) }
    }

    /** An otpauth:// link, or a steam:// link with a Base32 key (Steam Guard codes). */
    fun parseLink(link: String, issuer: String = "", account: String = ""): OtpParams {
        val t = link.trim()
        if (!t.startsWith(STEAM_SCHEME, ignoreCase = true)) return parseUri(t)
        val key = Base32.decode(t.substring(STEAM_SCHEME.length)) ?: throw InvalidOtp("Steam key is not Base32")
        if (key.isEmpty()) throw InvalidOtp("no secret")
        return of(OtpType.STEAM, key, "SHA1", 5, 30, 0, issuer.ifEmpty { "Steam" }, account)
    }

    /**
     * OTP settings read from a file's fields: [key] is the raw key (kept by the result, wiped
     * when the settings are not valid), checked as a loaded entry's are.
     */
    fun of(
        type: OtpType,
        key: ByteArray,
        algorithm: String,
        digits: Int,
        period: Int,
        counter: Long,
        issuer: String,
        account: String,
    ): OtpParams {
        val p = OtpParams(type, Secret(key), algorithm.ifBlank { "SHA1" }, digits, period, counter, issuer, account)
        try {
            if (key.isEmpty()) throw InvalidOtp("no secret")
            validate(p)
        } catch (e: Exception) {
            p.wipe()
            throw e
        }
        return p
    }

    /** OTP settings entered by hand: a Base32 key and its parameters, checked as a loaded entry's are. */
    fun manual(
        type: OtpType,
        secretBase32: String,
        algorithm: String,
        digits: Int,
        period: Int,
        counter: Long,
        issuer: String,
        account: String,
    ): OtpParams {
        val key = Base32.decode(secretBase32) ?: throw InvalidOtp("secret is not Base32")
        if (key.isEmpty()) throw InvalidOtp("no secret")
        val p = OtpParams(type, Secret(key), algorithm, digits, period, counter, issuer, account)
        try {
            validate(p)
        } catch (e: Exception) {
            p.wipe()
            throw e
        }
        return p
    }

    /**
     * OTP settings of an entry: the `otp` attribute (otpauth or steam link), the
     * legacy KeePassXC `TOTP Seed` / `TOTP Settings` pair, or KeePass 2.47's
     * `TimeOtp-*` / `HmacOtp-*` fields. null when the entry has none.
     */
    fun fromEntry(entry: PasswordEntry): OtpParams? {
        entry.get("otp")?.value()?.takeIf { it.isNotBlank() }?.let { value ->
            val v = value.trim()
            return if (v.startsWith("otpauth://", ignoreCase = true) || v.startsWith(STEAM_SCHEME, ignoreCase = true)) {
                parseLink(v, entry.title, entry.username)
            } else {
                // KeePassDX and older plugins also store a bare Base32 key here.
                fromSeed(value, null, entry)
            }
        }
        entry.get("TOTP Seed")?.value()?.takeIf { it.isNotBlank() }?.let { seed ->
            return fromSeed(seed, entry.get("TOTP Settings")?.value(), entry)
        }
        timeOtp(entry)?.let { return it }
        hmacOtp(entry)?.let { return it }
        return null
    }

    /** Whether [entry] has OTP settings, without decoding them. */
    fun hasOtp(entry: PasswordEntry): Boolean = entry.fields.any { f ->
        (f.name == "otp" || f.name == "TOTP Seed" || f.name.startsWith("TimeOtp-Secret") ||
            f.name.startsWith("HmacOtp-Secret")) && !f.isEmpty
    }

    /** `TOTP Settings`: "period;digits", digits "S" for Steam; a URI in the seed field is also accepted. */
    private fun fromSeed(seed: String, settings: String?, entry: PasswordEntry): OtpParams {
        if (seed.trim().startsWith("otpauth://", ignoreCase = true)) return parseUri(seed)
        val key = Base32.decode(seed) ?: throw InvalidOtp("TOTP seed is not Base32")
        var period = 30
        var digits = 6
        var steam = false
        if (!settings.isNullOrBlank()) {
            val parts = settings.split(';')
            parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { period = it }
            val d = parts.getOrNull(1)?.trim()
            if (d.equals("S", ignoreCase = true)) {
                steam = true
                digits = 5
            } else {
                d?.toIntOrNull()?.let { digits = it }
            }
        }
        return OtpParams(
            type = if (steam) OtpType.STEAM else OtpType.TOTP,
            secret = Secret(key),
            algorithm = "SHA1",
            digits = digits,
            period = period,
            counter = 0,
            issuer = entry.title,
            account = entry.username,
        ).also { validate(it) }
    }

    private fun keyFromFields(entry: PasswordEntry, prefix: String): ByteArray? {
        entry.get("$prefix-Secret")?.value()?.takeIf { it.isNotEmpty() }?.let { return it.toByteArray(Charsets.UTF_8) }
        entry.get("$prefix-Secret-Hex")?.value()?.takeIf { it.isNotBlank() }?.let {
            return Bytes.fromHex(it) ?: throw InvalidOtp("$prefix-Secret-Hex is not hex")
        }
        entry.get("$prefix-Secret-Base32")?.value()?.takeIf { it.isNotBlank() }?.let {
            return Base32.decode(it) ?: throw InvalidOtp("$prefix-Secret-Base32 is not Base32")
        }
        entry.get("$prefix-Secret-Base64")?.value()?.takeIf { it.isNotBlank() }?.let {
            return try {
                Base64.getMimeDecoder().decode(it.trim())
            } catch (e: IllegalArgumentException) {
                throw InvalidOtp("$prefix-Secret-Base64 is not Base64")
            }
        }
        return null
    }

    private fun timeOtp(entry: PasswordEntry): OtpParams? {
        val key = keyFromFields(entry, "TimeOtp") ?: return null
        return OtpParams(
            type = OtpType.TOTP,
            secret = Secret(key),
            algorithm = entry.get("TimeOtp-Algorithm")?.value()?.takeIf { it.isNotBlank() } ?: "SHA1",
            digits = entry.get("TimeOtp-Length")?.value()?.trim()?.toIntOrNull() ?: 6,
            period = entry.get("TimeOtp-Period")?.value()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 30,
            counter = 0,
            issuer = entry.title,
            account = entry.username,
        ).also { validate(it) }
    }

    private fun hmacOtp(entry: PasswordEntry): OtpParams? {
        val key = keyFromFields(entry, "HmacOtp") ?: return null
        return OtpParams(
            type = OtpType.HOTP,
            secret = Secret(key),
            algorithm = "SHA1",
            digits = 6,
            period = 30,
            counter = entry.get("HmacOtp-Counter")?.value()?.trim()?.toLongOrNull() ?: 0,
            issuer = entry.title,
            account = entry.username,
        ).also { validate(it) }
    }

    private fun validate(p: OtpParams) {
        if (p.digits !in 1..10) throw InvalidOtp("digits ${p.digits} out of range")
        if (p.period !in 1..86400) throw InvalidOtp("period ${p.period} out of range")
        // Probe the algorithm name once so a broken entry fails at load, not while drawing codes.
        generate(ByteArray(1), 0, p.algorithm, 1)
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}

/** RFC 4648 Base32, case-insensitive, padding and spaces ignored. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8 + 1)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == ' ' || c == '-' || c == '\t' || c == '\n' || c == '\r') continue
            val v = ALPHABET.indexOf(c.uppercaseChar())
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
