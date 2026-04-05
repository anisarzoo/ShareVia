# ShareVia Signaling + Realtime Server (V2)

This service now supports both:
- Legacy PeerJS signaling (`PEER_PATH`) for existing clients.
- V2 API + realtime hub for native Android/iOS/Windows + web/extension parity.

## Start locally
1. `cd signal-server`
2. `npm install`
3. Copy `.env.example` to `.env` and adjust values.
4. `npm run start`

## Health checks
- `http://<host>:9000/health`
- `http://<host>:9000/ready`

## V2 API surface
- `POST /v2/auth/magic-link/request`
- `POST /v2/auth/magic-link/verify`
- `POST /v2/device/link/start`
- `POST /v2/device/link/confirm`
- `GET /v2/config/ice`
- `POST /v2/telemetry/ingest`
- `GET /v2/telemetry/recent`
- `WS /v2/realtime`

## Core environment
- `PORT`
- `HOST`
- `PEER_PATH`
- `REALTIME_PATH`
- `TRUST_PROXY`
- `CORS_ORIGIN`
- `RATE_LIMIT`
- `RATE_LIMIT_WINDOW_MS`

## Auth + device-link environment
- `MAGIC_LINK_TTL_MS`
- `DEVICE_LINK_TTL_MS`
- `AUTH_DEV_ECHO_TOKEN` (local dev only)

## ICE environment
- `ICE_STUN_URLS`
- `ICE_TURN_URLS`
- `ICE_TURN_USERNAME`
- `ICE_TURN_CREDENTIAL`
- `ICE_TTL_SEC`

## Production recommendations
- Deploy this process on always-on infrastructure (Render/Fly/Railway/VPS).
- Put server behind TLS proxy (Nginx/Caddy/Traefik) where possible.
- Set `TRUST_PROXY=true` behind reverse proxy.
- Restrict `CORS_ORIGIN` to exact app origins (comma-separated).
- Keep `ALLOW_DISCOVERY=false` unless peer listing is explicitly needed.
- Use proper email delivery service for magic-link messages (current flow is stubbed for backend integration).
- Monitor `telemetry/recent` and external logs for fallback ratio, transfer failures, and reconnect spikes.

## Optional direct TLS
You can run HTTPS directly in Node with:
- `ENABLE_HTTPS=true`
- `TLS_KEY_FILE=<path>`
- `TLS_CERT_FILE=<path>`
- `TLS_CA_FILE=<path>` (optional)

