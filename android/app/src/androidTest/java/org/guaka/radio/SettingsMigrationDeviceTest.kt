package org.guaka.radio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsMigrationDeviceTest {
    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("migration_test_$name", mode)
    }

    @Test fun registersPrototypeLayoutWithoutRewritingSettingsCredentialsOrSavedQueue() {
        val context = context()
        val schema = context.getSharedPreferences("settings_schema", Context.MODE_PRIVATE)
        schema.edit().clear().commit()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            .putString("clientId", "stable-client").putString("assistantUrl", "http://192.168.1.2:8095").commit()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear()
            .putString("plexToken", "opaque-encrypted-fixture").commit()
        val listening = ListeningStore(context)
        if (!listening.isFavorite("radio:7soul")) listening.toggleFavorite("radio:7soul")
        val saved = SavedPlayback(listOf("radio:7soul", "track:server:42"), 1, 32000L)
        listening.savePlayback(saved)
        val before = listOf("settings", "credentials", "listening").associateWith {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap()
        }
        assertTrue(SettingsMigration.migrate(context))
        assertTrue(SettingsMigration.migrate(context))
        before.forEach { (name, expected) -> assertEquals(expected, context.getSharedPreferences(name, Context.MODE_PRIVATE).all) }
        assertEquals(SettingsMigration.CURRENT_SCHEMA, schema.getInt("version", 0))
        assertEquals(saved, ListeningStore(context).loadPlayback())
    }

    @Test fun neverDowngradesANewerSchemaMarker() {
        val context = context()
        val schema = context.getSharedPreferences("settings_schema", Context.MODE_PRIVATE)
        schema.edit().clear().putInt("version", SettingsMigration.CURRENT_SCHEMA + 1).commit()
        assertTrue(SettingsMigration.migrate(context))
        assertEquals(SettingsMigration.CURRENT_SCHEMA + 1, schema.getInt("version", 0))
    }
}
