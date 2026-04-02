Place the web client bundle in this directory for native offline startup:

Required files:
- index.html
- style.css
- app.js
- peerjs.min.js
- qrcode.min.js
- html5-qrcode.min.js
- manifest.webmanifest
- icon128.png

For production CI/CD, add a build step that copies from /web into this folder.
