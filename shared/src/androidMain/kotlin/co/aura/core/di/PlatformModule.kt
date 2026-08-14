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
    single<TextToSpeechEngine> { AndroidTTSEngine(get(), get()) }
    single<PermissionManager> { AndroidPermissionManager(get()) }
    single<co.aura.security.SecureStorage> { co.aura.security.AndroidSecureStorage(get()) }
    single<co.aura.actions.InstalledAppRegistry> { co.aura.actions.AndroidInstalledAppRegistry(get()) }
    single<co.aura.actions.AppAliasResolver> { co.aura.actions.AppAliasResolver() }
    single<co.aura.communication.ContactResolver> { co.aura.communication.AndroidContactResolver(get()) }
    single<co.aura.actions.ActionExecutor> { co.aura.actions.AndroidActionExecutor(get(), get(), get(), get(), get()) }
}
