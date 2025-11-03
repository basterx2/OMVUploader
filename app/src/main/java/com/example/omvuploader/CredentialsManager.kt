package com.example.omvuploader

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "omv_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_SERVER = "server"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SHARE_NAME = "share_name"
    }

    fun saveCredentials(server: String, username: String, password: String, shareName: String = "RaspberryHDD") {
        sharedPreferences.edit().apply {
            putString(KEY_SERVER, server)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putString(KEY_SHARE_NAME, shareName)
            apply()
        }
    }

    fun getCredentials(): Credentials? {
        val server = sharedPreferences.getString(KEY_SERVER, null)
        val username = sharedPreferences.getString(KEY_USERNAME, null)
        val password = sharedPreferences.getString(KEY_PASSWORD, null)
        val shareName = sharedPreferences.getString(KEY_SHARE_NAME, "RaspberryHDD")

        return if (server != null && username != null && password != null) {
            Credentials(server, username, password, shareName)
        } else {
            null
        }
    }

    fun hasCredentials(): Boolean {
        return sharedPreferences.contains(KEY_SERVER) &&
                sharedPreferences.contains(KEY_USERNAME) &&
                sharedPreferences.contains(KEY_PASSWORD)
    }

    fun clearCredentials() {
        sharedPreferences.edit().clear().apply()
    }
}

data class Credentials(
    val server: String,
    val username: String,
    val password: String,
    val shareName: String? = "RaspberryHDD"
)