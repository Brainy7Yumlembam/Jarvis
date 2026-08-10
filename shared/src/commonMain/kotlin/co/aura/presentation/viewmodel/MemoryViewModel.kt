package co.aura.presentation.viewmodel

import co.aura.domain.model.MemoryFragment
import co.aura.memory.MemoryManager

class MemoryViewModel(
    private val memoryManager: MemoryManager
) : BaseViewModel<List<MemoryFragment>, Unit>(emptyList()) {

    init {
        loadMemories()
    }

    override fun onEvent(event: Unit) {}

    fun loadMemories() {
        launchInScope {
            val memories = memoryManager.searchMemories("")
            updateState { memories }
        }
    }

    fun pinMemory(memoryId: String) {
        launchInScope {
            memoryManager.pinMemory(memoryId)
            loadMemories()
        }
    }

    fun deleteMemory(memoryId: String) {
        launchInScope {
            memoryManager.forgetMemory(memoryId)
            loadMemories()
        }
    }
}
