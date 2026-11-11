package com.prism.music.data.spotify

import android.content.SharedPreferences
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals text before it's written to the phone and opens it again ([KeystoreBox]; the tests use a plain one). */
interface TokenBox {
    fun seal(plain: String): String
    /** Null when it can't be opened (the key is gone: Android's backup restored the file onto another phone). */
    fun open(sealed: String): String?
}

/**
 * AES-GCM under a key that lives in the Android Keystore ([ALIAS]): the key never leaves the phone's secure hardware (or
 * its keystore daemon), so the sealed tokens in shared_prefs are useless anywhere else, a copied file included.
 */
class KeystoreBox : TokenBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String? = runCatching {
        val all = java.util.Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV)) }
        String(cipher.doFinal(all, IV, all.size - IV), Charsets.UTF_8)
    }.getOrNull()

    /** The key goes with the tokens (Disconnect). */
    fun forget() = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }

    companion object {
        const val ALIAS = "prism_spotify_tokens"
        private const val IV = 12
    }
}

/**
 * The Spotify connection kept on the phone. Two files:
 * - [AUTH_PREFS] ("spotify_auth"): the tokens, sealed by [box], and the account's id and name. Never in Prism's
 *   backups (Backup's PREFS_PARTS: null), never in Android's.
 * - [PREFS] ("spotify"): the Client ID the listener pasted, the features' switches and their sync state. No secret in
 *   it: a Client ID is public in PKCE. In backups with "Accounts", so a restored phone only needs Reconnect.
 */
class SpotifyStore(private val auth: SharedPreferences, val prefs: SharedPreferences, private val box: TokenBox) {
    var clientId: String
        get() = prefs.getString(CLIENT_ID, "").orEmpty()
        set(v) { prefs.edit().putString(CLIENT_ID, v.trim()).apply() }

    fun tokens(): SpotifyTokens? {
        val access = auth.getString(ACCESS, null)?.let(box::open) ?: return null
        val refresh = auth.getString(REFRESH, null)?.let(box::open)
        return SpotifyTokens(access, refresh, auth.getLong(EXPIRES, 0), auth.getString(SCOPE, "").orEmpty())
    }

    fun saveTokens(t: SpotifyTokens) {
        auth.edit().putString(ACCESS, box.seal(t.access)).apply {
            if (t.refresh != null) putString(REFRESH, box.seal(t.refresh))
        }.putLong(EXPIRES, t.expiresAt).putString(SCOPE, t.scope).putBoolean(REVOKED, false).commit()
    }

    var userId: String?
        get() = auth.getString(USER_ID, null)
        set(v) { auth.edit().putString(USER_ID, v).apply() }
    var userName: String?
        get() = auth.getString(USER_NAME, null)
        set(v) { auth.edit().putString(USER_NAME, v).apply() }

    /** Spotify took the access back (the refresh token was refused): the page offers Reconnect. */
    var revoked: Boolean
        get() = auth.getBoolean(REVOKED, false)
        set(v) { auth.edit().putBoolean(REVOKED, v).apply() }

    val connected: Boolean get() = connectedIn(auth)

    /** Only the tokens and the account go (a sign-in that didn't finish): the switches and the Client ID stay. */
    fun clearTokens() { auth.edit().clear().commit() }

    /** Everything about the connection goes; the Client ID only when [clientIdToo]. */
    fun wipe(clientIdToo: Boolean) {
        auth.edit().clear().commit()
        val keep = if (clientIdToo) null else clientId
        prefs.edit().clear().apply { keep?.takeIf { it.isNotBlank() }?.let { putString(CLIENT_ID, it) } }.commit()
        (box as? KeystoreBox)?.forget()
    }

    companion object {
        const val AUTH_PREFS = "spotify_auth"
        const val PREFS = "spotify"
        private const val CLIENT_ID = "client_id"
        private const val ACCESS = "access"
        private const val REFRESH = "refresh"
        private const val EXPIRES = "expires_at"
        private const val SCOPE = "scope"
        private const val USER_ID = "user_id"
        private const val USER_NAME = "user_name"
        private const val REVOKED = "revoked"

        /** Connected, read from [auth] ([AUTH_PREFS]) alone: nothing is unsealed, no keystore is touched. */
        fun connectedIn(auth: SharedPreferences): Boolean = auth.contains(ACCESS) && !auth.getBoolean(REVOKED, false)
    }
}
