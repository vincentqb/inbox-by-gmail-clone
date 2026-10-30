package com.baestheorem.inbox.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.baestheorem.inbox.BuildConfig
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicLong

// Where the OAuth client and the user's refresh token live. Encrypted at rest
// under the Android keystore; if that ever fails to open (some OEM images ship
// a broken keystore) it degrades to app-private prefs rather than bricking the
// app, since the file is already inside the app sandbox.
object AuthStore {
    private const val FILE = "inbox-auth"
    private const val K_CLIENT_ID = "client_id"
    private const val K_CLIENT_SECRET = "client_secret"
    private const val K_REFRESH = "refresh_token"
    private const val K_ACCOUNT = "account"
    private const val K_OWN_PROJECT = "own_project"

    private lateinit var prefs: SharedPreferences
    private val version = AtomicLong()
    /** In-flight requests must never adopt credentials from a newer sign-in. */
    val sessionVersion: Long get() = version.get()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: GeneralSecurityException) {
            Log.w("AuthStore", "encrypted prefs unavailable, using plain app-private prefs", e)
            context.getSharedPreferences(FILE + "-plain", Context.MODE_PRIVATE)
        } catch (e: java.io.IOException) {
            Log.w("AuthStore", "encrypted prefs unavailable, using plain app-private prefs", e)
            context.getSharedPreferences(FILE + "-plain", Context.MODE_PRIVATE)
        }
    }

    /** An OAuth client compiled into the APK, if the build had client.properties. */
    val embeddedClientId: String get() = BuildConfig.EMBEDDED_CLIENT_ID
    val embeddedClientSecret: String get() = BuildConfig.EMBEDDED_CLIENT_SECRET
    val embeddedOwner: String get() = BuildConfig.EMBEDDED_CLIENT_OWNER
    val hasEmbeddedClient: Boolean
        get() = embeddedClientId.isNotEmpty() && embeddedClientSecret.isNotEmpty()

    var clientId: String
        get() = prefs.getString(K_CLIENT_ID, "") ?: ""
        set(v) { version.incrementAndGet(); prefs.edit().putString(K_CLIENT_ID, v).apply() }

    var clientSecret: String
        get() = prefs.getString(K_CLIENT_SECRET, "") ?: ""
        set(v) { version.incrementAndGet(); prefs.edit().putString(K_CLIENT_SECRET, v).apply() }

    var refreshToken: String
        get() = prefs.getString(K_REFRESH, "") ?: ""
        set(v) { version.incrementAndGet(); prefs.edit().putString(K_REFRESH, v).commit() }

    var account: String
        get() = prefs.getString(K_ACCOUNT, "") ?: ""
        set(v) { prefs.edit().putString(K_ACCOUNT, v).commit() }

    /** True when the user brought their own Google Cloud project in the wizard. */
    var ownProject: Boolean
        get() = prefs.getBoolean(K_OWN_PROJECT, false)
        set(v) = prefs.edit().putBoolean(K_OWN_PROJECT, v).apply()

    val hasClient: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()
    val isSignedIn: Boolean get() = hasClient && refreshToken.isNotEmpty()

    fun useEmbeddedClient() {
        clientId = embeddedClientId
        clientSecret = embeddedClientSecret
        ownProject = false
    }

    fun setOwnClient(id: String, secret: String) {
        clientId = id.trim()
        clientSecret = secret.trim()
        ownProject = true
    }

    /** Drops the token but keeps the client, so re-signing in is one tap. */
    fun signOut() {
        version.incrementAndGet()
        prefs.edit().remove(K_REFRESH).remove(K_ACCOUNT).commit()
    }

    /** Full reset, back to the first wizard screen. */
    fun forgetEverything() {
        version.incrementAndGet()
        prefs.edit().clear().commit()
    }
}
