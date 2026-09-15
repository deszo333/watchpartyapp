/**
 * Watch Party — Signaling & Sync Server
 *
 * Stateless, in-RAM, ephemeral room server for 2-4 person watch parties.
 * Responsibilities:
 *   - Room create/join/leave + automatic cleanup when empty
 *   - Host designation + automatic host migration on disconnect
 *   - WebRTC signaling relay (SDP offer/answer, ICE candidates) for P2P mesh
 *   - Playback action broadcast (play/pause/seek/ping) — host authoritative
 *   - Buffering READY-gate protocol (auto-pause group when anyone buffers)
 *   - Chat relay
 *   - Heartbeat (ws ping/pong) to reap dead connections
 *
 * No database. No auth. No persistence beyond process memory.
 */

'use strict';

const { WebSocketServer, WebSocket } = require('ws');
const crypto = require('crypto');
const http = require('http');

const PORT = process.env.PORT || 8080;
const ROOM_CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I ambiguity
const HEARTBEAT_INTERVAL_MS = 15_000;
const EMPTY_ROOM_GRACE_MS = 30_000; // room survives briefly after going empty (reconnect grace)
const MAX_ROOM_SIZE = 4;

// ---------------------------------------------------------------------------
// In-memory state
// ---------------------------------------------------------------------------

/**
 * rooms: Map<roomCode, Room>
 * Room = {
 *   code: string,
 *   hostId: string | null,
 *   clients: Map<clientId, ClientInfo>,
 *   createdAt: number,
 *   emptySince: number | null,
 *   playback: { state: 'playing'|'paused', positionMs: number, updatedAtMs: number, sourceUrl: string|null }
 * }
 *
 * ClientInfo = { id, ws, name, ready: boolean, buffering: boolean, isAlive: boolean, joinedAt: number }
 */
const rooms = new Map();

function genRoomCode() {
  let code;
  do {
    code = Array.from({ length: 4 }, () =>
      ROOM_CODE_CHARS[crypto.randomInt(ROOM_CODE_CHARS.length)]
    ).join('');
  } while (rooms.has(code));
  return code;
}

function genClientId() {
  return crypto.randomUUID();
}

function now() {
  return Date.now();
}

function send(ws, msg) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function broadcastToRoom(room, msg, excludeClientId = null) {
  for (const client of room.clients.values()) {
    if (client.id !== excludeClientId) send(client.ws, msg);
  }
}

function roomRosterPayload(room) {
  return {
    type: 'ROOM_STATE',
    roomCode: room.code,
    hostId: room.hostId,
    participants: Array.from(room.clients.values()).map((c) => ({
      id: c.id,
      name: c.name,
      ready: c.ready,
      buffering: c.buffering,
      isHost: c.id === room.hostId,
    })),
    playback: room.playback,
  };
}

function broadcastRoomState(room) {
  broadcastToRoom(room, roomRosterPayload(room));
}

/** Picks the longest-connected remaining client as the new host. */
function migrateHostIfNeeded(room) {
  if (room.clients.size === 0) return;
  if (room.hostId && room.clients.has(room.hostId)) return; // current host still present

  let oldest = null;
  for (const client of room.clients.values()) {
    if (!oldest || client.joinedAt < oldest.joinedAt) oldest = client;
  }
  room.hostId = oldest.id;

  broadcastToRoom(room, {
    type: 'HOST_CHANGED',
    hostId: room.hostId,
    reason: 'previous_host_disconnected',
  });
}

/** Re-evaluates the buffering READY-gate: pauses group if anyone is buffering. */
function evaluateBufferGate(room) {
  const anyoneBuffering = Array.from(room.clients.values()).some((c) => c.buffering);

  if (anyoneBuffering && room.playback.state === 'playing') {
    room.playback.state = 'paused';
    room.playback.updatedAtMs = now();
    broadcastToRoom(room, {
      type: 'PLAYBACK_AUTO_PAUSE',
      reason: 'buffering',
      positionMs: room.playback.positionMs,
      bufferingClientIds: Array.from(room.clients.values())
        .filter((c) => c.buffering)
        .map((c) => c.id),
    });
  }

  // Notify when the gate clears — host UI can choose to auto-resume or prompt.
  if (!anyoneBuffering) {
    broadcastToRoom(room, { type: 'BUFFER_GATE_CLEAR' });
  }
}

function removeClient(room, clientId) {
  room.clients.delete(clientId);

  if (room.clients.size === 0) {
    room.emptySince = now();
    return;
  }

  migrateHostIfNeeded(room);
  evaluateBufferGate(room);
  broadcastRoomState(room);
  broadcastToRoom(room, { type: 'PEER_LEFT', clientId });
}

// ---------------------------------------------------------------------------
// Room cleanup sweep — reaps rooms that have been empty past the grace period
// ---------------------------------------------------------------------------
setInterval(() => {
  const t = now();
  for (const [code, room] of rooms.entries()) {
    if (room.clients.size === 0 && room.emptySince && t - room.emptySince > EMPTY_ROOM_GRACE_MS) {
      rooms.delete(code);
    }
  }
}, 10_000);

// ---------------------------------------------------------------------------
// HTTP + WebSocket server
// ---------------------------------------------------------------------------

const httpServer = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', rooms: rooms.size }));
    return;
  }
  res.writeHead(404);
  res.end();
});

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  // Context attached once the client joins/creates a room
  let currentRoomCode = null;
  let clientId = null;

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      send(ws, { type: 'ERROR', message: 'Malformed JSON' });
      return;
    }

    switch (msg.type) {
      // ---------------------------------------------------------------
      case 'CREATE_ROOM': {
        const name = String(msg.name || 'Host').slice(0, 32);
        const code = genRoomCode();
        clientId = genClientId();
        currentRoomCode = code;

        const room = {
          code,
          hostId: clientId,
          clients: new Map(),
          createdAt: now(),
          emptySince: null,
          playback: { state: 'paused', positionMs: 0, updatedAtMs: now(), sourceUrl: null },
        };
        room.clients.set(clientId, {
          id: clientId,
          ws,
          name,
          ready: false,
          buffering: false,
          isAlive: true,
          joinedAt: now(),
        });
        rooms.set(code, room);

        send(ws, { type: 'ROOM_CREATED', roomCode: code, clientId, hostId: clientId });
        broadcastRoomState(room);
        break;
      }

      // ---------------------------------------------------------------
      case 'JOIN_ROOM': {
        const code = String(msg.roomCode || '').toUpperCase().slice(0, 4);
        const name = String(msg.name || 'Guest').slice(0, 32);
        const room = rooms.get(code);

        if (!room) {
          send(ws, { type: 'ERROR', code: 'ROOM_NOT_FOUND', message: `No room ${code}` });
          return;
        }
        if (room.clients.size >= MAX_ROOM_SIZE) {
          send(ws, { type: 'ERROR', code: 'ROOM_FULL', message: 'Room is full' });
          return;
        }

        clientId = genClientId();
        currentRoomCode = code;
        room.emptySince = null;

        room.clients.set(clientId, {
          id: clientId,
          ws,
          name,
          ready: false,
          buffering: false,
          isAlive: true,
          joinedAt: now(),
        });

        // Tell the new client everything it needs, including current playback
        // position extrapolated to "now" so a late joiner lands in sync immediately.
        const extrapolatedPositionMs =
          room.playback.state === 'playing'
            ? room.playback.positionMs + (now() - room.playback.updatedAtMs)
            : room.playback.positionMs;

        send(ws, {
          type: 'JOIN_ACCEPTED',
          roomCode: code,
          clientId,
          hostId: room.hostId,
          playback: { ...room.playback, positionMs: extrapolatedPositionMs },
          existingPeerIds: Array.from(room.clients.keys()).filter((id) => id !== clientId),
        });

        broadcastToRoom(room, { type: 'PEER_JOINED', clientId, name }, clientId);
        broadcastRoomState(room);
        break;
      }

      // ---------------------------------------------------------------
      case 'SET_READY': {
        const room = rooms.get(currentRoomCode);
        const client = room?.clients.get(clientId);
        if (!room || !client) return;
        client.ready = Boolean(msg.ready);
        broadcastRoomState(room);
        break;
      }

      // ---------------------------------------------------------------
      // Playback control — host authoritative. Server rejects control
      // messages from non-host clients (except buffering reports).
      // ---------------------------------------------------------------
      case 'PLAYBACK_SET_SOURCE': {
        const room = rooms.get(currentRoomCode);
        if (!room || clientId !== room.hostId) return;
        room.playback.sourceUrl = String(msg.sourceUrl || '');
        room.playback.positionMs = 0;
        room.playback.state = 'paused';
        room.playback.updatedAtMs = now();
        broadcastToRoom(room, {
          type: 'PLAYBACK_SOURCE_CHANGED',
          sourceUrl: room.playback.sourceUrl,
        });
        break;
      }

      case 'PLAYBACK_PLAY': {
        const room = rooms.get(currentRoomCode);
        if (!room || clientId !== room.hostId) return;
        room.playback.state = 'playing';
        room.playback.positionMs = Number(msg.positionMs ?? room.playback.positionMs);
        room.playback.updatedAtMs = now();
        broadcastToRoom(room, {
          type: 'PLAYBACK_PLAY',
          positionMs: room.playback.positionMs,
          serverTimeMs: room.playback.updatedAtMs,
        });
        break;
      }

      case 'PLAYBACK_PAUSE': {
        const room = rooms.get(currentRoomCode);
        if (!room || clientId !== room.hostId) return;
        room.playback.state = 'paused';
        room.playback.positionMs = Number(msg.positionMs ?? room.playback.positionMs);
        room.playback.updatedAtMs = now();
        broadcastToRoom(room, {
          type: 'PLAYBACK_PAUSE',
          positionMs: room.playback.positionMs,
          serverTimeMs: room.playback.updatedAtMs,
        });
        break;
      }

      case 'PLAYBACK_SEEK': {
        const room = rooms.get(currentRoomCode);
        if (!room || clientId !== room.hostId) return;
        room.playback.positionMs = Number(msg.positionMs ?? 0);
        room.playback.updatedAtMs = now();
        broadcastToRoom(room, {
          type: 'PLAYBACK_SEEK',
          positionMs: room.playback.positionMs,
          serverTimeMs: room.playback.updatedAtMs,
        });
        break;
      }

      // Periodic host heartbeat with authoritative position — drives client
      // drift correction. Sent every ~2s by the host while playing.
      case 'PLAYBACK_PING': {
        const room = rooms.get(currentRoomCode);
        if (!room || clientId !== room.hostId) return;
        room.playback.positionMs = Number(msg.positionMs ?? room.playback.positionMs);
        room.playback.updatedAtMs = now();
        broadcastToRoom(
          room,
          {
            type: 'PLAYBACK_PING',
            positionMs: room.playback.positionMs,
            serverTimeMs: room.playback.updatedAtMs,
            state: room.playback.state,
          },
          clientId
        );
        break;
      }

      // ---------------------------------------------------------------
      // Buffering READY-gate — any client (including host) may report.
      // ---------------------------------------------------------------
      case 'BUFFER_STATE': {
        const room = rooms.get(currentRoomCode);
        const client = room?.clients.get(clientId);
        if (!room || !client) return;
        client.buffering = Boolean(msg.buffering);
        evaluateBufferGate(room);
        broadcastRoomState(room);
        break;
      }

      // ---------------------------------------------------------------
      // WebRTC signaling relay — pure passthrough by target client id.
      // ---------------------------------------------------------------
      case 'RTC_OFFER':
      case 'RTC_ANSWER':
      case 'RTC_ICE_CANDIDATE': {
        const room = rooms.get(currentRoomCode);
        if (!room) return;
        const target = room.clients.get(msg.targetId);
        if (!target) return;
        send(target.ws, { ...msg, fromId: clientId });
        break;
      }

      // ---------------------------------------------------------------
      case 'CHAT_MESSAGE': {
        const room = rooms.get(currentRoomCode);
        const client = room?.clients.get(clientId);
        if (!room || !client) return;
        const text = String(msg.text || '').slice(0, 1000);
        if (!text.trim()) return;
        broadcastToRoom(room, {
          type: 'CHAT_MESSAGE',
          fromId: clientId,
          fromName: client.name,
          text,
          sentAtMs: now(),
        });
        break;
      }

      default:
        send(ws, { type: 'ERROR', message: `Unknown message type: ${msg.type}` });
    }
  });

  ws.on('close', () => {
    if (!currentRoomCode || !clientId) return;
    const room = rooms.get(currentRoomCode);
    if (room) removeClient(room, clientId);
  });

  ws.on('error', () => {
    // 'close' will still fire after 'error' in ws; nothing extra needed here.
  });
});

// Heartbeat sweep — terminate dead sockets, which triggers 'close' cleanup.
const heartbeatInterval = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_INTERVAL_MS);

wss.on('close', () => clearInterval(heartbeatInterval));

httpServer.listen(PORT, () => {
  console.log(`Watch Party signaling server listening on :${PORT}`);
});
