## Plan: Adaptive Edge Kotlin MVP (DRAFT)

Build a Kotlin-first web app that preserves the core coaching workflow: Strava ingestion, Banister load modeling (TRIMP/CTL/ATL/TSB), daily subjective check-in, and strict structured workout generation. Because you chose Kotlin-only + Perplexity-only, this plan replaces the original Python/Streamlit module contract with a Ktor-based architecture and a provider-agnostic LLM boundary implemented with a Perplexity adapter first. The design prioritizes fast MVP delivery, deterministic schema validation, and safe rerun behavior (cache Strava pulls, persist generated workout so UI edits do not trigger new LLM calls).

**Steps**
1. Initialize Kotlin multi-module project scaffold and dependencies in [settings.gradle.kts](settings.gradle.kts), [build.gradle.kts](build.gradle.kts), [gradle/libs.versions.toml](gradle/libs.versions.toml), with modules for app, domain, metrics, Strava integration, and LLM integration.
2. Define core domain models and contracts in [core-domain/src/main/kotlin/com/adaptiveedge/domain](core-domain/src/main/kotlin/com/adaptiveedge/domain) for `Activity`, `DailyCheckIn`, `LoadSnapshot`, `WorkoutPlan`, `WorkoutRequest`, plus interfaces `ActivityRepository`, `LlmStructuredClient`, and `TokenStore`.
3. Implement training-load engine in [core-metrics/src/main/kotlin/com/adaptiveedge/metrics](core-metrics/src/main/kotlin/com/adaptiveedge/metrics) with `BanisterTrimpCalculator`, `LoadSeriesService` (42-day CTL EMA, 7-day ATL EMA, TSB = CTL - ATL), and deterministic 45-day demo-data generator fallback when Strava is unavailable.
4. Implement Strava OAuth2 + last-45-day fetch in [infra-strava/src/main/kotlin/com/adaptiveedge/strava](infra-strava/src/main/kotlin/com/adaptiveedge/strava), with local encrypted token storage in [infra-data/src/main/kotlin/com/adaptiveedge/data/EncryptedFileTokenStore.kt](infra-data/src/main/kotlin/com/adaptiveedge/data/EncryptedFileTokenStore.kt) and a 1-hour fetch cache service.
5. Implement Perplexity adapter in [infra-llm/src/main/kotlin/com/adaptiveedge/llm/PerplexityClient.kt](infra-llm/src/main/kotlin/com/adaptiveedge/llm/PerplexityClient.kt), plus shared strict validator/retry orchestration in [core-llm/src/main/kotlin/com/adaptiveedge/llm](core-llm/src/main/kotlin/com/adaptiveedge/llm): enforce exact JSON keys `warmup`, `main_set`, `cooldown`, `coach_reasoning`; retry once on invalid schema; then emit typed error.
6. Build Ktor UI routes and server-rendered dashboard in [app/src/main/kotlin/com/adaptiveedge/routes/DashboardRoutes.kt](app/src/main/kotlin/com/adaptiveedge/routes/DashboardRoutes.kt) and [app/src/main/resources/templates/dashboard.html](app/src/main/resources/templates/dashboard.html): sidebar settings, top metrics row, daily check-in form, generate button, workout output card, and persisted latest workout in server session/state store.
7. Add composition/config wiring in [app/src/main/kotlin/com/adaptiveedge/Application.kt](app/src/main/kotlin/com/adaptiveedge/Application.kt) and [app/src/main/resources/application.conf](app/src/main/resources/application.conf), including provider/model/env config and coaching philosophy rule packs.
8. Document setup and runbook in [README.md](README.md): env vars (Strava + Perplexity), local token storage behavior, fallback/demo mode behavior, and MVP limitations.

**Verification**
- Run `./gradlew build` and `./gradlew test`.
- Manual flow checks:
  - Start app, load dashboard, verify metrics render from demo data when Strava not connected.
  - Complete Strava OAuth, confirm 45-day pull and cache hit behavior within 1 hour.
  - Submit check-in, generate workout, verify schema-valid rendering and persisted workout on page interactions.
  - Force malformed LLM response in test/stub, confirm one retry then graceful error card.
- Add focused tests in module test folders for TRIMP math, EMA calculations, schema validator, retry policy, and token store read/write.

**Decisions**
- Chosen stack: Kotlin architecture (replacing Python/Streamlit structure).
- LLM provider: Perplexity only for MVP.
- Strava scope: include OAuth + last-45-day fetch in MVP.
- Training model: Banister HR-based TRIMP with CTL/ATL EMA and TSB.
- Invalid structured output policy: auto-retry once, then graceful failure.
