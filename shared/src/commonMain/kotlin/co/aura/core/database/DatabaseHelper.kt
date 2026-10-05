package co.aura.core.database

import app.cash.sqldelight.db.SqlDriver
import co.aura.database.AuraDatabase

class DatabaseHelper(driver: SqlDriver) {
    init {
        try {
            driver.execute(
                null,
                """
                CREATE TABLE IF NOT EXISTS conversation_summary (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    summary TEXT NOT NULL,
                    last_summarized_message_id TEXT,
                    updated_at INTEGER NOT NULL
                );
                """.trimIndent(),
                0
            )
        } catch (e: Exception) {
            // Already created or handled by schema
        }
    }

    val database = AuraDatabase(driver)
    val chatMessageQueries = database.chatHistoryQueries
    val localMemoryQueries = database.localMemoryQueries
    val syncQueueQueries = database.syncQueueQueries
}

