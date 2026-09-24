# ScreenLink

A deployable WebRTC screen-sharing website.

## Local test

1. Install Node.js 18+.
2. Run:
   npm install
   npm start
3. Open http://localhost:3000

For another device on the same LAN, use your computer's local IP and port 3000. For public use, deploy the repository to a Node-compatible HTTPS host such as Render.

## Production

The app needs HTTPS for browser screen capture. Render provides HTTPS automatically.

For maximum WebRTC reliability across restrictive NAT/firewall networks, add a TURN service by placing TURN credentials in the client configuration in `public/script.js`.

## Features

- Public web UI
- Room codes
- Shareable room links
- Socket.IO signaling
- WebRTC media
- Screen sharing
- Optional system/tab audio where the browser supports it
- Fullscreen viewer
