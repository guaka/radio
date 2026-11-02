package org.guaka.radio

import android.content.Context

/** Registers the pre-beta preference layout without moving encrypted credentials or rewriting user data. */
object SettingsMigration {
    const val CURRENT_SCHEMA = 1

    @Synchronized
    fun migrate(context: Context): Boolean {
        val schema = context.getSharedPreferences("settings_schema", Context.MODE_PRIVATE)
        val version = schema.all["version"] as? Int ?: 0
        // A newer application may have written this marker; never downgrade or clear its data.
        if (version >= CURRENT_SCHEMA) return true
        // Prototype versions already use settings/credentials/listening. Keep those names and the
        // Android Keystore alias intact so an in-place signed APK update preserves the whole layout.
        return schema.edit().putInt("version", CURRENT_SCHEMA).commit()
    }
}
