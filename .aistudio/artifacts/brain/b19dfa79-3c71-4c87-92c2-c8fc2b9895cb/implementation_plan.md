# Aether — Unified Gemini 3.8 Cognitive Workspace & Multimodal Upgrade

Aether will transition to a pure, zero-configuration Gemini architecture powered automatically by the platform's built-in `GEMINI_API_KEY` and **Gemini 3.8 Flash** (`gemini-3.8-flash`), removing all external third-party providers (Oxion, Groq, OpenRouter) and manual API key setup clutter while preserving 100% of existing cognitive features and introducing Multimodal Vision analysis and Voice Input & Spoken Responses.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following product and architectural choices have been updated per your feedback and will govern the implementation once you approve this plan:

- **Confirmed Decision 1 — Pure Gemini 3.8 Engine (`Gemini 3.8 Flash` & `Pro`)**: All third-party providers (Oxion, Groq, OpenRouter) are removed. Aether uses the built-in `GEMINI_API_KEY` automatically via `BuildConfig` / environment injection, powered by **Gemini 3.8 Flash** (`gemini-3.8-flash`) as the primary high-speed engine with an in-app toggle for **Pro** reasoning and smart endpoint fallback so requests never fail with HTTP 404.
- **Confirmed Decision 2 — Zero-Clutter Header & Settings UI**: The multi-provider dropdown, API Key setup warning banners, runtime diagnostic card, and manual API key configuration dialogs are removed so the workspace works immediately on launch.
- **Confirmed Decision 3 — 100% Preservation of Existing Aether Features**: Every built-in system—Long-Term Memory extraction & recall, Local File Search context, Autonomous Tool Calling & Google Search Grounding, Incognito Workspace isolation, Language & Communication Style adaptation, Conversation Continuity resolver, and Proactive Delivery—remains fully intact and wired directly to Gemini 3.8 Flash.
- **Confirmed Decision 4 — New Multimodal Vision & Voice Capabilities**: Users gain native image attachment via Android Photo Picker for multimodal vision analysis alongside integrated Speech-to-Text (voice input) and Text-to-Speech (spoken assistant responses).

---

### 1. Overview & Core Concept

- **What It Does**: Aether acts as an autonomous, minimalist cognitive workspace powered exclusively by Google Gemini (**Gemini 3.8 Flash** / **Pro**). It combines real-time SSE streaming, grounded web search, long-term personal memory, local file indexing, multimodal vision inspection, and hands-free voice interaction without requiring any manual API key entry.
- **Target Audience / Persona**: Power users, developers, and researchers who want an instant, distraction-free personal AI workspace with deep context awareness and zero configuration friction.
- **Key Value**: Eliminates third-party proxy errors and key-management friction while unlocking native Gemini 3.8 Flash speed, 1M+ token context window, native function calling, Google Search grounding, and multimodal image + voice workflows.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Zero-Config Launch & Model Toggle**: User opens Aether and lands directly in the clean workspace—no API key banners or setup modals. A sleek top-bar pill lets the user switch effortlessly between **3.8 Flash** (default) and **3.8 Pro**.
  2. **Multimodal Vision Flow**: User taps the attachment icon in the composer bar to pick an image via the zero-permission Android Photo Picker (`PickVisualMedia`) or attach a workspace document. A compact thumbnail preview appears above the input field with a remove button; sending the prompt streams Gemini 3.8's visual analysis.
  3. **Voice Input & Spoken Response Flow**: User taps the microphone button in the composer bar to dictate a message in Hindi, Hinglish, or English with real-time waveform feedback, and can tap the speaker icon on any assistant message (or enable auto-speak) to hear Aether read the response aloud.
  4. **Preserved Core Flows**: Drawer navigation for Recent Chats, Memory Vault management, Local File Search indexing, Incognito Mode toggle, and Dark/Light theme switching continue working seamlessly.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Bold, high-contrast architectural minimalism ("Autonomous Cognitive Workspace") with zero visual noise.
  - *Color Palette & Mood*: Deep obsidian dark surfaces (`#0A0A0C` background, `#121318` elevated cards) paired with crisp alabaster light mode surfaces, sharp electric indigo/cyan accents for active Gemini 3.8 Flash/Pro indicators, and subtle emerald status cues for active voice/tool execution.
  - *Typography & Hierarchy*: Clean geometric sans-serif hierarchy with wide-tracked uppercase headers (`A E T H E R`, `letterSpacing = 4.sp`), readable `15.sp` body copy with `22.sp` line height, and monospace code blocks.
  - *Component Styling & Layout*: Edge-to-edge layout (`enableEdgeToEdge()`) respecting status bar and navigation bar safe areas (`WindowInsets`), 48.dp minimum touch targets, and subtle `1.dp` translucent borders.

- **Interactive Feedback & Motion**:
  - Smooth spring transitions (`spring()`) when toggling between Gemini 3.8 Flash and Pro or entering Incognito Mode.
  - Animated pulsing indicator during active voice recording and TTS playback.
  - Live streaming token reveal with automatic scroll anchoring.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Exclusive Gemini 3.8 Flash Engine with Pre-Configured Environment Key & Smart Endpoint Resolution**
  - *Chosen Approach*: Route all requests through Gemini's official REST/SSE endpoints using `gemini-3.8-flash` (and `gemini-3.8-pro`) with automatic endpoint verification and fallback using the platform-injected `GEMINI_API_KEY`.
  - *Why*: Delivers the latest Gemini 3.8 Flash speed and intelligence while guaranteeing zero HTTP 404/401 errors, native tool calling, Google Search grounding, and multimodal vision support.
  - *Alternatives Considered*: Keeping multi-provider routing (Oxion/Groq/OpenRouter) was rejected because it introduced external key management overhead and incompatible endpoint behaviors.

- **Decision 2: Native Android Photo Picker & Speech APIs**
  - *Chosen Approach*: Use Android's `ActivityResultContracts.PickVisualMedia` for zero-permission image selection (Base64 inline data to Gemini) and Android's native `SpeechRecognizer` + `TextToSpeech` engines for voice interaction.
  - *Why*: Respects Google Play privacy policies (no broad storage permissions required), works reliably across devices, and keeps APK size fast and lightweight.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         AETHER COMPOSE UI LAYER                           │
│  ┌─────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │  Top Bar            │  │  Chat Workspace      │  │  Navigation      │  │
│  │  • 3.8 Flash / Pro  │  │  • Streaming Bubbles │  │  Drawer          │  │
│  │  • Incognito Toggle │  │  • TTS Speaker Action│  │  • Recent Chats  │  │
│  └──────────┬──────────┘  └──────────┬───────────┘  │  • Memory Vault  │  │
│             │                        │              │  • Theme Toggle  │  │
│  ┌──────────┴────────────────────────┴───────────┐  └────────┬─────────┘  │
│  │  Multimodal & Voice Composer Bar              │           │            │
│  │  • Photo Picker Preview • Mic Input • Prompt  │           │            │
│  └──────────────────────────┬────────────────────┘           │            │
└─────────────────────────────┼────────────────────────────────┼────────────┘
                              ▼                                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                    VIEWMODEL & COGNITIVE ORCHESTRATION                    │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │  ChatViewModel                                                      │  │
│  │  • Gemini Model State (3.8 Flash / 3.8 Pro)                         │  │
│  │  • Image Attachment State (Inline Base64 + MIME)                    │  │
│  │  • Voice Recognition & TTS Playback Controller                      │  │
│  └───────────────┬───────────────────────────────────┬─────────────────┘  │
│                  ▼                                   ▼                    │
│  ┌──────────────────────────────────┐  ┌───────────────────────────────┐  │
│  │  Preserved Cognitive Pipeline    │  │  Persistence & Context        │  │
│  │  • Language & Style Detector     │  │  • Room Conversation DB       │  │
│  │  • Communication Preferences     │  │  • Long-Term Memory Service   │  │
│  │  • Conversation Context Resolver │  │  • Local File Search Repo     │  │
│  │  • Tool Orchestrator & WebSearch │  │  • Proactive Delivery Engine  │  │
│  └───────────────┬──────────────────┘  └───────────────────────────────┘  │
└──────────────────┼────────────────────────────────────────────────────────┘
                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                        GEMINI 3.8 EXECUTION LAYER                         │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │  GeminiProvider & GeminiApiService (SSE Streaming + Function Calls) │  │
│  │  • Dynamic Model Endpoint: gemini-3.8-flash / gemini-3.8-pro        │  │
│  │  • Built-in GEMINI_API_KEY via BuildConfig & System Environment     │  │
│  │  • Multimodal Parts (Text + InlineData Image) + Grounding Tools     │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - `GeminiModelVariant`: Enum representing `FLASH_3_8("gemini-3.8-flash", "3.8 Flash")` and `PRO_3_8("gemini-3.8-pro", "3.8 Pro")`, persisted in preferences (and isolated in-memory during Incognito sessions).
  - `ImageAttachment`: Holds selected image URI, MIME type (`image/jpeg` / `image/png`), and Base64-encoded payload passed into `ProviderRequest` and Gemini's `inlineData` `Part`.
  - `VoiceState`: Tracks `Idle`, `Listening`, and `Speaking(messageId)` states for seamless UI feedback.

- **Interactive Component & State Mapping**:
  - **3.8 Flash / Pro Model Switcher**: Tapping the header pill toggles between `gemini-3.8-flash` and `gemini-3.8-pro`, immediately updating `ChatViewModel` and routing subsequent requests to the selected Gemini 3.8 model endpoint.
  - **Multimodal Image Picker**: Selecting an image compresses and encodes it off the main thread (`Dispatchers.IO`), displays a removable preview chip in the composer, and attaches the image payload to `ProviderRequest` so Gemini 3.8 analyzes both image and text together.
  - **Voice Dictation & Spoken Replies**: Tapping the mic button checks `RECORD_AUDIO` runtime permission, streams recognized speech into the input field, and tapping the speaker icon on any assistant message synthesizes natural speech via `TextToSpeech`.
  - **Preserved Features & Tests**: All unit and Robolectric tests are updated to validate the pure Gemini 3.8 architecture, automatic key resolution, and preserved cognitive pipeline.
