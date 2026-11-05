package eu.tiducto.osmreview.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Hikari pool + Flyway migrations (classpath:db/migration) + Exposed. */
class Db(url: String, user: String, password: String) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 8
            poolName = "osmreview"
        },
    )

    val database: Database

    init {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas("app")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        database = Database.connect(dataSource)
    }

    /** Blocking transaction on the IO dispatcher, for suspend callers. */
    suspend fun <T> tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(database) { block() }
    }

    fun <T> txBlocking(block: JdbcTransaction.() -> T): T = transaction(database) { block() }

    override fun close() = dataSource.close()
}
