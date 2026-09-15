package com.watchparty

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * Manages signaling server configuration and application-level context.
 */
class WatchPartyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: WatchPartyApp
            private set

        private const val PREFS_NAME = "watch_party_prefs"
        private const val KEY_SERVER_URL = "signaling_server_url"

        // Default to Android Emulator loopback; local LAN or custom URLs can be chosen in settings
        const val DEFAULT_EMULATOR_URL = "ws://10.0.2.2:8080"
        const val DEFAULT_LAN_URL = "ws://192.168.100.17:8080"

        private val prefs: SharedPreferences by lazy {
            instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        var serverUrl: String
            get() = prefs.getString(KEY_SERVER_URL, DEFAULT_EMULATOR_URL) ?: DEFAULT_EMULATOR_URL
            set(value) {
                prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()
            }
    }
}
