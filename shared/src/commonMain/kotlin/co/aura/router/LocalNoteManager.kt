package co.aura.router

import co.aura.domain.model.MemoryCategory
import co.aura.domain.model.MemoryFragment
import co.aura.memory.MemoryManager

/**
 * Manages local persistent notes and reminders via MemoryManager and SQLDelight.
 * Resolves note creation, search, retrieval, and deletion with ZERO Gemini tokens.
 */
class LocalNoteManager(
    private val memoryManager: MemoryManager
) {

    suspend fun addNote(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return "Sir, the note content is empty."
        val success = memoryManager.storeMemory(trimmed, "NOTE", score = 8)
        return if (success) {
            LocalResponseProvider.noteSaved(trimmed)
        } else {
            "Sorry, sir. I couldn't save that note."
        }
    }

    suspend fun getAllNotes(): List<MemoryFragment> {
        val all = memoryManager.searchMemories("")
        return all.filter { it.category == MemoryCategory.FACT || it.category == MemoryCategory.CONTEXT }
    }

    suspend fun readNotesSummary(): String {
        val notes = getAllNotes()
        if (notes.isEmpty()) {
            return "You have no saved notes, sir."
        }
        val noteList = notes.take(5).mapIndexed { idx, n -> "${idx + 1}. ${n.content}" }.joinToString("\n")
        return "Here are your recent notes, sir:\n$noteList"
    }

    suspend fun searchNotes(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return readNotesSummary()
        val all = memoryManager.searchMemories(trimmed)
        if (all.isEmpty()) {
            return "I couldn't find any notes matching \"$trimmed\", sir."
        }
        val noteList = all.take(3).map { "- ${it.content}" }.joinToString("\n")
        return "Here is what I found for \"$trimmed\", sir:\n$noteList"
    }

    suspend fun deleteNote(queryOrId: String): String {
        val trimmed = queryOrId.trim()
        val all = memoryManager.searchMemories("")
        val matches = all.filter { it.id == trimmed || it.content.contains(trimmed, ignoreCase = true) }
        return when {
            matches.isEmpty() -> "I couldn't find any note matching \"$trimmed\", sir."
            matches.size == 1 -> {
                val target = matches.first()
                memoryManager.forgetMemory(target.id)
                LocalResponseProvider.noteDeleted(target.content)
            }
            else -> {
                val first = matches.first()
                memoryManager.forgetMemory(first.id)
                LocalResponseProvider.noteDeleted(first.content)
            }
        }
    }

    suspend fun addReminder(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return "Sir, the reminder title is empty."
        val success = memoryManager.storeMemory("Reminder: $trimmed", "ROUTINE", score = 9)
        return if (success) {
            "Reminder created: \"$trimmed\", sir."
        } else {
            "Sir, I was unable to save the reminder."
        }
    }

    suspend fun listReminders(): String {
        val all = memoryManager.searchMemories("Reminder")
        val reminders = all.filter { it.content.startsWith("Reminder:", ignoreCase = true) }
        if (reminders.isEmpty()) {
            return "You have no active reminders, sir."
        }
        val list = reminders.take(5).map { "- ${it.content.removePrefix("Reminder:").trim()}" }.joinToString("\n")
        return "Here are your reminders, sir:\n$list"
    }
}
