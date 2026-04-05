# ShareVia V2 API Contract

## Base
- `basePath`: `/v2`
- `content-type`: `application/json`

## Auth
- `POST /v2/auth/magic-link/request`
  - request: `{ "email": "string", "deviceId": "string", "platform": "string" }`
  - response: `{ "ok": true, "requestId": "string", "expiresInSec": 900 }`
- `POST /v2/auth/magic-link/verify`
  - request: `{ "requestId": "string", "token": "string", "deviceId": "string" }`
  - response: `{ "ok": true, "sessionToken": "string", "profile": { "userId": "string", "email": "string" } }`

## Device Linking
- `POST /v2/device/link/start`
  - request: `{ "deviceId": "string", "platform": "string", "label": "string" }`
  - response: `{ "ok": true, "linkCode": "string", "expiresInSec": 300 }`
- `POST /v2/device/link/confirm`
  - request: `{ "linkCode": "string", "deviceId": "string", "sessionToken": "string" }`
  - response: `{ "ok": true, "linkedDeviceId": "string" }`

## Transport Config
- `GET /v2/config/ice`
  - response: `{ "ok": true, "iceServers": [{ "urls": ["stun:..."], "username": "", "credential": "" }], "ttlSec": 3600 }`

## Realtime
- `WS /v2/realtime`
  - event schema source of truth: `shared/v2/realtime-events.json`

## Compatibility Mapping
- Legacy web events (`file-start`, `file-chunk`, `file-end`, `file-ack`, `file-cancel`, `text-note`) map to V2 transfer envelope:
  - `file-start` -> `transfer.start`
  - `file-chunk` -> `transfer.chunk`
  - `file-end` -> `transfer.end`
  - `file-ack` -> `transfer.ack`
  - `file-cancel` -> `transfer.cancel`
  - `text-note` -> `transfer.note`
