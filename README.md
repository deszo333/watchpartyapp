# Watch Party — Android Client & Signaling Server

A native Android watch-party app designed for 2–4 close friends to watch movies together with synchronized playback and live WebRTC camera/audio tiles.

---

## Current State & Verification

### Build Status
- **Build verified**: Successfully compiles and builds APK via `./gradlew.bat assembleDebug`.
- **Gradle wrapper**: Generated with Gradle 8.9 (`gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`).
- **Kotlin / Compose**: Configured with Kotlin `2.0.20` and the official JetBrains Compose Compiler plugin (`org.jetbrains.kotlin.plugin.compose`).

### Implemented & Verified Features
1. **Late-Joining Sync Fix**: Late joiners receiving `JOIN_ACCEPTED` (or `ROOM_STATE`) with an active playback snapshot have their local `ExoPlayer` automatically loaded with the media item, seeked to the extrapolated live position, and transitioned to playback in exact sync with the host.
2. **Session Architecture**:
   - `PlaybackForegroundService` owns the lifecycle of `ExoPlayer`, `SignalingClient`, `SyncEngine`, `WebRtcMeshManager`, and shared `EglBase`.
   - `RoomViewModel` acts as a service-bound proxy, preserving playback across configuration changes and backgrounding.
   - Native Android audio focus configured (`C.USAGE_MEDIA`, `C.AUDIO_CONTENT_TYPE_MOVIE`, `handleAudioFocus = true`).
3. **Floating WebRTC Camera Tiles**:
   - Multi-party peer connection mesh via `WebRtcMeshManager` (Google WebRTC).
   - Rendered in `WatchRoomScreen` via `SurfaceViewRenderer` sharing `eglBase` context, anchored top-right and safe from subtitles.
4. **Lobby Device Check**:
   - Live camera preview and microphone status indicator in `LobbyScreen` before the party starts.
   - Graceful fallback when permissions are denied.
5. **Watch Room Controls**:
   - Custom progress bar / scrubber with interactive scrubbing for host and read-only progress display for guests.
   - Host controls: Play/Pause, Seek scrubber, "Source" direct MP4 URL loader dialog.
   - 3-second auto-hide controls timer that resets on interaction and pauses while scrubbing, typing in chat, or opening menus/dialogs.
   - Subtitle selector (CC toggle, embedded track dropdown, Cinema / High Contrast style presets).
   - Semi-transparent slide-out chat drawer.
   - Buffering protection: Who-is-buffering participant names displayed, with an instant "Resume" prompt for the host when buffer gates clear.
   - Role-aware host migration: Automatic promotion notice and switch into host ping broadcast mode when promoted.

---

## Fixed Contracts (Do Not Break)
1. **WebSocket Protocol (`server/server.js`)**:
   - Wire messages (`CREATE_ROOM`, `JOIN_ROOM`, `ROOM_STATE`, `PLAYBACK_PLAY`, `PLAYBACK_PAUSE`, `PLAYBACK_SEEK`, `PLAYBACK_PING`, `BUFFER_STATE`, `PLAYBACK_AUTO_PAUSE`, `BUFFER_GATE_CLEAR`, `RTC_OFFER`, etc.) and their mirror Kotlin DTOs in `SignalingClient.kt`.
2. **SyncEngine Drift Math**:
   - Two-tier drift correction:
     - Drift `< 300ms`: within tolerance, no-op.
     - `300ms <= Drift < 2500ms`: speed nudge (`1.05x` speed-up or `0.95x` slow-down).
     - Drift `>= 2500ms`: hard seek.
3. **Host Authority Model**:
   - Only the host issues authoritative play, pause, seek, and source change commands. Guests only react to broadcast events and report buffering states.

---

## Project Structure
```
WatchParty/
  PRODUCT_VISION.md                         # Full original vision & requirements
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradlew, gradlew.bat, gradle/wrapper/
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/com/watchparty/
        MainActivity.kt                     # Permissions, rationale, root UI container
        WatchPartyApp.kt                    # Application class & server endpoint config
        net/SignalingClient.kt              # OkHttp WebSocket client & wire protocol
        sync/SyncEngine.kt                  # ExoPlayer synchronization & drift correction
        rtc/WebRtcMeshManager.kt            # WebRTC full-mesh camera & audio manager
        service/PlaybackForegroundService.kt # Foreground service owning playback session
        viewmodel/RoomViewModel.kt          # UI facade bound to service
        ui/navigation/AppNav.kt             # Navigation host (Home -> Lobby -> WatchRoom)
        ui/home/HomeScreen.kt               # Room creation & joining (Zero-auth)
        ui/lobby/LobbyScreen.kt             # Pre-watch lobby, roster, device preview
        ui/watchroom/WatchRoomScreen.kt     # Fullscreen video, tiles, controls, chat
        ui/theme/Theme.kt, Type.kt          # Dark cinematic styling
  server/
    server.js, package.json                 # Ephemeral WebSocket signaling relay server
```

---

## How to Build & Run

### 1. Build Android App
```powershell
.\gradlew.bat assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 2. Start Signaling Server
```bash
cd server
npm install
node server.js
```
The server listens on port `8080` (or `process.env.PORT`).
Point `SIGNALING_SERVER_URL` in `WatchPartyApp.kt` to your server's WebSocket URL.
