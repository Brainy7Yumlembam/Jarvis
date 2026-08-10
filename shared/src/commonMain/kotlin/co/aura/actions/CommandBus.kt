package co.aura.actions

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CommandBus {
    suspend fun executeCommand(command: Command): Boolean
    suspend fun undoLastCommand(): Boolean
    fun getCommandHistory(): List<Command>
}

class CommandBusImpl : CommandBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val history = mutableListOf<Command>()
    private val undoStack = mutableListOf<UndoableCommand>()

    override suspend fun executeCommand(command: Command): Boolean = mutex.withLock {
        AuraLogger.i(LogCategory.ACTION, "Enqueuing command for execution: ${command.id}")
        history.add(command)

        var attempts = 0
        val maxAttempts = 3
        var success = false

        while (attempts < maxAttempts && !success) {
            attempts++
            try {
                success = command.execute()
                if (success) {
                    AuraLogger.i(LogCategory.ACTION, "Successfully executed command: ${command.id} on attempt $attempts")
                    if (command is UndoableCommand) {
                        undoStack.add(command)
                    }
                } else {
                    AuraLogger.w(LogCategory.ACTION, "Failed executing command: ${command.id} on attempt $attempts")
                }
            } catch (e: Exception) {
                AuraLogger.e(LogCategory.ACTION, "Exception while executing command: ${command.id} on attempt $attempts", e)
            }
        }

        return success
    }

    override suspend fun undoLastCommand(): Boolean = mutex.withLock {
        if (undoStack.isEmpty()) {
            AuraLogger.i(LogCategory.ACTION, "Undo stack is empty.")
            return false
        }
        val command = undoStack.removeLast()
        AuraLogger.i(LogCategory.ACTION, "Undoing command: ${command.id}")
        return try {
            command.undo()
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.ACTION, "Exception undoing command: ${command.id}", e)
            false
        }
    }

    override fun getCommandHistory(): List<Command> {
        return history.toList()
    }
}
