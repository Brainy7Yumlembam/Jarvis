package co.aura.security

import android.content.Context

class AndroidSecureStorage(private val context: Context) : SecureStorage {
    private val prefs = context.getSharedPreferences("aura_secure_prefs", Context.MODE_PRIVATE)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun get(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
