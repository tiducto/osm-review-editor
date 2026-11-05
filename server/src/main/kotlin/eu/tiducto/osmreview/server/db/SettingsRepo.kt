package eu.tiducto.osmreview.server.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SettingsRepo(private val db: Db) {
    fun get(key: String): String? = db.txBlocking {
        SettingsTable.selectAll().where { SettingsTable.key eq key }.firstOrNull()?.get(SettingsTable.value)
    }

    fun put(key: String, value: String) {
        db.txBlocking {
            SettingsTable.upsert {
                it[SettingsTable.key] = key
                it[SettingsTable.value] = value
                it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    fun delete(key: String) {
        db.txBlocking { SettingsTable.deleteWhere { SettingsTable.key eq key } }
    }
}
