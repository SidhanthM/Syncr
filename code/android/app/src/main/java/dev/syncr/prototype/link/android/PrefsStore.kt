package dev.syncr.prototype.link.android

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.syncr.prototype.link.Pairing
import dev.syncr.prototype.link.PairingStore
import java.security.SecureRandom

/** The phone's link identity and its one pairing, in app-private preferences. "Forget" deletes the pairing, nothing else. */
class PrefsPairingStore(private val prefs: SharedPreferences) : PairingStore {
    @Synchronized
    override fun phoneId(): String {
        prefs.getString(PHONE_ID, null)?.takeIf { it.length == 32 }?.let { return it }
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit { putString(PHONE_ID, fresh) }
        return fresh
    }

    @Synchronized
    override fun pairing(): Pairing? {
        val id = prefs.getString(MAC_ID, null) ?: return null
        val token = prefs.getString(TOKEN, null) ?: return null
        return Pairing(id, prefs.getString(MAC_NAME, null) ?: "Mac", token)
    }

    @Synchronized
    override fun save(pairing: Pairing) = prefs.edit {
        putString(MAC_ID, pairing.macId)
        putString(MAC_NAME, pairing.macName)
        putString(TOKEN, pairing.token)
    }

    @Synchronized
    override fun forget() = prefs.edit {
        remove(MAC_ID); remove(MAC_NAME); remove(TOKEN)
        remove(LAST_HOST); remove(LAST_PORT)
    }

    @Synchronized
    fun lastEndpoint(): Pair<String, Int>? {
        val host = prefs.getString(LAST_HOST, null) ?: return null
        val port = prefs.getInt(LAST_PORT, 0)
        return if (port in 1..65535) host to port else null
    }

    @Synchronized
    fun saveEndpoint(host: String, port: Int) = prefs.edit { putString(LAST_HOST, host); putInt(LAST_PORT, port) }

    private companion object {
        const val PHONE_ID = "phone_id"
        const val MAC_ID = "mac_id"
        const val MAC_NAME = "mac_name"
        const val TOKEN = "token"
        const val LAST_HOST = "last_host"
        const val LAST_PORT = "last_port"
    }
}
