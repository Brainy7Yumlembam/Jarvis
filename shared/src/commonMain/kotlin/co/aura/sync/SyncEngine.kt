package co.aura.sync

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.repository.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

interface SyncEngine {
    fun triggerSync()
    fun isSyncing(): Boolean
}

class SyncEngineImpl(
    private val syncRepository: SyncRepository
) : SyncEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncActive = false

    override fun triggerSync() {
        if (syncActive) return
        scope.launch {
            syncActive = true
            AuraLogger.i(LogCategory.SYNC, "Starting database synchronization...")
            try {
                val pendingTasks = syncRepository.getPendingTasks().firstOrNull() ?: emptyList()
                AuraLogger.i(LogCategory.SYNC, "Found ${pendingTasks.size} pending tasks to sync.")
                
                pendingTasks.forEach { task ->
                    AuraLogger.i(LogCategory.SYNC, "Syncing task: ${task.id} [${task.operationType}]")
                    // TODO: Execute HTTP Postgrest request via Supabase Client
                    // Upon success, remove from local queue:
                    syncRepository.removeSyncTask(task.id)
                }
                
                AuraLogger.i(LogCategory.SYNC, "Sync completed successfully.")
            } catch (e: Exception) {
                AuraLogger.e(LogCategory.SYNC, "Synchronization failed.", e)
            } finally {
                syncActive = false
            }
        }
    }

    override fun isSyncing(): Boolean = syncActive
}
