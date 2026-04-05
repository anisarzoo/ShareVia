const fs = require('fs');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const express = require('express');
const helmet = require('helmet');
const morgan = require('morgan');
const rateLimit = require('express-rate-limit');
const { ExpressPeerServer } = require('peer');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 9000);
const HOST = process.env.HOST || '0.0.0.0';
const RATE_LIMIT = Number(process.env.RATE_LIMIT || 240);
const RATE_LIMIT_WINDOW_MS = Number(process.env.RATE_LIMIT_WINDOW_MS || 60_000);
const TRUST_PROXY = process.env.TRUST_PROXY === 'true';
const PEER_PATH = normalizePath(process.env.PEER_PATH || '/peerjs');
const ALLOW_DISCOVERY = process.env.ALLOW_DISCOVERY === 'true';
const REALTIME_PATH = normalizePath(process.env.REALTIME_PATH || '/v2/realtime');
const REQUEST_BODY_LIMIT = process.env.REQUEST_BODY_LIMIT || '1mb';

const ENABLE_HTTPS = process.env.ENABLE_HTTPS === 'true';
const TLS_KEY_FILE = process.env.TLS_KEY_FILE || '';
const TLS_CERT_FILE = process.env.TLS_CERT_FILE || '';
const TLS_CA_FILE = process.env.TLS_CA_FILE || '';

const MAGIC_LINK_TTL_MS = Number(process.env.MAGIC_LINK_TTL_MS || 15 * 60_000);
const DEVICE_LINK_TTL_MS = Number(process.env.DEVICE_LINK_TTL_MS || 5 * 60_000);
const AUTH_DEV_ECHO_TOKEN = process.env.AUTH_DEV_ECHO_TOKEN === 'true';

const ICE_STUN_URLS = parseCsv(process.env.ICE_STUN_URLS || 'stun:stun.l.google.com:19302');
const ICE_TURN_URLS = parseCsv(process.env.ICE_TURN_URLS || '');
const ICE_TURN_USERNAME = process.env.ICE_TURN_USERNAME || '';
const ICE_TURN_CREDENTIAL = process.env.ICE_TURN_CREDENTIAL || '';
const ICE_TTL_SEC = Number(process.env.ICE_TTL_SEC || 3600);

const TELEMETRY_RETENTION = Number(process.env.TELEMETRY_RETENTION || 2000);

const corsPolicy = parseCorsPolicy(process.env.CORS_ORIGIN || '*');

const usersByEmail = new Map();
const sessions = new Map();
const magicLinkRequests = new Map();
const deviceLinkCodes = new Map();
const userDevices = new Map();

const realtimeClients = new Map();
const realtimeByDevice = new Map();
const realtimeRooms = new Map();
const telemetryEvents = [];

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', TRUST_PROXY);

app.use(
  helmet({
    crossOriginResourcePolicy: false,
  }),
);

app.use(morgan('combined'));
app.use(express.json({ limit: REQUEST_BODY_LIMIT }));

app.use(
  rateLimit({
    windowMs: RATE_LIMIT_WINDOW_MS,
    max: RATE_LIMIT,
    standardHeaders: true,
    legacyHeaders: false,
  }),
);

app.use((req, res, next) => {
  const requestOrigin = normalizeOrigin(req.headers.origin);
  const allowedOrigin = resolveAllowedOrigin(requestOrigin, corsPolicy);

  if (requestOrigin && !allowedOrigin) {
    res.status(403).json({ ok: false, error: 'origin_not_allowed' });
    return;
  }

  if (allowedOrigin) {
    res.setHeader('Access-Control-Allow-Origin', allowedOrigin);
    if (allowedOrigin !== '*') {
      appendVaryHeader(res, 'Origin');
    }
  }

  res.setHeader('Access-Control-Allow-Headers', 'Origin, Authorization, X-Requested-With, Content-Type, Accept');
  res.setHeader('Access-Control-Allow-Methods', 'GET,HEAD,POST,OPTIONS');

  if (req.method === 'OPTIONS') {
    res.sendStatus(204);
    return;
  }

  next();
});

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: 'sharevia-signal-server',
    peerPath: PEER_PATH,
    realtimePath: REALTIME_PATH,
    protocol: ENABLE_HTTPS ? 'https' : 'http',
    corsMode: corsPolicy.wildcard ? 'wildcard' : 'allowlist',
    realtimeConnections: realtimeClients.size,
    trackedRooms: realtimeRooms.size,
    activeSessions: sessions.size,
    telemetryCount: telemetryEvents.length,
    timestamp: nowIso(),
  });
});

app.get('/ready', (_req, res) => {
  res.json({ ok: true });
});

app.get('/v2/contracts', (_req, res) => {
  res.json({
    ok: true,
    transferProtocol: 'shared/v2/transfer-protocol.json',
    realtimeEvents: 'shared/v2/realtime-events.json',
    apiContract: 'shared/v2/api-contract.md',
  });
});

app.post('/v2/auth/magic-link/request', (req, res) => {
  const email = sanitizeEmail(req.body?.email);
  const deviceId = sanitizeDeviceId(req.body?.deviceId);
  const platform = sanitizeLabel(req.body?.platform, 20);

  if (!email || !deviceId) {
    res.status(400).json({ ok: false, error: 'email_and_device_required' });
    return;
  }

  const requestId = `mlr_${tokenHex(8)}`;
  const token = generateNumericCode(6);
  const expiresAt = Date.now() + MAGIC_LINK_TTL_MS;

  magicLinkRequests.set(requestId, {
    requestId,
    email,
    deviceId,
    platform: platform || 'unknown',
    token,
    expiresAt,
    createdAt: Date.now(),
  });

  trackMetric('auth_magic_link_requested', {
    emailDomain: email.split('@')[1] || '',
    platform: platform || 'unknown',
  });

  const response = {
    ok: true,
    requestId,
    expiresInSec: Math.round(MAGIC_LINK_TTL_MS / 1000),
    delivery: 'stubbed',
  };
  if (AUTH_DEV_ECHO_TOKEN) {
    response.debugToken = token;
  }

  res.json(response);
});

app.post('/v2/auth/magic-link/verify', (req, res) => {
  const requestId = sanitizeLabel(req.body?.requestId, 80);
  const token = sanitizeLabel(req.body?.token, 16);
  const deviceId = sanitizeDeviceId(req.body?.deviceId);

  const request = magicLinkRequests.get(requestId);
  if (!request || !token || !deviceId) {
    res.status(400).json({ ok: false, error: 'invalid_request' });
    return;
  }

  if (Date.now() > request.expiresAt) {
    magicLinkRequests.delete(requestId);
    res.status(410).json({ ok: false, error: 'magic_link_expired' });
    return;
  }

  if (request.token !== token) {
    res.status(401).json({ ok: false, error: 'invalid_token' });
    return;
  }

  const user = getOrCreateUserByEmail(request.email);
  const sessionToken = `svs_${tokenHex(24)}`;
  sessions.set(sessionToken, {
    sessionToken,
    userId: user.userId,
    email: user.email,
    createdAt: Date.now(),
    lastSeenAt: Date.now(),
  });

  upsertUserDevice(user.userId, deviceId, request.platform, 'Primary Device');
  magicLinkRequests.delete(requestId);

  trackMetric('auth_magic_link_verified', {
    userId: user.userId,
    platform: request.platform,
  });

  res.json({
    ok: true,
    sessionToken,
    profile: {
      userId: user.userId,
      email: user.email,
    },
  });
});

app.post('/v2/device/link/start', (req, res) => {
  const session = resolveSession(req);
  if (!session) {
    res.status(401).json({ ok: false, error: 'invalid_session' });
    return;
  }

  const deviceId = sanitizeDeviceId(req.body?.deviceId);
  const platform = sanitizeLabel(req.body?.platform, 20) || 'unknown';
  const label = sanitizeLabel(req.body?.label, 40) || `${platform} device`;

  if (!deviceId) {
    res.status(400).json({ ok: false, error: 'device_id_required' });
    return;
  }

  const linkCode = generateNumericCode(8);
  const expiresAt = Date.now() + DEVICE_LINK_TTL_MS;
  deviceLinkCodes.set(linkCode, {
    linkCode,
    ownerUserId: session.userId,
    ownerDeviceId: deviceId,
    ownerPlatform: platform,
    ownerLabel: label,
    expiresAt,
    createdAt: Date.now(),
  });

  upsertUserDevice(session.userId, deviceId, platform, label);
  trackMetric('device_link_started', { userId: session.userId, platform });

  res.json({
    ok: true,
    linkCode,
    expiresInSec: Math.round(DEVICE_LINK_TTL_MS / 1000),
  });
});

app.post('/v2/device/link/confirm', (req, res) => {
  const session = resolveSession(req);
  if (!session) {
    res.status(401).json({ ok: false, error: 'invalid_session' });
    return;
  }

  const linkCode = sanitizeLabel(req.body?.linkCode, 16);
  const deviceId = sanitizeDeviceId(req.body?.deviceId);
  const platform = sanitizeLabel(req.body?.platform, 20) || 'unknown';
  const label = sanitizeLabel(req.body?.label, 40) || `${platform} device`;

  const codeState = deviceLinkCodes.get(linkCode);
  if (!codeState || !deviceId) {
    res.status(400).json({ ok: false, error: 'invalid_link_code' });
    return;
  }
  if (Date.now() > codeState.expiresAt) {
    deviceLinkCodes.delete(linkCode);
    res.status(410).json({ ok: false, error: 'link_code_expired' });
    return;
  }
  if (codeState.ownerUserId !== session.userId) {
    res.status(403).json({ ok: false, error: 'link_not_authorized_for_user' });
    return;
  }

  upsertUserDevice(session.userId, deviceId, platform, label);
  deviceLinkCodes.delete(linkCode);

  trackMetric('device_link_confirmed', { userId: session.userId, platform });

  res.json({
    ok: true,
    linkedDeviceId: deviceId,
  });
});

app.get('/v2/config/ice', (_req, res) => {
  const iceServers = buildIceServers();
  res.json({
    ok: true,
    iceServers,
    ttlSec: ICE_TTL_SEC,
    generatedAt: nowIso(),
  });
});

app.post('/v2/telemetry/ingest', (req, res) => {
  const metrics = Array.isArray(req.body?.metrics) ? req.body.metrics : [];
  let accepted = 0;
  for (const metric of metrics) {
    if (!metric || typeof metric !== 'object') {
      continue;
    }
    const name = sanitizeLabel(metric.name, 80);
    if (!name) {
      continue;
    }
    const data = scrubObject(metric.data);
    trackMetric(name, data);
    accepted += 1;
  }
  res.json({ ok: true, accepted });
});

app.get('/v2/telemetry/recent', (req, res) => {
  const requested = Number(req.query?.limit || 100);
  const limit = Number.isFinite(requested) ? Math.max(1, Math.min(requested, 500)) : 100;
  const items = telemetryEvents.slice(-limit);
  res.json({ ok: true, count: items.length, items });
});

const { server, protocol } = createServer(app);

const peerServer = ExpressPeerServer(server, {
  path: '/',
  proxied: TRUST_PROXY,
  allow_discovery: ALLOW_DISCOVERY,
});

peerServer.on('connection', (client) => {
  console.log(`[peer] connected ${client.getId()}`);
});

peerServer.on('disconnect', (client) => {
  console.log(`[peer] disconnected ${client.getId()}`);
});

app.use(PEER_PATH, peerServer);

const wsServer = new WebSocketServer({ noServer: true });
const legacyUpgradeListeners = server.listeners('upgrade').slice();
server.removeAllListeners('upgrade');
server.on('upgrade', (req, socket, head) => {
  const requestHost = req.headers.host || `${HOST}:${PORT}`;
  const parsed = new URL(req.url || '/', `http://${requestHost}`);

  if (parsed.pathname === REALTIME_PATH) {
    wsServer.handleUpgrade(req, socket, head, (ws) => {
      wsServer.emit('connection', ws, req);
    });
    return;
  }

  if (parsed.pathname.startsWith(PEER_PATH)) {
    for (const listener of legacyUpgradeListeners) {
      listener.call(server, req, socket, head);
    }
    return;
  }

  socket.destroy();
});

wsServer.on('connection', (socket) => {
  const clientId = `ws_${tokenHex(8)}`;
  const client = {
    clientId,
    socket,
    connectedAt: Date.now(),
    deviceId: '',
    platform: 'unknown',
    status: 'online',
    sessionToken: '',
    userId: '',
    rooms: new Set(),
  };
  realtimeClients.set(clientId, client);
  trackMetric('ws_connected', { clientId });

  sendRealtime(client, 'server.welcome', {
    clientId,
    protocolVersion: 'sv2',
    serverTime: nowIso(),
  });

  socket.on('message', (chunk) => {
    const text = decodeSocketMessage(chunk);
    if (!text) {
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch (_error) {
      sendRealtime(client, 'server.error', {
        code: 'invalid_json',
        message: 'Message payload must be valid JSON.',
      });
      return;
    }
    handleRealtimeEvent(client, parsed);
  });

  socket.on('close', () => {
    unregisterRealtimeClient(clientId);
  });

  socket.on('error', (error) => {
    trackMetric('ws_error', {
      clientId,
      message: error?.message || 'unknown',
    });
  });
});

server.listen(PORT, HOST, () => {
  console.log(`ShareVia signaling server listening on ${protocol}://${HOST}:${PORT}${PEER_PATH}`);
  console.log(`ShareVia realtime hub listening on ${protocol}://${HOST}:${PORT}${REALTIME_PATH}`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    console.log(`[server] ${signal} received, closing listener...`);
    wsServer.close();
    cleanupTimer && clearInterval(cleanupTimer);
    server.close(() => process.exit(0));
  });
}

const cleanupTimer = setInterval(() => {
  const now = Date.now();
  for (const [requestId, request] of magicLinkRequests.entries()) {
    if (now > request.expiresAt) {
      magicLinkRequests.delete(requestId);
    }
  }
  for (const [linkCode, state] of deviceLinkCodes.entries()) {
    if (now > state.expiresAt) {
      deviceLinkCodes.delete(linkCode);
    }
  }
}, 60_000);
if (typeof cleanupTimer.unref === 'function') {
  cleanupTimer.unref();
}

function handleRealtimeEvent(client, message) {
  const event = sanitizeLabel(message?.event || message?.type, 80);
  const payload = scrubObject(message?.payload || message?.data || {});
  const requestId = sanitizeLabel(message?.requestId, 80);

  if (!event) {
    sendRealtime(client, 'server.error', {
      code: 'missing_event',
      message: 'event is required',
    });
    return;
  }

  switch (event) {
    case 'auth.hello':
      handleAuthHello(client, payload, requestId);
      return;
    case 'presence.update':
      handlePresenceUpdate(client, payload);
      return;
    case 'room.host':
      handleRoomHost(client, payload);
      return;
    case 'room.join':
      handleRoomJoin(client, payload);
      return;
    case 'room.leave':
      handleRoomLeave(client, payload);
      return;
    case 'signal.offer':
    case 'signal.answer':
    case 'signal.candidate':
    case 'transfer.start':
    case 'transfer.chunk':
    case 'transfer.ack':
    case 'transfer.cancel':
    case 'transfer.resume':
      routeRealtimeEvent(client, event, payload);
      return;
    default:
      sendRealtime(client, 'server.error', {
        code: 'unsupported_event',
        message: `Unsupported event: ${event}`,
      });
  }
}

function handleAuthHello(client, payload, requestId) {
  const oldDeviceId = client.deviceId;
  const deviceId = sanitizeDeviceId(payload.deviceId);
  if (!deviceId) {
    sendRealtime(client, 'server.error', {
      code: 'device_id_required',
      message: 'auth.hello requires payload.deviceId',
    });
    return;
  }

  client.platform = sanitizeLabel(payload.platform, 20) || 'unknown';
  client.sessionToken = sanitizeLabel(payload.sessionToken, 140);
  client.status = sanitizeLabel(payload.status, 24) || 'online';
  client.deviceId = deviceId;

  const session = sessions.get(client.sessionToken);
  client.userId = session?.userId || '';
  if (session) {
    session.lastSeenAt = Date.now();
  }

  if (oldDeviceId && oldDeviceId !== deviceId) {
    removeRealtimeDeviceIndex(oldDeviceId, client.clientId);
  }
  addRealtimeDeviceIndex(deviceId, client.clientId);

  sendRealtime(client, 'auth.hello.ack', {
    accepted: true,
    clientId: client.clientId,
    deviceId: client.deviceId,
    userId: client.userId || null,
  }, requestId);

  broadcastRealtime('presence.update', {
    deviceId: client.deviceId,
    status: client.status,
    platform: client.platform,
    userId: client.userId || null,
    lastSeenAt: nowIso(),
  }, client.clientId);

  trackMetric('auth_hello', {
    deviceId: client.deviceId,
    platform: client.platform,
    hasSession: Boolean(client.sessionToken),
  });
}

function handlePresenceUpdate(client, payload) {
  client.status = sanitizeLabel(payload.status, 24) || 'online';
  if (!client.deviceId) {
    return;
  }
  broadcastRealtime('presence.update', {
    deviceId: client.deviceId,
    status: client.status,
    platform: client.platform,
    userId: client.userId || null,
    lastSeenAt: nowIso(),
  }, client.clientId);
}

function handleRoomHost(client, payload) {
  const roomId = sanitizeRoomId(payload.roomId);
  if (!roomId) {
    sendRealtime(client, 'server.error', {
      code: 'invalid_room_id',
      message: 'room.host requires payload.roomId',
    });
    return;
  }

  joinRealtimeRoom(client, roomId);
  sendRealtime(client, 'room.hosted', {
    roomId,
    mode: sanitizeLabel(payload.mode, 20) || 'online',
    transportHints: Array.isArray(payload.transportHints) ? payload.transportHints.slice(0, 6) : [],
  });

  broadcastToRoom(roomId, 'room.member', {
    roomId,
    action: 'host-online',
    deviceId: client.deviceId || null,
    clientId: client.clientId,
    platform: client.platform,
  }, client.clientId);

  trackMetric('room_hosted', { roomId, deviceId: client.deviceId || 'unknown' });
}

function handleRoomJoin(client, payload) {
  const roomId = sanitizeRoomId(payload.roomId);
  if (!roomId) {
    sendRealtime(client, 'server.error', {
      code: 'invalid_room_id',
      message: 'room.join requires payload.roomId',
    });
    return;
  }

  joinRealtimeRoom(client, roomId);
  sendRealtime(client, 'room.joined', {
    roomId,
    clientId: client.clientId,
  });

  broadcastToRoom(roomId, 'room.member', {
    roomId,
    action: 'joined',
    deviceId: client.deviceId || null,
    clientId: client.clientId,
    platform: client.platform,
  }, client.clientId);

  trackMetric('room_joined', { roomId, deviceId: client.deviceId || 'unknown' });
}

function handleRoomLeave(client, payload) {
  const roomId = sanitizeRoomId(payload.roomId);
  if (!roomId) {
    return;
  }
  leaveRealtimeRoom(client, roomId);
  broadcastToRoom(roomId, 'room.member', {
    roomId,
    action: 'left',
    deviceId: client.deviceId || null,
    clientId: client.clientId,
  }, client.clientId);
}

function routeRealtimeEvent(client, event, payload) {
  const roomId = sanitizeRoomId(payload.roomId);
  const targetDeviceId = sanitizeDeviceId(payload.targetDeviceId);
  const targetClientId = sanitizeLabel(payload.targetClientId, 40);
  const relayEnvelope = {
    ...payload,
    roomId: roomId || payload.roomId || null,
    fromDeviceId: client.deviceId || null,
    fromClientId: client.clientId,
    fromPlatform: client.platform || null,
  };

  let delivered = 0;
  if (targetClientId) {
    delivered = sendToClientId(targetClientId, event, relayEnvelope, client.clientId) ? 1 : 0;
  } else if (targetDeviceId) {
    delivered = sendToDevice(targetDeviceId, event, relayEnvelope, client.clientId);
  } else if (roomId) {
    delivered = broadcastToRoom(roomId, event, relayEnvelope, client.clientId);
  } else {
    sendRealtime(client, 'server.error', {
      code: 'route_target_required',
      message: `${event} requires targetDeviceId, targetClientId, or roomId`,
    });
    return;
  }

  if (delivered === 0) {
    sendRealtime(client, 'route.missed', {
      event,
      roomId: roomId || null,
      targetDeviceId: targetDeviceId || null,
      targetClientId: targetClientId || null,
    });
  }

  trackMetric('realtime_route', {
    event,
    delivered,
    roomId: roomId || '',
    targetDeviceId: targetDeviceId || '',
    fallbackUsed: payload.fallbackUsed === true,
  });
}

function joinRealtimeRoom(client, roomId) {
  let members = realtimeRooms.get(roomId);
  if (!members) {
    members = new Set();
    realtimeRooms.set(roomId, members);
  }
  members.add(client.clientId);
  client.rooms.add(roomId);
}

function leaveRealtimeRoom(client, roomId) {
  client.rooms.delete(roomId);
  const members = realtimeRooms.get(roomId);
  if (!members) {
    return;
  }
  members.delete(client.clientId);
  if (members.size === 0) {
    realtimeRooms.delete(roomId);
  }
}

function unregisterRealtimeClient(clientId) {
  const client = realtimeClients.get(clientId);
  if (!client) {
    return;
  }

  for (const roomId of client.rooms) {
    const members = realtimeRooms.get(roomId);
    if (members) {
      members.delete(clientId);
      if (members.size === 0) {
        realtimeRooms.delete(roomId);
      } else {
        broadcastToRoom(roomId, 'room.member', {
          roomId,
          action: 'left',
          deviceId: client.deviceId || null,
          clientId,
        }, clientId);
      }
    }
  }

  if (client.deviceId) {
    removeRealtimeDeviceIndex(client.deviceId, clientId);
    if (!realtimeByDevice.get(client.deviceId)?.size) {
      broadcastRealtime('presence.update', {
        deviceId: client.deviceId,
        status: 'offline',
        platform: client.platform,
        userId: client.userId || null,
        lastSeenAt: nowIso(),
      }, clientId);
    }
  }

  realtimeClients.delete(clientId);
  trackMetric('ws_disconnected', {
    clientId,
    deviceId: client.deviceId || '',
  });
}

function addRealtimeDeviceIndex(deviceId, clientId) {
  let bucket = realtimeByDevice.get(deviceId);
  if (!bucket) {
    bucket = new Set();
    realtimeByDevice.set(deviceId, bucket);
  }
  bucket.add(clientId);
}

function removeRealtimeDeviceIndex(deviceId, clientId) {
  const bucket = realtimeByDevice.get(deviceId);
  if (!bucket) {
    return;
  }
  bucket.delete(clientId);
  if (bucket.size === 0) {
    realtimeByDevice.delete(deviceId);
  }
}

function sendToDevice(deviceId, event, payload, exceptClientId = '') {
  const bucket = realtimeByDevice.get(deviceId);
  if (!bucket || bucket.size === 0) {
    return 0;
  }
  let delivered = 0;
  for (const clientId of bucket) {
    if (clientId === exceptClientId) {
      continue;
    }
    if (sendToClientId(clientId, event, payload, exceptClientId)) {
      delivered += 1;
    }
  }
  return delivered;
}

function sendToClientId(clientId, event, payload, exceptClientId = '') {
  if (clientId === exceptClientId) {
    return false;
  }
  const client = realtimeClients.get(clientId);
  if (!client) {
    return false;
  }
  return sendRealtime(client, event, payload);
}

function broadcastToRoom(roomId, event, payload, exceptClientId = '') {
  const members = realtimeRooms.get(roomId);
  if (!members || members.size === 0) {
    return 0;
  }
  let delivered = 0;
  for (const clientId of members) {
    if (clientId === exceptClientId) {
      continue;
    }
    if (sendToClientId(clientId, event, payload, exceptClientId)) {
      delivered += 1;
    }
  }
  return delivered;
}

function broadcastRealtime(event, payload, exceptClientId = '') {
  for (const [clientId, client] of realtimeClients.entries()) {
    if (clientId === exceptClientId) {
      continue;
    }
    sendRealtime(client, event, payload);
  }
}

function sendRealtime(client, event, payload, requestId = '') {
  if (!client?.socket || client.socket.readyState !== 1) {
    return false;
  }
  const envelope = JSON.stringify({
    event,
    payload,
    timestamp: nowIso(),
    requestId: requestId || undefined,
  });
  client.socket.send(envelope);
  return true;
}

function resolveSession(req) {
  const token =
    sanitizeLabel(
      extractBearerToken(req.headers?.authorization) ||
        req.body?.sessionToken,
      140,
    );
  if (!token) {
    return null;
  }
  const session = sessions.get(token);
  if (!session) {
    return null;
  }
  session.lastSeenAt = Date.now();
  return session;
}

function upsertUserDevice(userId, deviceId, platform, label) {
  let devices = userDevices.get(userId);
  if (!devices) {
    devices = new Map();
    userDevices.set(userId, devices);
  }
  devices.set(deviceId, {
    deviceId,
    platform,
    label,
    linkedAt: nowIso(),
  });
}

function getOrCreateUserByEmail(email) {
  let user = usersByEmail.get(email);
  if (!user) {
    user = {
      userId: `usr_${tokenHex(8)}`,
      email,
      createdAt: Date.now(),
    };
    usersByEmail.set(email, user);
  }
  return user;
}

function buildIceServers() {
  const servers = [];
  if (ICE_STUN_URLS.length > 0) {
    servers.push({ urls: ICE_STUN_URLS });
  }
  if (ICE_TURN_URLS.length > 0) {
    servers.push({
      urls: ICE_TURN_URLS,
      username: ICE_TURN_USERNAME,
      credential: ICE_TURN_CREDENTIAL,
    });
  }
  return servers;
}

function trackMetric(name, data = {}) {
  telemetryEvents.push({
    name: sanitizeLabel(name, 120) || 'metric',
    ts: nowIso(),
    data: scrubObject(data),
  });
  if (telemetryEvents.length > TELEMETRY_RETENTION) {
    telemetryEvents.splice(0, telemetryEvents.length - TELEMETRY_RETENTION);
  }
}

function decodeSocketMessage(chunk) {
  if (chunk == null) {
    return '';
  }
  if (typeof chunk === 'string') {
    return chunk;
  }
  if (Buffer.isBuffer(chunk)) {
    return chunk.toString('utf8');
  }
  return String(chunk);
}

function createServer(expressApp) {
  if (!ENABLE_HTTPS) {
    return {
      server: http.createServer(expressApp),
      protocol: 'http',
    };
  }

  if (!TLS_KEY_FILE || !TLS_CERT_FILE) {
    throw new Error('ENABLE_HTTPS=true requires TLS_KEY_FILE and TLS_CERT_FILE');
  }

  const tlsOptions = {
    key: fs.readFileSync(TLS_KEY_FILE),
    cert: fs.readFileSync(TLS_CERT_FILE),
  };

  if (TLS_CA_FILE) {
    tlsOptions.ca = fs.readFileSync(TLS_CA_FILE);
  }

  return {
    server: https.createServer(tlsOptions, expressApp),
    protocol: 'https',
  };
}

function normalizePath(value) {
  if (!value) {
    return '/';
  }
  return value.startsWith('/') ? value : `/${value}`;
}

function parseCorsPolicy(rawValue) {
  const raw = String(rawValue || '*').trim();
  if (!raw || raw === '*') {
    return { wildcard: true, origins: new Set() };
  }

  const origins =
    raw
      .split(',')
      .map((entry) => normalizeOrigin(entry))
      .filter(Boolean);

  return { wildcard: false, origins: new Set(origins) };
}

function resolveAllowedOrigin(requestOrigin, policy) {
  if (policy.wildcard) {
    return '*';
  }
  if (!requestOrigin) {
    return null;
  }
  return policy.origins.has(requestOrigin) ? requestOrigin : null;
}

function normalizeOrigin(origin) {
  if (!origin) {
    return '';
  }
  return String(origin).trim().replace(/\/+$/, '');
}

function appendVaryHeader(res, value) {
  const current = String(res.getHeader('Vary') || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (!current.includes(value)) {
    current.push(value);
  }
  if (current.length > 0) {
    res.setHeader('Vary', current.join(', '));
  }
}

function extractBearerToken(headerValue) {
  const raw = String(headerValue || '');
  if (!raw.toLowerCase().startsWith('bearer ')) {
    return '';
  }
  return raw.slice(7).trim();
}

function parseCsv(rawValue) {
  const raw = String(rawValue || '').trim();
  if (!raw) {
    return [];
  }
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function sanitizeEmail(value) {
  const email = String(value || '').trim().toLowerCase();
  if (!email || !email.includes('@') || email.length > 160) {
    return '';
  }
  return email;
}

function sanitizeDeviceId(value) {
  const text =
    String(value || '')
      .trim()
      .replace(/[^a-zA-Z0-9._:-]/g, '');
  if (!text) {
    return '';
  }
  return text.slice(0, 96);
}

function sanitizeRoomId(value) {
  const text =
    String(value || '')
      .trim()
      .replace(/[^a-zA-Z0-9_-]/g, '');
  if (!text) {
    return '';
  }
  return text.slice(0, 48);
}

function sanitizeLabel(value, maxLen = 80) {
  const text =
    String(value || '')
      .trim()
      .replace(/[\u0000-\u001f]/g, '');
  if (!text) {
    return '';
  }
  return text.slice(0, maxLen);
}

function scrubObject(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return {};
  }
  const output = {};
  for (const [key, raw] of Object.entries(value)) {
    const safeKey = sanitizeLabel(key, 64);
    if (!safeKey) {
      continue;
    }
    if (raw == null) {
      output[safeKey] = null;
      continue;
    }
    if (typeof raw === 'number' || typeof raw === 'boolean') {
      output[safeKey] = raw;
      continue;
    }
    if (typeof raw === 'string') {
      output[safeKey] = raw.slice(0, 500);
      continue;
    }
    if (Array.isArray(raw)) {
      output[safeKey] = raw.slice(0, 20).map((item) => String(item).slice(0, 120));
      continue;
    }
    output[safeKey] = '[object]';
  }
  return output;
}

function tokenHex(bytes) {
  return crypto.randomBytes(bytes).toString('hex');
}

function generateNumericCode(length) {
  const chars = [];
  for (let i = 0; i < length; i += 1) {
    chars.push(String(Math.floor(Math.random() * 10)));
  }
  return chars.join('');
}

function nowIso() {
  return new Date().toISOString();
}
