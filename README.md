# MoQ Publisher

MoQ Publisher is an Android camera broadcaster built with Jetpack Compose, CameraX and the `dev.moq` client. A GPU surface pipeline feeds Android MediaCodec without CPU YUV pixel copies, publishes compressed H.264/H.265 access units to a MoQ relay, and can publish microphone audio as Opus. An application-owned session and camera/microphone foreground service keep broadcasts independent of the Activity.

## Features

- CameraX capture with front/back camera switching, tap-to-focus, pinch/button/slider zoom, torch and saved exposure compensation within each camera's supported range.
- Advanced camera settings select advertised logical and physical lenses, stabilization, focus and exposure lock, fixed/portrait/landscape output, and front-camera output mirroring.
- Collapsible camera details, with camera actions on the left and zoom/broadcast controls or statistics on the right in landscape.
- H.264 and H.265 hardware video encoding. Settings show the Android-reported encoder names and capability ranges. The app keeps the selected codec and FPS, lets CameraX resolve the capture size, and checks whether MediaCodec can start with that configuration.
- Device-supported resolution selection and 15/24/30/60 FPS targets.
- Optional 48 kHz mono/stereo Opus microphone audio, with four quality/bitrate presets, 0-300% PCM gain and automatic mono fallback.
- Relay URL, broadcast path, JWT and TLS certificate verification settings.
- Security settings provide explicit clearing actions for the encrypted moq.pro JWT and Cloudflare Publish/Subscribe tokens.
- Switchable moq.pro and Cloudflare MoQ Draft 18 profiles, with encrypted connection tokens,
  separate Subscribe-only viewing/OBS links, connection phases and an opt-in remote decoder check.
- Cloudflare viewing tools include a Subscribe-token QR code, Browser Source instructions and native MoQ source instructions. The app keeps Publish and Subscribe credentials separate.
- The simple dashboard shows live duration, published FPS, uplink throughput, network and audio level. The professional dashboard adds the active encoder and dimensions, RTT, dropped frames, queue depth and age, lost packets, reconnects, device health and remote decoding status.
- Thermal or memory pressure also offers an explicit lower-bitrate action for the current stream. The app waits for the user to apply it and preserves the configured quality when no action is chosen.
- On-device diagnostic ZIP export with sharing and saving to the phone, without USB or adb.
- Configurable video bitrate in Kbps (default 8000 Kbps), independent minimum and maximum ABR bounds, optional congestion-aware bitrate adaptation and sensor-timestamp frame pacing. Minimum, target and maximum bitrate can be adjusted while broadcasting.
- Built-in Data Saver, Mobile Streaming, High Quality and High FPS presets, plus named custom presets for reusable video and audio settings. You can duplicate a built-in or custom preset, then edit and save the copy.
- Settings and the last selected camera/video options are persisted locally.
- Traditional Chinese and dark mode by default, with instant English/system-language and dark/light theme selection.
- The screen stays awake during connection and streaming.
- Background broadcasts continue when switching apps or turning off the screen, with a broadcast notification, Stop action and a bounded, renewed CPU wake lock.
- Saved landscape lock supports both landscape directions; interface rotation does not restart the live camera session.
- Universal APKs for ARM64, ARMv7 and x86_64. The MoQ core is downloaded from official Maven releases on first launch, cached across APK updates and checked for compatible updates at startup.

## Usage

1. Install the signed release APK (recommended), offline APK, compact APK or debug APK on a supported Android device. The release, compact and debug APKs download the compatible MoQ core on first launch. The offline APK includes the official core for ARM64, ARMv7 and x86_64 and does not need that first download.
2. On first launch, connect to the internet and allow the official MoQ core package to download (about 33 MB). Only the running process ABI is extracted and saved. Grant camera permission; microphone permission is requested when an audio broadcast starts.
3. Open **Settings -> Relay Provider**. For moq.pro, enter the relay URL, broadcast path and optional publish JWT. For a private relay, enter a separate viewing JWT under **moq.pro viewing** to copy the Subscribe endpoint or verify remote decoding.
   For Cloudflare, enter a **Publish + Subscribe connection token** and a broadcast name
   (default `twcam.hang`). You can clear the broadcast name to publish an unnamed broadcast, matching the official `moq-pub` example. Use a `.hang` name for moq.watch links and QR codes. Native subscriber settings and the in-app decoder check accept an empty name with a separate Subscribe-only token. This profile uses `https://draft-18.cloudflare.mediaoverquic.com/<connection-token>`.
   do not use a Management API token, Project ID, Relay ID or `?jwt=`.
4. Select a stream quality preset or configure the codec, resolution, frame rate, target video bitrate in Kbps and TLS behavior. Save a named custom preset when you want to reuse the video and audio choices. **Adaptive bitrate** enables network adaptation; disable it to retain the configured encoder target.
5. Press **Start broadcast** while the app is visible. The app checks permissions, Relay settings, MoQ core, and the requested quality. A missing Android encoder capability entry is a warning. The selected H.264 or H.265 encoder attempts to open with the requested frame rate and the capture size. Camera broadcasts require camera permission. Audio capture requires Android's recording permission. Android 13+ also offers notification permission. Stop from the app or broadcast notification.

### Screen broadcasting

Under **Settings → Video source**, choose **Screen** before starting and accept the Android sharing prompt. The selected video resolution caps the long edge of the shared image. The stream follows the aspect ratio of the shared screen or window. For audio on Android 10+, choose **Microphone** or **Device audio**. Device audio includes media or games whose apps allow Android playback capture. Use **Share screen** during a camera broadcast to switch sources for that session. When Android ends sharing, a camera broadcast returns to the camera and a screen-started broadcast ends. Enable **Background broadcast** to share other apps.

### Local recording

Under **Settings → Local recording**, select **Stream and record** to save MP4 while sending MoQ, or **Record only** to record without connecting to a Relay. Both save H.264 or H.265 with AAC when audio is available. Recording starts while the Relay connects and continues during network handovers. If the Relay connection fails, the phone keeps recording and shows the Relay error. Start a new broadcast to reconnect after a terminal failure.

Choose a segment duration of 5, 15, 30 or 60 minutes. A video format or channel change can start a new segment early. Select a completed MP4 in **Local recording** and choose **Export MP4** to save it to a folder of your choice.

### Remote access

While a session is active, open **Settings → Remote access** on the phone. Choose **View only** for status, FPS, bitrate, RTT, battery, thermal status, and alerts. Choose **Allow control** to also enable mute, bitrate, subscriber verification, and stop. Use **Copy pairing code** and transfer the code to your computer on the same Wi-Fi or Ethernet network. Android can ask for local network permission.

Run the desktop client from the repository with Python 3.10 or later.

```powershell
python tools/moq-remote-control.py status --watch
python tools/moq-remote-control.py mute
python tools/moq-remote-control.py bitrate 2500
python tools/moq-remote-control.py verify
python tools/moq-remote-control.py stop
```

Enter the pairing code at the hidden prompt. The client pins the phone's TLS certificate before sending the pairing credential. Bitrate changes apply to the current session within the configured range. Verification uses the app's separate subscriber connection. **Revoke access** invalidates the pairing code immediately. Ending the session also revokes access.

### Video overlay

In **Settings → Video overlay**, enable **Show overlay**. Open **Edit overlay** to import a PNG, JPEG or WebP logo, enter text or a watermark, and add a clock or session timer. Select the corner, scale and opacity, then apply. The preview fits the encoded frame while the overlay is visible during capture. The same overlay appears in the live video and local MP4.
6. The core must be ready before streaming. Compatible core updates apply on the next cold launch. During a broadcast, select a resolution and FPS together and tap **Apply resolution and frame rate**. The app saves the new mode when the rebuilt video track writes its first keyframe. If configuration fails or times out, it restores the previous settings. Stop before changing the codec. To stop a live broadcast, confirm in the app or use **Stop immediately** in the notification. You can turn off the app confirmation in Broadcast behavior.

In landscape, the narrow left rail contains camera switching, center focus, torch, Settings and the details toggle. The narrow right rail contains a vertical zoom slider and the broadcast button. Drag upward to zoom in or downward to zoom out; tap the displayed zoom ratio to reset to 1.0x. Statistics and diagnostic export open in a separate panel beside the right rail. The actions and details can scroll on short screens; the zoom rail accepts vertical drags directly. Portrait keeps one set of focus/torch controls and a separate zoom row.

Settings are grouped as Broadcast behavior (background broadcast and landscape lock), Connection (relay URL and broadcast path), Video (codec, resolution, FPS and bitrate), Audio (microphone, channels, quality and gain), Security & Authentication (JWT and TLS), and Appearance & Language (theme and language). The exposure icon beside the zoom controls opens the EV slider and reset action. Unsupported cameras show a localized explanation.

The JWT is added as a `jwt` query parameter at connection time and is masked in the settings preview. The relay URL must use `https` or `wss`; it must include a host and a valid non-empty broadcast path.

The **Bitrate** statistic measures the change in bytes actually sent over the elapsed sample time, displayed in decimal Mbps, including transport overhead and retransmissions. It does not display the congestion controller's estimate of available bandwidth. The first sample, a reconnect, or a transport that provides no byte counter shows `--` until a valid measurement is available.

The default video target is 8000 Kbps. Existing Mbps preferences migrate once (8 Mbps becomes 8000 Kbps). Enter decimal Kbps with minimum ≤ target ≤ maximum and a floor of 100 Kbps. With ABR enabled, the encoder starts at the target, drops quickly when capacity falls or queued video ages, and recovers gradually up to the configured maximum. With ABR disabled, the encoder uses the target. You can apply all four values together while the encoder is running. If the codec rejects a change, the app keeps the last accepted rate and suspends automatic changes until the encoder restarts or accepts a manual change. Actual encoder output varies with scene complexity and hardware rate control.

CameraX provides one camera surface to an EGL renderer. The renderer feeds the MediaCodec input surface at the selected frame rate and draws a separate preview at up to 30 FPS; hiding or destroying the display surface does not remove the encoder output. Camera frames above the selected streaming FPS are dropped using sensor timestamps. The pacer re-anchors if switching cameras changes the sensor clock origin. CameraX queries FPS support for the actual capture session and configures a session-wide range, preferring a fixed range at the target when supported. Sensor resolutions stay within the requested dimensions, independent of display rotation; if no mode fits, the smallest available mode is used.

The main screen shows measured output FPS, the current encoder target bitrate, capture resolution and requested camera FPS range. It displays a notice when the selected camera range tops out below the target FPS. A variable camera range may slow down in low light, and actual FPS still depends on device performance. MediaCodec output drains on its own callback thread; GPU submission has bounded pending frames and a stalled encoder enters bounded component recovery. Raw pixels never cross the MoQ FFI boundary. Encoded video queue overflow waits for a fresh keyframe instead of publishing deltas with missing references. Diagnostics record `gpu_submit_*` timings and queue media age. The earlier YUV encoder remains as a separate backend in source, but the app's normal capture path uses surfaces.

Audio capture/publication uses a separate lock from video encoding. A capture gap resets the Opus sample clock so resumed audio follows live time; transient microphone failures restart AudioRecord with a bounded retry policy and a new audio track. Live gain changes ramp over audio frames, and mute ramps to a valid silent Opus stream without rebuilding the MoQ session. The audio meter reports peak, RMS, peak hold, dBFS, channel peaks and clipping. The Audio settings list supports the system default, built-in, wired, USB and Bluetooth inputs, reports the actual route and falls back when a selected device is removed. Video carries SPS/PPS (plus VPS for H.265) on every keyframe, uses a one-second keyframe interval, and requests a fresh keyframe when viewers subscribe. The H.265 option requires a hardware HEVC encoder on the phone and HEVC decoding support in the viewing browser.

## Report a playback problem from the phone

1. Install v1.0.3 or later, start a broadcast and reproduce buffering or delay in moq.watch for about one minute.
2. Return to the broadcaster and tap **Export diagnostics** on the main screen. This works while live, after stopping, and after an error.
3. Select **Share** to share the ZIP, or **Save file** to save it with Android's file picker. Attach that ZIP to the support conversation. Include the viewing URL and viewing browser if available.

Recording starts automatically. A rotating event history survives app restarts and is capped at 1 MiB in the app's private, non-backed-up storage. Per-frame counters stay in memory; once-per-second summaries record actual camera/submitted/encoded/published FPS, pacing/busy drops, keyframes, frame handoff latency, audio packets/resets and ages of the last camera/video/audio activity. Session samples add RTT, measured send rate, capacity estimates, transport loss/counters, encoder queue size, CPU, heap use and thermal status.

Starting with v1.0.4, reports also record camera-selected sizes/FPS, actual frame dimensions/crops/rotation/plane strides, and average/maximum pixel-copy, codec-input, codec-drain, MoQ-publication and full video-callback times. These stage timings are nested: codec drain includes MoQ publication, and the callback includes both; they should not be added together. Version 1.0.5 adds saved bitrate/adaptation settings, session-specific FPS query results and preview resolution. The reported `video_budget_bps` tracks the last bitrate successfully accepted by the encoder, including when dynamic bitrate changes are rejected.

Each ZIP contains `summary.txt` (device, state, settings, network and AVC/HEVC encoder capabilities), `events.log`, a best-effort `app-logcat.txt` snapshot of the current app process, and a field guide in `README.txt`. Authentication fields are omitted; URL query values, URL credentials, bearer tokens and recognizable JWTs are redacted before persistence and again before export. Camera/video frames and microphone samples are not recorded. The Android share grants read access to the selected ZIP only; saving uses the system file picker and needs no storage permission. The latest three exports are retained in app cache.

## Project layout

- `MainActivity.kt` contains the Compose screens and permission flow.
- `MainViewModel.kt` owns persisted settings and validation and observes the application-owned broadcast state. Live statistics include the actual hardware encoder name when the device reports it.
- `BroadcastSession.kt` and `BroadcastService.kt` own the live session, foreground notification, generation-safe stop actions and CPU wake lock.
- `CameraPreview.kt` owns only the display texture and camera gestures; `CameraCaptureEngine.kt` owns the independent CameraX lifecycle.
- `CameraSurfaceRenderer.kt` and `SurfaceVideoEncoder.kt` feed GPU surfaces to the hardware encoder and drain compressed frames asynchronously.
- `MoqStreamer.kt` owns the MoQ session, media producers, audio capture and cleanup.
- `HardwareVideoEncoder.kt`, `YuvPlaneCopy.kt` and `AnnexB.kt` handle hardware encoding, camera/codec plane layouts and independently decodable H.264/H.265 access units.
- `CameraCapturePolicy.kt` bounds sensor resolution choices and chooses supported camera FPS ranges.
- `AudioTimeline.kt`, `VideoFramePacer.kt`, `VideoBitrateControl.kt`, `VideoBitrateSettings.kt` and `SendRateEstimator.kt` handle live timing, encoder rate settings/control and measured transport throughput.
- `AppDiagnostics.kt`, `DiagnosticRecorder.kt`, `StreamMetrics.kt` and `DiagnosticsExportButton.kt` record bounded diagnostics and export/share reports from the phone.
- `RelayUrl.kt` contains URL construction and masking helpers with JVM unit tests.

## Verification

Run the unit tests with:

```text
./gradlew testDebugUnitTest
```

Build the debug APK with:

```text
./gradlew assembleDebug
```

Run Android lint with `./gradlew lintDebug`. JVM tests cover timing, throughput, stride-aware pixel copying and AVC/HEVC access-unit packaging. Hardware encoding and end-to-end relay playback require an Android device; successful JVM tests and APK compilation do not validate those device paths.


## APK variants (v1.0.49)

Build all variants with `./gradlew assembleDebug assembleCompact assembleRelease assembleOffline testDebugUnitTest lintDebug lintCompact lintRelease`. Compact and release enable R8/resource shrinking; debug retains development tooling. Release, compact and debug omit `libmoq_ffi.so`, while offline packages the official core as verified assets for the three supported Android ABIs. The local APKs are signed with the existing development certificate for in-place sideload updates. The Kotlin client remains 0.5.3 / FFI bindings 0.4.26; startup accepts newer official cores only when the complete UniFFI contract matches (currently 0.4.27). The core architecture must match both the device ABI preference and the running process bitness. The official upstream currently supplies no 32-bit x86 core.

Build a local single-ABI Offline APK with `./gradlew assembleOffline -PmoqAbi=arm64-v8a`. The other supported values are `armeabi-v7a` and `x86_64`. GitHub builds include every supported ABI.

## Main app updates

The app checks `https://api.github.com/repos/apple050620312/MoQPublisher/releases/latest` at startup and when returning to the foreground. A newer release APK can be downloaded from Settings after broadcasting stops. The updater only accepts the versioned `MoQPublisher-<version>-release.apk` asset from this repository, follows HTTPS GitHub asset redirects, checks the declared size and SHA-256 digest, then hands the verified file to Android's package installer. It never replaces an APK silently or while a broadcast is active.

Available App and MoQ core updates now open an on-screen reminder at startup and when returning to the foreground. The reminder combines both updates, offers APK download or installation, and opens the About section directly. A downloaded APK prompts again when it is ready to install. Core reminders explain when a download is deferred until broadcasting stops, when a downloaded core will apply on the next cold start, or when a newer core requires updated app bindings. Dismissing a reminder suppresses that version and stage for the current foreground session.

Version 1.0.19 gives update APKs and diagnostic exports separate FileProvider components. Android caches local providers by component class; sharing one class between the two authorities could send the installer an unreadable APK URI. Installation now includes a ClipData read grant, opens Android's unknown-app-source permission screen when needed, and resumes installation after permission is granted. Before opening the installer, the app rechecks the file size, SHA-256, package name, release version, increasing version code, Android compatibility and signing certificate on an IO dispatcher. If an earlier version reports a package parsing error, download this release APK in a browser and install it over the existing app once; subsequent updates use the repaired flow.

Run `./publish-release.ps1` on the development computer after committing and pushing source changes. The script runs the signed builds, JVM tests and release lint locally, checks package/version, certificate and ZIP alignment, then uploads the release APK, offline APK and both SHA-256 sidecars to the matching GitHub tag. It verifies the uploaded digests. Use `./publish-release.ps1 -ValidateOnly -SkipTests` to validate already built APKs locally.

The desktop backup gateway serves Enhanced RTMP and SRT from a Relay Subscribe connection. [Gateway setup and validation](docs/testing/BACKUP_GATEWAY.md)

For production distribution, provide the keystore path and its credentials through the local `MOQ_PRODUCTION_KEYSTORE`, `MOQ_PRODUCTION_STORE_PASSWORD`, `MOQ_PRODUCTION_KEY_ALIAS` and `MOQ_PRODUCTION_KEY_PASSWORD` environment variables. Run `./gradlew bundleProduction --no-configuration-cache` for a store bundle or `./gradlew assembleProduction --no-configuration-cache` for an APK. Keep the official keystore outside the repository. Production uses the same application ID and receives App updates through its distribution channel. Android requires a fresh install to switch from the sideload certificate to an unrelated production certificate. Before migrating, save the connection and quality settings you need and restore them after reinstalling. Re-enter relay credentials in the new installation.

`AudioSettings.kt` processes interleaved signed 16-bit PCM before Opus encoding. Economy uses 32/64 Kbps for mono/stereo, Standard 64/128, High 96/192 and Maximum 128/256. Standard preserves the previous bitrate. Capture layout, frame size, encoder channels and video bandwidth reservation use the actual selected format and quality. Unsupported stereo retries mono, selects the corresponding mono bitrate and shows a localized notice. Gain is applied with saturation; 100% preserves every input sample.

`AppLocale.kt` scopes Android configuration/resources to Compose, preserving the Activity context chain and avoiding activity/session restarts. `AppSettings.kt` persists the default Chinese/dark choices and alternatives; all interface messages use Android string resources, including errors already on screen. `MainActivity.kt` shares cards, controls and status notices between portrait/landscape layouts, wraps options on narrow screens and scrolls control panels on short screens. It applies FLAG_KEEP_SCREEN_ON during connection/live states and releases it when stopped or failed.

Additional JVM tests cover Kbps migration/boundaries, audio gain/clipping and stereo fallback. Device tests in `SettingsPersistenceTest.kt` and `AppearanceTest.kt` cover real preferences and immediate resource/theme/error updates; compile with `./gradlew assembleDebugAndroidTest`, and execute with `./gradlew connectedDebugAndroidTest` on an ARM64 Android device. A build without a connected device does not validate microphone hardware, actual layout or end-to-end playback.

## Audio continuity and core updates in 1.0.10

Microphone capture and publication use separate audio-priority threads. The bundled Opus implementation is retained inside the downloaded core. A local compressed-packet bridge forwards each packet immediately into 200 ms MoQ groups, reducing audio QUIC stream creation from 50 to 5 streams per second. It preserves OpusHead/pre-skip and epoch resets. Video encoding and transport remain unchanged. Diagnostics add microphone/publication intervals, queue wait, PCM silence/peak and hardware capture-clock observations without recording microphone samples.

Official artifacts are fetched over verified HTTPS. The updater checks artifact SHA-256, source binding contracts, the extracted ELF architecture and all 213 required native ABI checks before activation. Downloads are cached in app-private storage and do not need to repeat for each APK update or build type. Existing cores work offline, and updates never replace an active process library during a broadcast.

See `build/diagnostics/audio-20261116/verification.md` for diagnostic evidence, file changes, native audio verification, APK hashes and test limitations.

## Camera, background broadcasting and surface pipeline in 1.0.11

Exposure, landscape lock, background broadcasting and audio quality are persisted. Camera exposure is clamped to each newly attached camera's range. The foreground service starts from a visible Activity, declares the camera and enabled microphone service types, and releases its wake lock and notification when stopped. A stopped process is not automatically restarted into camera/microphone capture; Android force-stop, revoked permissions and vendor battery policies can end a broadcast.

Activity destruction no longer closes the streamer or the live camera. A stream generation prevents an old service/notification from stopping a newer broadcast. The broadcast job owns cleanup exactly once. The GPU renderer uses an off-screen context and independent display/encoder surfaces; the front preview is mirrored explicitly while encoded video stays unmirrored. Display rotation keeps the live encoding orientation stable. Audio publication uses one coroutine event loop per publisher thread, preserving immediate Opus delivery and 200 ms groups without creating a new blocking coroutine for each packet. The app continues to use the official Kotlin bindings and downloaded compatible Rust core.

See `build/diagnostics/v1.0.11/verification.md` for the changed files, validation and device-test limitations. JVM tests and host native Opus tests do not validate an Android GPU/codec, actual background capture, layout or real network playback.

## Cloudflare and camera orientation in 1.0.12

Both providers share the same camera, H.264/H.265 encoders, Opus encoder, Hang catalog/tracks,
timestamps, bounded queues, adaptation and foreground service. Changing providers while stopped
does not require restarting the app and preserves each provider's saved destination. Cloudflare
always verifies TLS and disables the moq.pro WebSocket fallback. Connection tokens are AES-GCM
encrypted with an Android Keystore key, excluded from backup, and never logged in URLs. The old
moq.pro authentication preference migrates into the encrypted store after a successful write.

Enter a different **Subscribe-only connection token from the same relay** and use a `.hang`
broadcast name to enable the app's viewing and OBS information. The app never substitutes the
Publish Token. Before copying, confirm that the opaque token has Subscribe-only permissions; the
app cannot infer those permissions. Shared links intentionally contain the Subscribe Token. Draft 18 links use
`https://moq.watch/<subscribe-token>/<full-broadcast-name>?cloudflare=draft-18`.

Connected means session establishment; Publishing requires a subscriber's video demand and media
handoff. Neither proves remote decoding. While broadcasting, **Verify remote media decoding**
opens a distinct remote Subscribe session, waits for the Hang catalog, decodes three video frames
with Android MediaCodec and, when enabled, three Opus packets with the existing SDK. It times out
after 30 seconds and closes its consumers/connection. This is extra network traffic, not a
moq.watch browser test. No Cloudflare account tokens or Android device were available during
development, so real Cloudflare publication, browser decoding and device behavior remain to be
verified. SDK 0.5.3 does not expose a negotiated-version getter or namespace-acceptance ACK.

The SDK's automatic reconnect re-announces broadcasts with exponential backoff. A change of the
Android default network additionally rebuilds the session/media publishers; both routes request a
fresh keyframe, discard the old queued GOP and preserve the foreground service. Stats distinguish
the selected endpoint draft from the unexposed negotiated draft and count established reconnects.

The 1.0.11 rendering path could apply HAL texture orientation and app rotation together. 1.0.12
normalizes the HAL orientation/mirroring while retaining its crop/GL flip, applies clockwise camera
rotation and center crop once through shared UV/metering geometry, and tracks 180-degree display
changes. Only the front preview is mirrored. The camera dump now records transformation metadata
without image pixels. JVM tests verify geometry, not real Android GPU output; phone verification
of portrait, both landscape directions, front/back cameras and receiver aspect remains necessary.

See `build/diagnostics/cloudflare-v1.0.12/verification.md` for compatibility evidence, file changes,
build/test results and the remaining device checks.
