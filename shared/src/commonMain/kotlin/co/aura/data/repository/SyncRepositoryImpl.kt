package co.aura.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.aura.core.database.DatabaseHelper
import co.aura.domain.model.SyncTask
import co.aura.domain.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SyncRepositoryImpl(
    private val databaseHelper: DatabaseHelper
) : SyncRepository {
    
    override fun getPendingTasks(): Flow<List<SyncTask>> {
        return databaseHelper.syncQueueQueries
            .getPendingTasks()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { entity ->
                    SyncTask(
                        id = entity.id,
                        operationType = entity.operation_type,
                        payload = entity.payload,
                        createdAt = entity.created_at,
                        attempts = entity.attempts.toInt()
                    )
                }
            }
    }

    override suspend fun queueSyncTask(task: SyncTask) {
        databaseHelper.syncQueueQueries.insertSyncTask(
            id = task.id,
            operation_type = task.operationType,
            payload = task.payload,
            created_at = task.createdAt,
            attempts = task.attempts.toLong()
        )
    }

    override suspend fun removeSyncTask(taskId: String) {
        databaseHelper.syncQueueQueries.deleteSyncTask(taskId)
    }
}
