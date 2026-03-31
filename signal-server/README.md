# Local Signaling Server

Use this service for **offline Wi-Fi sharing** when devices are on the same LAN and internet is unavailable.

## Start
1. Install dependencies:
   - `cd signal-server`
   - `npm install`
2. Run server:
   - `npm run start`

## Configure web app
Open advanced settings in web client and set:
- Host: LAN IP of machine running this server (example `192.168.1.20`)
- Port: `9000`
- Path: `/peerjs`
- Secure: off for plain LAN, on behind HTTPS reverse proxy

## Health check
- `http://<host>:9000/health`

## Production deployment
- Put behind Nginx/Caddy with TLS.
- Lock CORS to known origins.
- Run process with PM2/systemd.
- Enable log shipping and alerting.
