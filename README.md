# NEPSE Pulse V20 — Self-Hosted Central Feed

This build keeps the existing NEPSE Pulse mobile UI and replaces the unreliable free hosted API dependency with a self-hosted FastAPI feed service.

## Architecture

NEPSE public frontend data → `nepsepy` backend → `/api/market` → NEPSE Pulse

The backend uses `nepsepy==1.0.2`, a current public/read-only NEPSE client. It performs the public session bootstrap used by the NEPSE frontend and does not require user credentials. It is unofficial and data may be delayed/corrected.

## Deploy backend

### Render
1. Create a new Web Service from this package/repository.
2. Set the service root directory to `backend`.
3. Render will use `backend/Dockerfile`.
4. Wait for `/health` to return JSON with `status: healthy`.

### Docker

`cd backend`

`docker build -t nepse-pulse-feed .`

`docker run -p 8000:8000 nepse-pulse-feed`

Then open `http://localhost:8000/health` and `/api/market`.

## Connect the Android app

After the backend has an HTTPS URL, edit `config.js`:

`window.NEPSE_PULSE_BACKEND_URL = 'https://YOUR-BACKEND-URL';`

Then deploy the frontend folder to Netlify/GitHub Pages or another HTTPS host.

The frontend still retains its existing UI and local cache. No demo market values are generated.

## Important

The backend does not claim that 461 rows are always returned. `461` is kept as the listed-security universe reference; `coveredRows` reports how many records the upstream actually returned. Missing data remains missing rather than being filled with fake zeros.
