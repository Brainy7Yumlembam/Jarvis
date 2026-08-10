package co.aura.core.database

import app.cash.sqldelight.db.SqlDriver
import co.aura.database.AuraDatabase

class DatabaseHelper(driver: SqlDriver) {
    val database = AuraDatabase(driver)
    val chatMessageQueries = database.chatHistoryQueries
    val localMemoryQueries = database.localMemoryQueries
    val syncQueueQueries = database.syncQueueQueries
}
