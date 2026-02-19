# Enduro Coach — Kotlin MVP

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/manol-dimitrov/strava-data-analyser?quickstart=1)

AI-powered endurance coaching built on the **Banister impulse-response model** — ingests training data from Strava, computes physiological load metrics, and generates structured workout prescriptions via Google Gemini.

## Architecture

```
app/              Ktor server, dashboard routes, composition root
core-domain/      Shared domain models & provider-agnostic contracts
core-metrics/     Banister TRIMP calculator, CTL/ATL/TSB series, demo fallback
core-llm/         Strict structured-output validator + retry orchestration
infra-data/       AES-256-GCM encrypted local token store
infra-strava/     Strava OAuth2, activity fetch, 1-hour cache
infra-llm/        Gemini adapter (default), Perplexity adapter (opt-in)
```

## Features

- **Strava OAuth2 integration** — connects to athlete's account, fetches last 45 days of running activities with heart rate data
- **Banister impulse-response modelling** — computes TRIMP (heart-rate-weighted training impulse), CTL (42-day fitness EMA), ATL (7-day fatigue EMA), and TSB (form = CTL − ATL)
- **Interactive dashboard** with:
  - Contextual metric cards with badges and two-layer explanations (dynamic interpretation + static reference)
  - SVG training load chart (CTL, ATL, TSB curves over analysis window)
  - Banister method explainer — scientific background, theory, formulas
  - Daily subjective check-in (leg feeling, mental readiness, time available, coaching philosophy)
  - Structured workout prescription with warm-up, main set, cool-down, and coach reasoning
- **Gemini 2.5 Flash** structured output — requests `application/json` MIME type, validates exact JSON schema, retries once on schema failure
- **Elite coach persona** — direct, no-sycophancy prompts requiring physiological justification for every prescription
- **Graceful fallback** — demo data when Strava is unconfigured or unavailable

## Environment Variables

### Google Gemini (default LLM)
| Variable | Required | Default |
|---|---|---|
| `GEMINI_API_KEY` | Yes | — |
| `GEMINI_MODEL` | No | `gemini-2.5-flash` |
| `GEMINI_BASE_URL` | No | `https://generativelanguage.googleapis.com` |

### Perplexity (opt-in alternative)
Set `enduroCoach.llm.provider = "perplexity"` in `application.conf`, then:
| Variable | Required | Default |
|---|---|---|
| `PERPLEXITY_API_KEY` | Yes | — |
| `PERPLEXITY_MODEL` | No | `sonar` |
| `PERPLEXITY_BASE_URL` | No | `https://api.perplexity.ai` |

### Strava
| Variable | Required | Default |
|---|---|---|
| `STRAVA_CLIENT_ID` | Yes | — |
| `STRAVA_CLIENT_SECRET` | Yes | — |
| `STRAVA_REDIRECT_URI` | Yes | — |

For GitHub Codespaces, set redirect URI to `https://<codespace-name>-8080.app.github.dev/api/strava/exchange`.

### Token Encryption
| Variable | Required | Default |
|---|---|---|
| `ENDURO_TOKEN_KEY` | No | Machine-local derived key |

OAuth tokens are stored encrypted (AES-256-GCM) under `~/.enduro-coach/`.

## Quick Start with GitHub Codespaces

The fastest way to try this — no local setup required:

1. **Get API keys** (both free):
   - [Google Gemini API key](https://aistudio.google.com/apikey)
   - [Strava API application](https://www.strava.com/settings/api) — set **Authorization Callback Domain** to `<your-codespace-name>-8080.app.github.dev`

2. **Click "Open in Codespaces"** above (or go to the repo → Code → Codespaces → New)

3. **Add secrets** — Codespaces will prompt for these on first launch:
   - `GEMINI_API_KEY`
   - `STRAVA_CLIENT_ID`
   - `STRAVA_CLIENT_SECRET`

4. **Wait for build** — the devcontainer auto-runs `gradle build` and starts the app on port 8080

5. **Open the dashboard** — Codespaces auto-opens the forwarded port in your browser. The Strava redirect URI is auto-configured.

> **Without any keys?** The app still works — it uses demo training data and shows a graceful error for workout generation. You can explore the dashboard, metrics, and Banister model explainer.

## Run Locally

```bash
# Build
gradle build

# Start (with env vars)
GEMINI_API_KEY="..." \
STRAVA_CLIENT_ID="..." \
STRAVA_CLIENT_SECRET="..." \
STRAVA_REDIRECT_URI="http://localhost:8080/api/strava/exchange" \
gradle :app:run

# Open dashboard
open http://localhost:8080/
```

## Strava Connection

1. Visit `http://localhost:8080/api/strava/connect` (or click "Connect Strava" on the dashboard)
2. Authorize on Strava's OAuth page
3. Callback redirects to dashboard — load metrics switch from demo to real data

## Tests

34 unit tests across 5 test classes:
- `BanisterTrimpCalculatorTest` (7) — TRIMP edge cases, HR zones, duration scaling
- `LoadSeriesServiceTest` (8) — CTL/ATL/TSB series computation, empty/single-day cases
- `StructuredWorkoutValidationTest` (8) — exact key enforcement, missing/extra/blank keys
- `StructuredWorkoutGeneratorTest` (5) — retry on schema error, pass-through on success
- `EncryptedFileTokenStoreTest` (6) — encrypt/decrypt round-trip, key derivation

```bash
gradle test
```

## Design Notes

- Workout generation enforces exact JSON keys: `warmup`, `main_set`, `cooldown`, `coach_reasoning`
- Gemini 2.5 Flash (thinking model) — response parsing filters out thought parts automatically
- No full auth/session model; MVP assumes single local operator flow
- Strava activity cache is 1 hour to avoid API rate limits
