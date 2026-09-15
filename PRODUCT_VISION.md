# ORIGINAL PRODUCT VISION

**Personal-use watch-party app, 2-4 friends, no logins / passwords / profiles.**

---

## Core User Experience & Flow

- **Home Screen**: Clean, dark, cinematic interface. "Create Room" or "Join Room" (4-character room code + display name). No authentication of any kind.
- **Lobby**: Waiting room showing connected friends, camera/mic testing, readiness status.
- **Watch Room**: Host starts the party; app transitions to full-screen, landscape-first, cinematic video viewing room.

---

## In-Room Controls & UI

- **Movie is the center of attention** (base z-index layer).
- **Compact, unobtrusive floating participant camera tiles** that do NOT obstruct subtitles or key action (anchored top-right).
- **Low-latency voice communication** active while watching with AEC/AGC acoustic echo cancellation and noise suppression.
- **Semi-transparent slide-out text chat drawer** for quiet comments.
- **Custom media controls** (play, pause, seek bar / scrubber, audio/subtitle selector, source URL input) that cleanly fade out after 3 seconds of inactivity, pausing fade during interactions and typing.

---

## Video & Playback Experience

- **Direct playback of an external HTTPS MP4 URL**.
- **Files ~3GB**, streamed via HTTP Range requests directly from cloud storage (R2/S3/CDN) to the Android client — server NEVER touches the video file.
- **Embedded or external WebVTT/SRT subtitles** with toggleable caption styles (Cinema drop-shadow, High Contrast outline).

---

## Synchronization Requirements

- **Seamless play/pause/seek sync** across all 2-4 viewers. Host-authoritative controls.
- **Late-joining sync**: Late joiner jumps to host's exact position automatically accounting for network latency and current host play state.
- **Drift correction**: Continuous, subtle timestamp alignment without audio stutter (two-tier drift correction: 300ms–2500ms speed-nudge at 1.05x/0.95x; >=2500ms hard seek).
- **Buffering protection**: A slow participant's buffering auto-pauses the party with who-is-buffering feedback and alerts the host to resume once the buffer gate clears.

---

## Constraints & Philosophy

- **Personal use only**, 2-4 friends.
- **Zero bloat**: NO auth, NO persistent DB, NO analytics, NO payments, NO enterprise infrastructure.
- **Native Android**, sideloaded APK, feels like a premium native app (native audio focus, hardware decoding, low latency WebRTC mesh) — NOT a wrapped web browser/PWA.
