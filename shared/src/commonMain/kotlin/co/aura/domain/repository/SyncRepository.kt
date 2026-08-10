package co.aura.domain.repository

import co.aura.domain.model.SyncTask
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    fun getPendingTasks(): Flow<List<SyncTask>>
    suspend fun queueSyncTask(task: SyncTask)
    suspend fun removeSyncTask(taskId: String)
}
