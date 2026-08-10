package co.aura.core.logging

enum class LogCategory {
    AI, VOICE, ACTION, PLUGIN, SECURITY, SYNC, NETWORK, DATABASE, AUTOMATION
}

object AuraLogger {
    fun d(category: LogCategory, message: String) {
        println("[DEBUG][${category.name}] $message")
    }

    fun i(category: LogCategory, message: String) {
        println("[INFO][${category.name}] $message")
    }

    fun w(category: LogCategory, message: String) {
        println("[WARN][${category.name}] $message")
    }

    fun e(category: LogCategory, message: String, throwable: Throwable? = null) {
        println("[ERROR][${category.name}] $message")
        throwable?.printStackTrace()
    }
}
