package co.aura.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import co.aura.database.AuraDatabase
import co.aura.security.AndroidPermissionManager
import co.aura.security.PermissionManager
import co.aura.voice.AndroidSpeechRecognizer
import co.aura.voice.AndroidTTSEngine
import co.aura.voice.TextToSpeechEngine
import co.aura.voice.VoiceRecognizer
import org.koin.dsl.module

actual val platformModule = module {
    single<SqlDriver> {
        AndroidSqliteDriver(
            schema = AuraDatabase.Schema,
            context = get(),
            name = "aura.db"
        )
    }
    single<VoiceRecognizer> { AndroidSpeechRecognizer(get()) }
    single<TextToSpeechEngine> { AndroidTTSEngine(get()) }
    single<PermissionManager> { AndroidPermissionManager(get()) }
}
