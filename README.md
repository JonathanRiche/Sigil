# Sigil

An experimental Android remote desktop client for Omarchy and Hyprland, designed for foldable phones. Sigil uses [Gliff](https://github.com/omacom/gliff) over SSH, with a Rust session core and a Kotlin Android interface.

**Preview status:** real Tailscale SSH authentication, desktop mirroring and sustained video have worked on a physical foldable. Tabletop controls and saved Standard/Split keyboard selection have also been exercised. Live emulator checks also confirmed the app dock, monitor list, and video continuity across layout and rotation changes. A standard cursor is displayed at the actual host position when the compositor omits its cursor image. Some gestures, dictation and physical-fold lifecycle behavior on the newest build remain unverified. This is an experimental preview.

## Connect

The desktop needs a running Hyprland session, `gliff-server` on the remote user's PATH, and working SSH access. Sigil currently targets Gliff protocol v1 at [revision 32bb062](https://github.com/omacom/gliff/tree/32bb0622cf2feef69515bf8f75a836f657f06083); compatibility with other revisions is not guaranteed.

1. Connect the phone and desktop to the tailnet and enable Tailscale SSH through your existing tailnet setup and policy.
2. Enter the desktop hostname and remote username. Private-key setup is not required for Tailscale SSH: Sigil explicitly uses SSH authentication method `none`, letting Tailscale authorize the tailnet identity.
3. On first connection, review the SHA256 host fingerprint and explicitly choose whether to trust it. Compare it through an independent trusted channel when available. Discovery disconnects before authentication; acceptance saves the pin for that host and port, then retries. A changed saved key fails rather than being silently replaced.
4. Complete any Tailscale browser approval requested by the server, then connect.

If the host is sleeping or the network is unavailable, the initial connection retries automatically for up to three minutes. Short TCP/key-exchange attempts replace stale connections, and keyless authentication retries if no response or approval request arrives. A displayed Tailscale approval stays on the same authentication attempt. Cancel stops retries immediately. This waits for reachability; it does not send Wake-on-LAN packets.

Mirroring the existing desktop is the default. Advanced settings retain an optional private screen. The fixed server commands are `gliff-server --stdio --output auto --low-bandwidth` and, for private mode, `gliff-server --stdio --headless --low-bandwidth`. Window focus and workspace switching work with both the legacy Hyprland dispatch syntax and the Lua syntax required since Hyprland 0.56. Selecting an existing display reconnects with its validated connector name; it does not reconfigure the host monitor.

Conventional SSH key authentication is an advanced option. Imported keys must be unencrypted OpenSSH/PEM keys; the app encrypts its stored copy with Android Keystore. Encrypted input keys, password authentication, ambient SSH agents and automatic host-key replacement are unsupported.

## Interface

- Desktop video, direct touch input, relative pointer controls and explicit text clipboard sharing.
- Tabletop/Flex layout with desktop above and controls below, plus a manual layout option where posture reporting is unavailable.
- A keyboard-deck control surface: a rounded panel of sculpted keys in graphite or aluminium (follows the system theme; the half-circle pill switches it). A top strip holds settings, display selection, the open-app dock, brightness, volume and appearance; zoom, overview, dictation and the Keyboard/Trackpad switch sit beside or below the keys. During a live session with the deck open, the top bar is replaced by a small status pill showing the host and frame rate; tap it for connection setup.
- Standard and Split on-screen US keyboards, saved layout preference and physical keyboard support.
- New metadata-backed app dock, window focus, workspace navigation/overview, display selection and remote battery status.
- New remote volume, media, brightness and launcher controls, with unavailable actions reported rather than simulated.
- New platform speech recognition with explicit text insertion into the focused remote app. Microphone permission and an installed recognition service are required; that service determines whether recognition uses a network.

Expanded controls have partial live validation, not comprehensive device coverage. The overview lists actual workspaces/windows; no compositor overview is assumed. Battery is absent on a desktop without a system battery. Cursor position and visible fallback rendering were confirmed in a live emulator session. The fallback does not reproduce host-specific pointer shapes. App dock tiles use class initials when icon artwork is unavailable.

Sigil negotiates one H.264 4:2:0 stream for Android MediaCodec. It does not assume hardware H.264 4:4:4 decoding and does not reconstruct Gliff's dual-stream full-colour mode. Coloured text may look softer. Flow acknowledgements follow bounded codec admission, so they do not measure actual render latency.

There is no audio streaming, file/image clipboard, full IME composition, automatic network reconnect or simultaneous multi-host session. Surface recovery is implemented; actual fold transitions on the latest revision still need validation. Sustained latency, thermal behavior and broad device compatibility are not established.

## Build and releases

See [BUILDING.md](BUILDING.md) for Linux build instructions using your own toolchains. Android 11/API 30 or newer is required; builds include ARM64 and x86_64.

Download the APK from [GitHub Releases](https://github.com/JonathanRiche/Sigil/releases). Version 0.1.0-preview.4 is explicitly a **debug-signed experimental preview**, not a production build. A future production signing key may require uninstalling this preview, which removes saved app data. Checksums and dependency notices accompany the APK. No production-signing or comprehensive compatibility claim is made.

## Upstream and licensing

The protocol adaptation references Gliff revision `32bb0622cf2feef69515bf8f75a836f657f06083`. Its MIT copyright and permission text are retained in [core/UPSTREAM-LICENSE](core/UPSTREAM-LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

A license for Sigil's original code has not yet been selected by its rights holders. The upstream MIT notice does not by itself license all Sigil code or settle dependency obligations. This documentation does not assign copyright ownership or choose a license on anyone's behalf.
