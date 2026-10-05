# Jarvis (Aura) — System Architecture & Flow Specification

Comprehensive technical flow and architectural blueprint for the **Jarvis (Aura)** Kotlin Multiplatform (KMP) voice AI assistant application across Android and Desktop platforms.

---

## 1. High-Level Architecture Overview

Jarvis (Aura) is structured around a **Reactive, Multi-Tier, Pipeline Architecture** built on Kotlin Multiplatform, Compose Multiplatform, Coroutines/Flow, Koin DI, and SQLDelight.

```mermaid
flowchart TD
    subgraph UI_Layer ["Presentation Layer (Compose Multiplatform)"]
        UI["Jarvis Core HUD / Screen / Subtitles"]
        VM["VoiceAssistantViewModel / BaseViewModel"]
    end

    subgraph Voice_Layer ["Voice & Audio Ingestion / Output"]
        SR["Speech Recognizer (Google / Whisper / Android / Desktop)"]
        IM["Interruption Monitor (Barge-In Detector)"]
        TTS["TTS Engine (ElevenLabs / Azure / OpenAI / Google / Native)"]
        QP["TtsQueuePlayer & Visualizer Controller"]
    end

    subgraph Cognitive_Layer ["Cognitive Core & Conversation"]
        CM["ConversationManager"]
        CB["ContextBuilder"]
        PE["PersonalityEngine"]
        MM["MemoryManager & KeywordMemoryRetriever"]
        ME["MemoryExtractor (Async Background)"]
    end

    subgraph AI_Tier ["AI Provider Strategy (Fallback & Load Balancing)"]
        APM["AIProviderManager"]
        GCP["Gemini Provider (Credential Pool)"]
        NV["Nvidia Provider"]
        OAI["OpenAI / Claude / DeepSeek"]
        LOC["Local AI Provider"]
    end

    subgraph Action_Layer ["Action & Command Execution Bus"]
        AP["ActionParser"]
        AV["ActionValidator"]
        AR["ActionRouter & CommandBus"]
        PAE["Platform Action Executor (Android / Desktop)"]
        PLUG["Plugins (11 Device Plugins)"]
        RES["AppAliasResolver & ContactResolver"]
    end

    subgraph Storage_Layer ["Persistence & Security"]
        SQL[("SQLDelight SQLite Database")]
        SEC["SecureStorage (Encrypted Token/Key Vault)"]
        SYNC["SyncEngine & Supabase Backend"]
    end

    %% Flow Connections
    UI <-->|StateFlow & Events| VM
    VM -->|Start / Stop / Partial Transcript| SR
    SR -->|Transcribed Text| VM
    VM -->|Process User Message| CM
    IM -->|Barge-In Signal| VM
    VM -->|Streaming Sentence Chunks| QP
    QP -->|Audio Output| TTS
    
    CM <-->|Fetch Context & Memory| CB
    CB <-->|Query Relevant Memories| MM
    CM <-->|Prompt Persona & Rules| PE
    CM -->|Generate Response / Stream| APM
    APM --> GCP & NV & OAI & LOC
    
    CM -->|Parse Assistant Actions / Decision| AP
    AP --> AV
    AV --> AR
    AR --> RES
    AR --> PAE & PLUG
    
    CM -.->|Async Extract Fragments| ME
    ME -.->|Save Fragment| MM
    MM <-->|Read / Write| SQL
    CM <-->|Persist History| SQL
    SEC <-->|Auth & Keys| APM
    SYNC <-->|Cloud Sync| SQL
```

---

## 2. End-to-End User Interaction Flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as Holographic HUD / UI
    participant VM as VoiceAssistantViewModel
    participant SR as VoiceRecognizer
    participant CM as ConversationManager
    participant CB as ContextBuilder
    participant MM as MemoryManager
    participant AI as AIProvider (Gemini/Nvidia/Local)
    participant AR as ActionRouter
    participant QP as TtsQueuePlayer
    participant TTS as TTSEngine

    User->>UI: Tap Mic / Wake Word
    UI->>VM: VoiceEvent.StartListening
    VM->>SR: startListening()
    VM-->>UI: State = Listening (Visualizer Pulse)
    
    User->>SR: Speaks query ("Play Bohemian Rhapsody" / "What did we discuss yesterday?")
    SR-->>VM: RecognitionEvent.PartialResult("Play...")
    VM-->>UI: Update Live Subtitles & Waveform
    SR-->>VM: RecognitionEvent.FinalResult("Play Bohemian Rhapsody")
    VM-->>UI: State = Processing("Play Bohemian Rhapsody")
    
    VM->>CM: processUserMessageStream("Play Bohemian Rhapsody")
    
    %% Fast Path Deterministic Check
    alt Local Fast-Path Command (App launch / Volume / Media control / Contact Call)
        CM->>CM: SpeechCommandNormalizer + Deterministic Fast-Path
        CM->>AR: executeAction(PlayMediaAction("Bohemian Rhapsody"))
        AR-->>CM: ActionResult.Success("Playing Bohemian Rhapsody on Spotify, sir.")
    else AI Fallback / Cognitive Processing
        CM->>CB: buildPromptContext(userMessage, inMemoryHistory)
        CB->>MM: retrieveRelevantMemories(query)
        MM-->>CB: List<MemoryFragment>
        CB-->>CM: Full System Prompt + Context + Persona
        
        CM->>AI: generateResponseStream(promptContext)
        loop Token Streaming & Sentence Chunking
            AI-->>CM: Stream text tokens
            CM->>CM: SentenceChunker.extractNextChunk()
            CM-->>VM: Emit Sentence ("Searching your library and playing now, sir.")
            VM->>QP: enqueue(chunk)
            QP->>TTS: speak(chunk)
            TTS-->>User: Audio Speech Output
            VM-->>UI: State = Speaking (Live Subtitle + Cadence Visualizer)
        end
        CM->>CM: Parse AssistantDecision JSON for Actions
        opt Contains Action JSON
            CM->>AR: executeAction(ParsedAction)
            AR-->>CM: ActionResult
        end
    end

    %% Memory Pipeline
    CM--)MM: scheduleBackgroundMemoryAndSummarization()
    MM--)AI: Extract facts & preferences
    MM--)SQLDelight: Insert MemoryFragment & Summarize

    %% Completion / Continuous Mode
    QP-->>VM: onPlaybackComplete()
    alt Continuous Conversation Enabled
        VM->>SR: startListening() [State = Listening]
    else Standard Mode
        VM-->>UI: State = Idle
    end
```

---

## 3. Core Subsystem Workflows

### 3.1 Voice & Real-Time Barge-In (Interruption) Pipeline

The voice loop operates a low-latency streaming pipeline that instantly cancels active AI generation and TTS playback when user speech or an explicit interruption is detected.

```mermaid
flowchart TD
    subgraph Listening_State ["1. Speech Ingestion"]
        MIC[Microphone Input] --> VAD[Voice Activity Detection / InterruptionMonitor]
        MIC --> REC[VoiceRecognizer: Google / Whisper / Native]
        REC -->|Partial Transcription| SUB_USER[Live User Subtitle Stream]
        REC -->|Final Transcript| ROUTER[Route to Conversation Manager]
    end

    subgraph Barge_In ["2. Real-Time Interruption System"]
        VAD -->|Voice Energy Detected during TTS| DETECT[InterruptionDetector]
        DETECT -->|Trigger Interrupted| CANCEL[Cancel activeAiStreamJob & ttsQueue.stop()]
        CANCEL --> RESET_STATE[Reset TTS / Switch to Listening State]
    end

    subgraph Speech_Generation ["3. TTS & Cadence Simulation"]
        ROUTER --> STREAM_AI[AI Provider Stream]
        STREAM_AI --> CHUNKER[SentenceChunker: Split by Punctuation]
        CHUNKER --> QUEUE[DefaultTtsQueuePlayer: Thread-safe Channel Queue]
        QUEUE --> ENGINE[TextToSpeechEngine: ElevenLabs/OpenAI/Azure/Google]
        QUEUE --> CADENCE[startSpeechCadenceSimulation: Sine-wave Amplitude Modulation]
        CADENCE --> VIS[AudioVisualizerController -> UI Hologram Topology]
    end
```

---

### 3.2 Cognitive Processing & Multi-Tier AI Provider Pipeline

The system incorporates dynamic provider selection, credential rotation, rate-limit failovers, and context augmentation.

```mermaid
flowchart TD
    IN[User Query] --> CONTEXT[ContextBuilder: System Prompt + Identity + Past Summaries]
    
    subgraph Memory_Augmentation ["Context Augmentation"]
        CONTEXT --> RETRIEVE[KeywordMemoryRetriever / MemoryManager]
        RETRIEVE --> DB[(SQLDelight: LocalMemory)]
        DB --> RECENT_FACTS[Top-k Ranked Memory Fragments]
        RECENT_FACTS --> CONTEXT
    end
    
    CONTEXT --> APM[AIProviderManager]
    
    subgraph Provider_Hierarchy ["Provider Fallback & Credential Failover"]
        APM --> P1{Primary: Gemini Provider?}
        P1 -->|Success| OUT[AI Stream Output]
        P1 -->|429 / Exhausted / Network Error| ROTATE[GeminiCredentialManager: Rotate API Key]
        ROTATE --> P1
        ROTATE -->|All Gemini Keys Depleted| P2{Secondary: Nvidia Provider?}
        P2 -->|Success| OUT
        P2 -->|Error / Offline| P3{Tertiary: Local AI Provider / Ollama}
        P3 -->|Success| OUT
        P3 -->|Offline| P4[Fallback Response Engine]
        P4 --> OUT
    end
```

---

### 3.3 Intent Parsing & Action Execution Architecture

Jarvis supports dual-path action dispatch:
1. **Deterministic Fast-Path**: Instant local execution without calling the LLM for common device intents (calling, SMS, media playback, apps, alarms).
2. **LLM Cognitive Path**: Structured JSON output format (`AssistantDecision`) parsed and executed via the `CommandBus` and `ActionRouter`.

```mermaid
flowchart TD
    MSG[Input User Message] --> NORM[SpeechCommandNormalizer: Clean STT artifacts]
    NORM --> DET{Matches Deterministic Regex?}
    
    %% Fast path
    DET -->|Yes: e.g., 'Call Jarvis' or 'Pause music'| FAST[Execute Local Intent directly]
    FAST --> SEC_CHECK{SecurityManager.authorizeAction}
    
    %% AI Path
    DET -->|No| LLM[LLM Response with AssistantDecision JSON]
    LLM --> PARSER[ActionParser: Deserialize Action Models]
    PARSER --> VALIDATOR[ActionValidator: Validate Parameters & Enums]
    VALIDATOR --> SEC_CHECK
    
    %% Execution
    SEC_CHECK -->|Authorized| ROUTER[ActionRouter & CommandBus]
    SEC_CHECK -->|Sensitive / High Risk| CONFIRM[Request Biometric / User Confirmation]
    CONFIRM -->|Approved| ROUTER
    
    ROUTER --> DISPATCH{Action Target}
    DISPATCH -->|App / Media / Camera / Phone| PAE[PlatformActionExecutor: Android / Desktop]
    DISPATCH -->|Plugin Specific| PLUGS[11 Device Plugins: SMS, Maps, Calendar, Weather...]
    DISPATCH -->|Contact / App Name| ALIAS[AppAliasResolver & ContactResolver]
    
    PAE --> EXEC_RES[ActionResult: Success / Ambiguity / Failure]
    PLUGS --> EXEC_RES
    ALIAS --> EXEC_RES
    EXEC_RES --> FINALIZE[ConversationManager: Synthesize Confirmation Utterance]
```

---

### 3.4 Long-Term Memory & Asynchronous Extraction Flow

Jarvis extracts enduring facts, user preferences, and relationship aliases from everyday conversations in the background without blocking conversational replies.

```mermaid
flowchart LR
    A["Conversation Turn Complete (User & Assistant text)"] --> B["scheduleBackgroundMemoryAndSummarization (CoroutineScope)"]
    
    subgraph Async_Worker ["Background Coroutine Worker"]
        B --> C["MemoryExtractor.extractAndSaveAsync()"]
        C --> D["LLM Fact Extraction Prompt"]
        D --> E["Extract Key-Value / MemoryFragment"]
        E --> F[("SQLDelight: LocalMemory Table")]
        
        B --> G{"inMemoryHistory >= 8 turns?"}
        G -->|Yes| H["MemoryManager.summarizeOldConversations()"]
        H --> I["Generate Conversation Summary"]
        I --> J[("SQLDelight: ChatHistory & ConversationSummary")]
    end
```

---

## 4. State Machine Specifications

### 4.1 Voice Assistant State Machine (`VoiceAssistantState`)

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> Listening : StartListening / Mic Tap / Wake Word
    
    Listening --> Listening : Partial Transcript / Live Subtitle Update
    Listening --> Processing : FinishUserSpeech / Final Transcript
    Listening --> Idle : CancelListening / Silence Timeout
    Listening --> Error : ErrorOccurred

    Processing --> Speaking : First Token / Sentence Chunk Ready
    Processing --> Error : AI Failure / Offline Timeout
    Processing --> Idle : Interrupted / Canceled

    Speaking --> Speaking : Streaming TTS Queue & Cadence Update
    Speaking --> Listening : Interrupted (Barge-In) / Playback Complete (Continuous Mode)
    Speaking --> Idle : Playback Complete (Single Mode) / StopListening
    Speaking --> Error : Audio Hardware / TTS Error

    Error --> Idle : Timeout (3s) / User Action
```

---

### 4.2 Subtitle & Holographic HUD Feed State

```mermaid
stateDiagram-v2
    [*] --> Inactive

    Inactive --> UserSpeaking : RecognitionEvent.PartialResult
    UserSpeaking --> UserSpeaking : Update currentUserSubtitle
    UserSpeaking --> JarvisThinking : RecognitionEvent.FinalResult (Processing)
    
    JarvisThinking --> JarvisSpeaking : SentenceChunker emits first speech chunk
    JarvisSpeaking --> JarvisSpeaking : Append active sentence to currentJarvisSubtitle
    JarvisSpeaking --> HistoryArchived : onUtteranceEnd / Flush to history list
    
    HistoryArchived --> UserSpeaking : Continuous listening resumes
    HistoryArchived --> Inactive : System returns to Idle
```

---

## 5. Directory & Module Navigation

```
d:\Brainy\Jarvis\
├── androidApp/                       # Android native application entry point
│   └── src/main/kotlin/co/aura/android/
│       ├── AuraApplication.kt       # Application lifecycle & Koin Android startup
│       └── MainActivity.kt          # Edge-to-edge Compose entry & Intent handling
├── desktopApp/                       # Desktop JVM entry point
│   └── src/jvmMain/kotlin/co/aura/desktop/
│       └── Main.kt                  # Desktop Window configuration & Koin JVM startup
├── shared/                           # Core Kotlin Multiplatform (KMP) logic
│   ├── src/commonMain/kotlin/co/aura/
│   │   ├── actions/                  # Action parser, router, validator, command bus
│   │   ├── ai/                       # AI providers (Gemini pool, Nvidia, Local, OpenAI)
│   │   ├── automation/               # System automation manager
│   │   ├── backend/                  # Ktor HTTP client & Supabase integration
│   │   ├── communication/            # Contact resolver & alias logic
│   │   ├── conversation/             # ContextBuilder, PersonalityEngine, ConversationManager
│   │   ├── core/                     # Koin DI, DatabaseHelper, Logger
│   │   ├── data/                     # Repository implementations & DTOs
│   │   ├── domain/                   # Domain entities, models, and repository interfaces
│   │   ├── memory/                   # MemoryManager, KeywordMemoryRetriever, MemoryExtractor
│   │   ├── phone/                    # SystemControlProvider interface
│   │   ├── plugins/                  # 11 Device Plugins (SMS, Alarm, Music, Weather, etc.)
│   │   ├── presentation/             # Compose Multiplatform UI, ViewModels, Theme, HUD
│   │   ├── security/                 # SecurityManager, PermissionManager, SecureStorage
│   │   ├── sync/                     # SyncEngine for offline-first replication
│   │   ├── vision/                   # Visual intelligence provider
│   │   └── voice/                    # Speech recognizers, TTS engines, visualizer, barge-in
│   ├── src/commonMain/sqldelight/    # SQLite Schemas (ChatHistory, LocalMemory, SyncQueue)
│   ├── src/androidMain/              # Android platform implementations (TTS, STT, Actions)
│   └── src/desktopMain/              # Desktop JVM platform implementations
```
