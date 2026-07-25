# Inkuiro

Native Android (Kotlin) AI chat client for the **BOOX Palma 2** — an e-ink phone
(6.13" carta screen, 300 ppi, Android 13). Multi-provider: OpenRouter, OpenAI,
Anthropic (Claude), Google Gemini, and a local OpenAI-compatible server
(Ollama / llama.cpp). Personal, single-user app.

The whole UI is designed for e-ink: no animations, pure black/white contrast,
reading by page jumps (with drag scrolling available, minus the inertia), and
explicit control of screen refresh modes via the Onyx SDK.

This file reflects the current state of the app.

## Build & run

The toolchain is pinned in `mise.toml` (Java 17, Gradle 8.11.1). `adb` is **not**
on PATH — use the full path.

```fish
# Build + unit tests (JVM, no emulator needed)
mise exec java@17 -- ./gradlew assembleDebug testDebugUnitTest

# Install on the Palma (must be connected; see notes below)
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch (the package stays dev.zero.inkchat despite the Inkuiro rename)
~/Android/Sdk/platform-tools/adb shell am start -n dev.zero.inkchat/.ui.chatlist.ChatListActivity
```

Test results: `app/build/test-results/testDebugUnitTest/*.xml`.

### Palma 2 / adb gotchas

- The device drops the USB connection when it sleeps — keep the screen awake
  while installing. If `adb devices` is empty, re-plug the cable (a data cable,
  not charge-only) and set the USB notification to "File transfer".
- After a fresh `adb install`, the BOOX firmware leaves the package **disabled**
  (`enabled=3`, "Activity does not exist" on launch). Run once:
  `adb shell pm enable dev.zero.inkchat`.
- Developer mode: Settings → More Settings → toggle **USB Debug Mode** (no
  7-taps-on-build-number needed on BOOX).

## Stack

- **Language**: Kotlin (JVM 17). `minSdk 30`, `targetSdk/compileSdk 34`.
- **UI**: classic Android Views + XML + ViewBinding (not Compose — Compose
  recomposes too much for e-ink). Version catalog in `gradle/libs.versions.toml`.
- **HTTP/SSE**: OkHttp 4 + `okhttp-sse`. **JSON**: kotlinx.serialization.
- **DB**: Room (KSP). **Secrets**: EncryptedSharedPreferences (AES256-GCM).
- **Markdown**: Markwon (core, ext-tables, linkify) — rendered into TextViews,
  no WebView.
- **E-ink**: `com.onyx.android.sdk:onyxsdk-device` (Onyx Maven repo, http-only,
  restricted to the `com.onyx` group — see `settings.gradle.kts`).
- **DI**: manual object graph in `AppGraph.kt` (no framework).

## Architecture

Single module, package `dev.zero.inkchat`.

```
data/
  db/        Room: Entities, ConversationDao, MessageDao, AppDatabase
  images/    ImageStore — downscales/stores attached photos as JPEG under app-internal storage
  prefs/     SecurePrefs (EncryptedSharedPreferences; the ONLY place keys live)
  provider/  AiProvider interface + ProviderRegistry + SseStream (shared SSE runner)
    compat/    OpenAiCompatProvider — OpenAI-compatible wire (OpenAI, local, base for OpenRouter)
    openrouter/ OpenRouterProvider (subclass; adds authenticated GET /key verify)
    anthropic/ AnthropicProvider (native Messages API client)
    gemini/    GeminiProvider (native generativeLanguage client)
domain/
  model/     Chat.kt (ChatRequest, ChatTurn, Role, ModelInfo)
  ChatSettings.kt   interface the repository needs; SecurePrefs implements it
  ChatRepository.kt orchestrates persistence + streaming
i18n/        Msg.kt — localized strings for Context-less layers (en/es)
ui/
  chatlist/  ChatListActivity  (home: conversation list + default-provider picker)
  chat/      ChatActivity, ChatViewModel, StreamCoalescer, PagedScrollView
  settings/  SettingsActivity, ModelPickerActivity
  common/    Markdown, TwoLine, PromptDialog (reusable EditText-in-AlertDialog helper)
  eink/      EinkRefresh — Onyx SDK facade (no-op off-device)
App.kt / AppGraph.kt
```

### Providers

All providers implement `AiProvider` (`listModels`, `verifyAuth`, `streamChat`
returning `Flow<ChatEvent>`). Two families:

- **OpenAI-compatible** (`compat/OpenAiCompatProvider`): parameterized by base
  URL, whether a key is required, and the usage flavor. OpenRouter is a subclass;
  OpenAI and the local server are plain instances (local has a configurable base
  URL and no required key). Wire DTOs in `OpenAiWire.kt`, SSE parsing in
  `OpenAiSseParser.kt`.
- **Native clients** (`anthropic/`, `gemini/`): wire-level HTTP+SSE on purpose,
  to keep the shared abstraction — we deliberately do **not** pull in the
  Anthropic Java SDK.

`SseStream.kt` holds the generic SSE lifecycle (connection, cancellation, HTTP
errors); each provider supplies a `ChatSseHandler` that interprets its payloads.
Cancelling flow collection cancels the `EventSource` — this is what the chat's
"Stop" button relies on.

Providers are wired in `AppGraph.providerRegistry`. `ChatEvent` is
`Delta | Usage | Error | Done`; `streamChat` never throws — every failure arrives
as `ChatEvent.Error(message, recoverable)`.

`AiProvider.supportsWebSearch` (default `false`, `true` for OpenRouter) gates the
chat header's web-search toggle; when on, `OpenAiCompatProvider` adds OpenRouter's
`plugins: [{"id":"web"}]` to the request.

**Vision**: `ChatTurn.imagePath` (a local file path from `ImageStore`) is optional
and only meaningful on user turns. Each provider family encodes it differently —
`ChatMessageDto.content` (OpenAI-compatible) and `AnthropicMessageDto.content`
are `JsonElement` rather than `String` so a message can be either a plain string
or a multi-part array (`[{type:"text"}, {type:"image_url"|"image"}]`); Gemini
adds an `inlineData` part alongside `text`. Images are read + base64-encoded
synchronously when the request DTO is built (same thread as JSON serialization,
not off-loaded to IO) — acceptable since `ImageStore` caps images at 1568px JPEG
q85 before they ever reach disk.

### Data model

`ConversationEntity` carries its own `providerId` + `modelId`, so changing the
default provider/model never affects existing conversations. Messages cascade-
delete with their conversation. DAOs expose `Flow` for the list and `LIMIT/OFFSET`
for pagination. `MessageEntity.imagePath` (nullable) points at a JPEG under the
app's internal `images/` dir; deleting a message (regenerate/edit-last-message)
also deletes its image file via `ChatRepository.deleteMessage`.

Schema changes use real `Migration`s (see `AppDatabase.MIGRATION_1_2`), not
`fallbackToDestructiveMigration()` — this is a single-user app but the user's
own conversation history on their physical device is real data, not disposable.

### E-ink specifics

- **`EinkRefresh`**: facade over `EpdController`. Guarded by `isOnyxDevice`
  (`Build.MANUFACTURER`/`BRAND`) so it's a no-op on emulator/JVM tests. Fast mode
  = A2 (`ANIMATION_QUALITY`) during generation/keyboard; full refresh = GC on
  reply end and screen changes.
- **`StreamCoalescer`**: the coalescing buffer for streaming. Accumulates deltas
  and repaints only on a `\n\n` paragraph break or every 1500 ms — never token by
  token. Each update carries the FULL text; the UI re-renders the whole message
  with Markwon. Tested with virtual time.
- **`PagedScrollView`**: finger dragging plus discrete page jumps via buttons,
  taps on the top/bottom third, or the Palma's physical button (`onKeyDown`
  maps PAGE_UP/DOWN + volume). Full refresh every 5 manual turns. Fling is
  overridden to a no-op — inertia keeps repainting after the finger lifts — and
  a drag runs in the fast waveform, restoring quality + full refresh on release
  (only when the content actually moved, so a tap doesn't pay for a refresh).
  **Gotcha:** message TextViews must not consume the tap, or paging silently
  dies once messages fill the screen. Markwon installs a `LinkMovementMethod`
  (a `ScrollingMovementMethod`, which grabs ACTION_DOWN unconditionally) and
  marks the view clickable — `Markdown.render()` swaps in
  `LinkOnlyMovementMethod` and clears both flags. Always render messages
  through that helper, never `markwon.setMarkdown()` directly.

### Provider selection UX

- **Home screen**: picks the *default* provider for new conversations
  (`SecurePrefs.activeProviderId`).
- **Settings**: pure provider *configuration* (key, default model, local URL).
  Its provider selector is a local "which am I configuring" state
  (`configuringProviderId`) — it does NOT change the default.
- **Inside a chat**: tap the header to change that conversation's model (via
  `ModelPickerActivity`, scoped to that conversation's provider).

### Chat header actions

- **Token usage**: `MessageDao.observeTokenUsage` sums `tokensIn`/`tokensOut`
  for the conversation; shown under the model name once non-zero.
- **Search**: `ChatListActivity` filters in-memory over title + modelId; no DB
  query, the conversation list is already fully loaded.
- **Font size**: `SecurePrefs.messageFontSizeSp`, applied in
  `ChatActivity.newMessageView()`; changing it in Settings forces a full
  re-render on the next `onResume()` (`renderedIds.clear()`, since
  `renderMessages()` otherwise skips unchanged id lists).
- **Regenerate / edit last message**: header **⋯** button
  (`ChatViewModel.regenerateLastResponse()` / `editLastUserMessage()`), only
  shown when there's something to act on. Deliberately not long-press-on-message
  — that would need `isLongClickable = true`, which is exactly what the
  tap-to-page-turn fix above depends on being `false`.

## Localization

`values/strings.xml` is **English** (the fallback); `values-es/` is Spanish.
`resourceConfigurations = [en, es]` strips every other locale. Labels follow the
system language. For Context-less layers (providers, ViewModel), `i18n/Msg.kt`
resolves en/es from `Locale.getDefault()` at call time. **All source comments,
KDoc, and test names are in English**; the only Spanish is localization data
(`values-es/` and the literals inside `Msg.kt`).

## Security

- API keys live **only** in EncryptedSharedPreferences, keyed per provider.
- The `OkHttpClient` has **no logging interceptor** on purpose, so the
  `Authorization` header can never reach a log.
- `allowBackup=false`. The Onyx SDK's manifest injects WiFi/BT/DUMP permissions
  and `allowBackup=true`; both are stripped via `tools:node="remove"` /
  `tools:replace`. `usesCleartextTraffic=true` is needed for local http servers.

## Provider API notes

- **OpenRouter**: `GET /models` is **public** — it does not validate the key.
  "Test connection" uses the authenticated `GET /key`. 401 "No cookie auth
  credentials found" = missing/malformed Authorization header (e.g. a key stored
  with "Bearer " glued on); "User not found." = well-formed but nonexistent key.
  The Settings key field normalizes all of this and pre-fills `sk-or-v1-`.
- **Anthropic**: `x-api-key` + `anthropic-version: 2023-06-01` headers,
  `max_tokens` is **required** (defaults to 4096), system prompt is a top-level
  field (not a message), SSE events are `message_start`/`content_block_delta`/
  `message_delta`/`message_stop`.
- **Gemini**: `x-goog-api-key`, roles `user`/`model`, `systemInstruction`
  separate, streaming via `:streamGenerateContent?alt=sse` with **no `[DONE]`** —
  the final chunk carries `finishReason`.

## Conventions

- Commit/PR when asked; branch off before committing on the default branch.
- Run `assembleDebug testDebugUnitTest` before considering a change done; verify
  behavior on the Palma for anything with a runtime surface.
- Keep new code in the existing idiom (manual DI, `Flow`-based providers,
  English comments only). Don't add dependencies outside the pinned stack without
  reason. Prioritize simplicity — it's a single-user app.
