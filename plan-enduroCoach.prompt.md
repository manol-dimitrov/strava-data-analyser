## Plan: Maestro — AI Endurance Coach (Kotlin MVP)

> **Brand**: Maestro  
> **Tagline**: "Your AI Endurance Coach"  
> **Voice**: Elite strategist — authoritative, high-performance guidance  
> **Visual direction**: Premium dark  
> **Package root (pending)**: `com.maestrocoach`  
> **Project slug (pending)**: `maestro`  

### Rebrand Status: LOCKED — pending implementation

The rebrand from "Enduro Coach" to **Maestro** is approved and will be implemented in a future phase. The scope is a full technical rename including UI copy, runtime identifiers, Kotlin package namespace, Gradle project/group naming, and env/config keys. A compatibility window will be maintained for one release (old + new cookie/session/token-dir names accepted in parallel during transition).

#### Rebrand Implementation Phases (future)

**Phase 1 — Low risk: UI copy + visual refresh**
- Update dashboard title, topbar identity, hero messaging, and feature microcopy to Maestro branding
- Redesign CSS tokens for premium-dark palette with elite strategist tone
- Reframe feature language to AI actions: predict, adapt, recommend, brief
- Update README, docs, and devcontainer display name

**Phase 2 — Medium risk: runtime identifiers**
- Rename cookie: `enduro_session` → `maestro_session` (dual-read for one release)
- Rename token store directory: `~/.enduro-coach/` → `~/.maestro/` (dual-read for one release)
- Rename OAuth state default: `enduro-coach` → `maestro`
- Rename config namespace: `enduroCoach.*` → `maestro.*` (no aliases — hard rename)
- Rename env vars: `ENDURO_TOKEN_KEY` → `MAESTRO_TOKEN_KEY` (no aliases)
- Update health/banner text

**Phase 3 — High risk: namespace + build rename (isolated, compile-gated)**
Module-by-module migration with compile check after each:
1. `core-domain`: move `com.endurocoach.domain` → `com.maestrocoach.domain`, compile gate
2. `core-metrics`: move + update imports, compile gate
3. `core-llm`: move + update imports, compile gate
4. `infra-data`: move + update imports, compile gate + run data tests
5. `infra-strava`: move + update imports, compile gate
6. `infra-llm`: move + update imports, compile gate
7. `app`: move + update all imports + mainClass FQCN + application.conf module ref, full build + test gate
8. Gradle: rename `rootProject.name`, `group`, verify `installDist` and distribution scripts

**Phase 4 — Verification**
- `./gradlew clean test` passes all modules
- Manual smoke test: dashboard loads, demo data renders, Strava OAuth flow completes, workout generates
- Confirm old session/token artifacts are still readable during compatibility window
- Confirm new writes use Maestro identifiers
- Confirm renamed env/config keys are required (no old-key fallback)

---

### Original MVP Plan

Build a Kotlin-first web app that delivers the core coaching workflow: Strava ingestion, Banister load modeling (TRIMP/CTL/ATL/TSB), daily subjective check-in, and strict structured workout generation. The architecture uses Ktor for the server, a provider-agnostic LLM boundary (`LlmStructuredClient`), and Google Gemini as the primary adapter (free tier, up to 15 RPM). The design prioritizes fast MVP delivery, deterministic schema validation, and safe rerun behavior (cache Strava pulls, persist generated workout so UI edits do not trigger new LLM calls).

### Modules

| Module | Purpose |
|---|---|
| `app` | Ktor server, dashboard routes, composition root, config |
| `core-domain` | Shared value objects (`Activity`, `WorkoutPlan`, …) and contracts (`LlmStructuredClient`, `ActivityRepository`, `TokenStore`) |
| `core-metrics` | TRIMP calculator, CTL/ATL/TSB EMA engine, deterministic demo-data generator |
| `core-llm` | Provider-agnostic strict JSON schema validator + retry orchestrator |
| `infra-data` | AES-256-GCM encrypted local token store (`~/.enduro-coach/`) |
| `infra-strava` | Strava OAuth2 flow, activity fetch, 1-hour fetch cache |
| `infra-llm` | Gemini adapter (primary); legacy Perplexity adapter retained but not default |

### Steps

1. **Project scaffold** *(done)* — Initialize Kotlin multi-module project in [settings.gradle.kts](settings.gradle.kts), [build.gradle.kts](build.gradle.kts), [gradle/libs.versions.toml](gradle/libs.versions.toml).
2. **Domain models & contracts** *(done)* — `Activity`, `DailyCheckIn`, `LoadSnapshot`, `WorkoutPlan`, `WorkoutRequest`, plus interfaces `ActivityRepository`, `LlmStructuredClient`, `TokenStore` in [core-domain/src/main/kotlin/com/endurocoach/domain](core-domain/src/main/kotlin/com/endurocoach/domain).
3. **Training-load engine** *(done)* — `BanisterTrimpCalculator`, `LoadSeriesService` (42-day CTL EMA, 7-day ATL EMA, TSB = CTL − ATL), and 45-day demo-data fallback in [core-metrics](core-metrics/src/main/kotlin/com/endurocoach/metrics).
4. **Strava integration** *(done)* — OAuth2 + last-45-day fetch in [infra-strava](infra-strava/src/main/kotlin/com/endurocoach/strava), encrypted token store in [infra-data](infra-data/src/main/kotlin/com/endurocoach/data/EncryptedFileTokenStore.kt), 1-hour cache.
5. **Strict schema validator & retry** *(done)* — `StructuredWorkoutValidation` + `StructuredWorkoutGenerator` in [core-llm](core-llm/src/main/kotlin/com/endurocoach/llm): enforce exact keys `warmup`, `main_set`, `cooldown`, `coach_reasoning`; auto-retry once on invalid schema; then typed error.
6. **Google Gemini adapter** — Implement `GeminiClient` in [infra-llm/src/main/kotlin/com/endurocoach/llm/GeminiClient.kt](infra-llm/src/main/kotlin/com/endurocoach/llm/GeminiClient.kt) using the Gemini REST `generateContent` endpoint. Config via `GEMINI_API_KEY` env var; default model `gemini-2.5-flash` (free tier). Request `application/json` response MIME type for reliable structured output. Retain the existing Perplexity adapter as an opt-in alternative (`provider = "perplexity"` in config).
7. **Dashboard UI** *(done)* — Server-rendered HTML dashboard at `/` in [app/src/main/kotlin/com/endurocoach/routes/DashboardRoutes.kt](app/src/main/kotlin/com/endurocoach/routes/DashboardRoutes.kt) and [app/src/main/resources/templates/dashboard.html](app/src/main/resources/templates/dashboard.html): top metrics row, daily check-in form, generate button, workout card, persisted latest workout in server state.
8. **Composition & config** *(done)* — Wiring in [Application.kt](app/src/main/kotlin/com/endurocoach/Application.kt) and [application.conf](app/src/main/resources/application.conf): provider selection (`gemini` default), model/env/baseUrl config, coaching philosophy rule packs. Update defaults from Perplexity → Gemini.
9. **README & runbook** — Update [README.md](README.md) with Gemini env vars, setup steps, fallback/demo behavior, and MVP limitations.

### Verification

- `gradle build` compiles all modules cleanly.
- Manual flow:
  1. Start app → dashboard renders with demo-data metrics (no Strava).
  2. Strava OAuth → 45-day pull → cache hit within 1 hour.
  3. Submit check-in → generate workout → schema-valid card rendered; page reload preserves workout.
  4. Force malformed LLM response → one retry → graceful error card.
- Focused tests (future): TRIMP math, EMA series, schema validator, retry policy, token store I/O.

### Decisions

- **Stack**: Kotlin + Ktor (replacing Python/Streamlit).
- **LLM provider**: Google Gemini (`gemini-2.5-flash`, free tier) as default. Perplexity retained as opt-in.
- **Structured output**: Gemini `responseMimeType: "application/json"` plus `StructuredWorkoutValidation` double-check; auto-retry once.
- **Strava scope**: OAuth + last-45-day fetch in MVP.
- **Training model**: Banister HR-based TRIMP with CTL/ATL EMA and TSB.
- **Naming (current)**: Enduro Coach (human), enduro-coach (slugs), `com.endurocoach` (packages).
- **Naming (approved, pending)**: Maestro (human), maestro (slugs), `com.maestrocoach` (packages). Tagline: "Your AI Endurance Coach".
- **Deployment**: Railway via `railway.toml`; port reads `$PORT` env var; Strava redirect URI set per deployment target.