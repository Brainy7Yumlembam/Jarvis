package co.aura.actions

interface Command {
    val id: String
    suspend fun execute(): Boolean
}

interface UndoableCommand : Command {
    suspend fun undo(): Boolean
}
