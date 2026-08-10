package co.aura.core.di

import co.aura.actions.*
import co.aura.ai.*
import co.aura.automation.*
import co.aura.backend.*
import co.aura.conversation.*
import co.aura.core.database.DatabaseHelper
import co.aura.data.repository.*
import co.aura.domain.repository.*
import co.aura.memory.*
import co.aura.phone.*
import co.aura.plugins.*
import co.aura.plugins.device.*
import co.aura.security.*
import co.aura.sync.*
import co.aura.vision.*
import co.aura.voice.*
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val commonModule = module {
    // Database Infrastructure
    single { DatabaseHelper(get()) }
    
    // Networking
    single { createHttpClient() }
    
    // Serialization
    single { Json { ignoreUnknownKeys = true; isLenient = true } }
    
    // Repositories
    single<ConversationRepository> { ConversationRepositoryImpl(get()) }
    single<MemoryRepository> { MemoryRepositoryImpl(get()) }
    single<DeviceActionRepository> { DeviceActionRepositoryImpl() }
    single<SyncRepository> { SyncRepositoryImpl(get()) }
    
    // AI Providers
    single { GeminiModelConfig("gemini-1.5-flash") }
    single<AIProvider> { GeminiProvider(get(), get(), get(), get()) }
    
    // Action / Command Bus Architecture
    single<CommandBus> { CommandBusImpl() }
    single<ActionParser> { ActionParserImpl(get()) }
    single<ActionValidator> { ActionValidatorImpl() }
    single<ActionRouter> { ActionRouterImpl(get()) }
    
    // Cognitive Managers
    single<MemoryRetriever> { KeywordMemoryRetriever(get()) }
    single<ContextBuilder> { ContextBuilderImpl() }
    single<MemoryManager> { MemoryManagerImpl(get(), get(), get()) }
    single<PersonalityEngine> { PersonalityEngineImpl() }
    single<ConversationManager> { ConversationManagerImpl(get(), get(), get(), get(), get()) }
    
    // Extension Systems
    single<PluginManager> { PluginManagerImpl().apply {
        registerPlugin(CalendarPlugin())
        registerPlugin(ContactsPlugin())
        registerPlugin(SMSPlugin())
        registerPlugin(CameraPlugin())
        registerPlugin(FilesPlugin())
        registerPlugin(MusicPlugin())
        registerPlugin(WeatherPlugin())
        registerPlugin(BrowserPlugin())
        registerPlugin(MapsPlugin())
        registerPlugin(NotificationPlugin())
        registerPlugin(AlarmPlugin())
    }}
    single<AutomationManager> { AutomationManagerImpl(get()) }
    
    // Security Framework
    single<SecurityManager> { SecurityManagerImpl(get()) }
    
    // Sync Utilities
    single<SyncEngine> { SyncEngineImpl(get()) }
    
    // System & Sensor control providers
    single<SystemControlProvider> { SystemControlProviderImpl() }
    single<VisualIntelligenceProvider> { VisualIntelligenceProviderImpl() }
    
    // ViewModels
    factory { VoiceAssistantViewModel(get(), get(), get(), get()) }
    factory { co.aura.presentation.viewmodel.MemoryViewModel(get()) }
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(platformModule, commonModule)
}

fun initKoinHelper() = initKoin {}
