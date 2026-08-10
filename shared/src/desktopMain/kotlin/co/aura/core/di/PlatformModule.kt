package co.aura.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import co.aura.database.AuraDatabase
import co.aura.security.DesktopPermissionManager
import co.aura.security.PermissionManager
import co.aura.voice.DesktopSpeechRecognizer
import co.aura.voice.DesktopTTSEngine
import co.aura.voice.TextToSpeechEngine
import co.aura.voice.VoiceRecognizer
import org.koin.dsl.module
import java.io.File

actual val platformModule = module {
    single<SqlDriver> {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".aura")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        val dbFile = File(appDir, "aura.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        
        try {
            AuraDatabase.Schema.create(driver)
        } catch (e: Exception) {
            // Already created
        }
        driver
    }
    single<VoiceRecognizer> { DesktopSpeechRecognizer() }
    single<TextToSpeechEngine> { DesktopTTSEngine() }
    single<PermissionManager> { DesktopPermissionManager() }
}
