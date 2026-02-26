# Configuration as Code

This directory contains environment variable templates for different deployment targets.

## Railway Deployment

**File**: `railway.env.json`

Copy this file, fill in your actual values, then paste the JSON into Railway's **Raw Editor** in the Variables tab:

1. Go to your Railway project → Service → Variables tab
2. Click **Raw Editor** (top right)
3. Paste the entire JSON object
4. Update `STRAVA_REDIRECT_URI` to match your Railway domain: `https://<your-app>.up.railway.app/api/strava/exchange`
5. Click **Update Variables**

**Note**: Railway auto-injects `PORT` — do not add it manually.

### Persistent Storage (required to survive redeployments)

Without a persistent volume, onboarding profiles and OAuth tokens are stored on the container's ephemeral filesystem and are wiped on every redeploy.

To fix this:

1. In the Railway dashboard → your service → **Volumes** tab, add a volume and set the mount path to `/data`.
2. Add `DATA_DIR=/data` to your Railway environment variables.

Railway will then write all `.enc` files under `/data/.enduro-coach/`, which persists across deploys.

## Local Development

**File**: `../.env.example`

Copy to `.env` in the repo root and fill in your values:

```bash
cp .env.example .env
# Edit .env with your API keys
```

Then source it before running:

```bash
source .env
./gradlew :app:run
```

Or use an env-loader tool like `direnv` or your IDE's run configuration.

## Required Values

| Variable | Where to get it | Required for |
|---|---|---|
| `GEMINI_API_KEY` | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | Workout generation |
| `STRAVA_CLIENT_ID` | [strava.com/settings/api](https://www.strava.com/settings/api) | Strava OAuth |
| `STRAVA_CLIENT_SECRET` | [strava.com/settings/api](https://www.strava.com/settings/api) | Strava OAuth |
| `STRAVA_REDIRECT_URI` | Your app URL + `/api/strava/exchange` | OAuth callback |
| `ENDURO_TOKEN_KEY` | Leave empty for auto-generation | Token encryption |
| `DATA_DIR` | Railway volume mount path (e.g. `/data`) | Persistent storage across redeploys |

## Application Config (HOCON)

Core app behavior is configured in `app/src/main/resources/application.conf`:

- LLM provider/model defaults
- Coaching philosophy rule packs
- Port binding (with `$PORT` override for Railway)

**These are versioned** and should be edited via pull request, not runtime env vars.
