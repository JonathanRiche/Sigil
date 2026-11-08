# Rust and Kotlin JNI contract, v1

Owner: Rust core worker. Package/object: `dev.omarchy.gliff.NativeCore`. Preserve existing `viewport(Int, Int): Long`. All new JNI methods are instance native methods on that Kotlin object. No overloaded methods.

```kotlin
external fun openSession(host: String, port: Int, user: String,
    privateKey: String, passphrase: String, fingerprint: String,
    maxWidth: Int, maxHeight: Int): Long
external fun openSessionWithMode(host: String, port: Int, user: String,
    privateKey: String, passphrase: String, fingerprint: String,
    maxWidth: Int, maxHeight: Int, headless: Boolean): Long
external fun pollEvent(handle: Long): ByteArray?
external fun sendControl(handle: Long, kind: Int, a: Double, b: Double, c: Int, d: Int): Boolean
external fun setClipboard(handle: Long, text: String): Boolean
external fun requestClipboard(handle: Long): Boolean
external fun closeSession(handle: Long)
```

`openSession` starts asynchronously and returns a positive opaque handle, or zero for invalid arguments/resource limits. Host, port and user are separate fields, not an SSH command or URL. `privateKey=""` with `passphrase=""` explicitly selects keyless SSH method `none` for Tailscale SSH. Tailnet identity and server SSH policy provide authorization; no private key is required. A nonempty privateKey explicitly selects advanced OpenSSH/PEM public-key authentication; empty passphrase means unencrypted. There is no ambient credential lookup or silent switch from failed key authentication to keyless. The initial implementation rejects nonempty passphrases and encrypted keys explicitly because encrypted-key KDF resource limits are not implemented. `fingerprint` is an exact OpenSSH SHA256 pin when saved. Empty fingerprint explicitly selects first-use discovery: the core emits HostKey type8 with the observed fingerprint and rejects before authentication, then Closed. Kotlin must ask Trust host? and persist per host/port only after explicit acceptance, close the old handle, then retry with that exact pin. A nonempty mismatch is fatal and never produces a replacement suggestion; malformed nonempty pins fail. Discovery observes an unverified endpoint and is not independent verification. No known-host lookup, ambient keys/agents, password auth, accept-any callback or shell interpolation. The existing openSession defaults to desktop mirroring with exactly `gliff-server --stdio --output auto --low-bandwidth`, without PTY. The additional openSessionWithMode uses the same arguments plus headless:Boolean: false selects that mirror command; true selects exactly `gliff-server --stdio --headless --low-bandwidth`. These are two fixed allowlisted literals, never a shell string from Kotlin. Mode is immutable per session; changing it requires close/reconnect.

Max dimensions are decoder-supported caps, not the current viewport. Initial viewport uses these caps. For pinned upstream, Rust answers the initial server Ping with Pong before sending Resize and publishing Connected. The pinned server Ping is required. Missing Ping times out after 15 seconds; application traffic before that Ping is rejected. No no-Ping compatibility mode is implemented. A queued pre-ready Resize replaces the initial viewport. Send another Resize after Connected if current bounds differ. Keep caps conservative, currently each side <= 4096 and total <= 3840*2160. All JNI calls are short and nonblocking except bounded key/string conversion. `pollEvent` returns null when no event is queued. Calls may come from different Kotlin threads, but serialize poll calls on one consumer. `closeSession` is idempotent, removes the handle and signals cancellation. A closed handle cannot be reused. Another open returns zero until the previous terminal handle is explicitly closed and its worker has finished shutting down. Call closeSession after Error/Closed before reconnecting. Completed sessions discard all queued regular events, retaining only bounded Error/Closed metadata; close also drains all queued events. Kotlin clears key/password fields after use; Java String and dependency-parser secret copies cannot be reliably zeroized by this bridge, so complete key-byte eradication and persistent secrets are outside its guarantees.

## Controls

`sendControl` returns false for invalid parameters, closed handle or full input queue. Handle false explicitly; do not silently lose key/button releases. Sizes and coordinates must describe the remote displayed frame geometry, not the device screen.

| kind | meaning | a | b | c | d |
| --- | --- | --- | --- | --- | --- |
| 1 | Resize | physical width | physical height | scale_milli (500..4000) | unused |
| 2 | Key | unused | unused | Linux evdev keycode | 1 pressed, 0 released |
| 3 | PointerMotion | remote logical x | remote logical y | unused | unused |
| 4 | PointerButton | unused | unused | evdev button (272 left, 273 right, 274 middle) | 1 pressed, 0 released |
| 5 | PointerAxis | continuous scroll delta | unused | 0 vertical, 1 horizontal | 1 stop, 0 active |
| 6 | FrameAck | unused | unused | frame id low 32 bits | frame id high 32 bits |
| 7 | RequestKeyframe | unused | unused | unused | unused |
| 8 | ReleaseAll | unused | unused | unused | unused |

FrameAck is delivery/flow accounting, not proof of decode or rendering. For video delivered to Kotlin, send exactly one Ack after successful bounded MediaCodec input acceptance; do not wait for output and do not send a duplicate from the output callback. Validate the access unit/SPS before queueing. Keep output/render token tracking and bounded decoder stall guards separate. A deliberately discarded setup/recovery frame is also accounted and followed by a keyframe request. While Rust itself waits for a keyframe, it discards non-keyframes and sends its own Ack with decoded_at_ms=0 plus RequestKeyframe. The pinned server uses frame_id cumulatively for in-flight accounting and ignores decoded_at_ms. IDs are reconstructed using unsigned 32-bit halves, avoiding Double precision loss. `decoded_at_ms` retains its upstream wire field name but is supplied by Rust's monotonic session clock at flow-Ack dispatch; it is not decoder completion telemetry. Application input starts only after Connected. Before the initial Pong, only the latest queued Resize is retained; other queued controls, including keys and clipboard, are discarded rather than replayed later. Resize may arrive before handshake and is retained as the latest desired viewport. ReleaseAll releases Rust-tracked held keys/buttons. Cancellation interrupts whole operations, including blocked writes, release loops and event emission. Best-effort release runs only at a known outbound frame boundary, with one aggregate 200 ms deadline and 300 ms cleanup deadline. An interrupted/failed frame write makes the stream terminal: no release or Bye frame is appended. Closing the underlying transport, including a pending background SSH KEX task, does not depend on further peer progress. A separate SSH disconnect attempt has at most 300 ms. Network loss can prevent release delivery; server-side virtual-input teardown remains essential.

## Events

Every ByteArray starts with one type byte. All following integers use little endian. Fields consume the whole fixed header; remaining bytes, where present, are the body described below. Unknown types must be ignored safely. Poll up to a bounded number of events per UI tick. MediaCodec work belongs off the UI thread.

| type | bytes following type |
| --- | --- |
| 1 Status | UTF-8 status text, informational only; `Connected` means HelloAck accepted and initial Pong/Resize completed |
| 2 StreamConfig | u32 width, u32 height, u32 scale_milli, u32 view_width, u32 view_height, u32 fps_cap; 25 bytes total |
| 3 Video | u64 frame_id, u64 pts_us, u8 keyframe (0/1), then Annex B H.264 access-unit bytes; fixed header 18 bytes total |
| 4 ClipboardText | UTF-8 text requested explicitly by Kotlin |
| 5 Error | UTF-8 safe error text; terminal, no automatic retry or secret/raw server stderr details |
| 6 Closed | no additional bytes; terminal |
| 8 HostKey | UTF-8 observed canonical SHA256 fingerprint, before authentication; explicit trust required, then retry with exact pin |
| 7 AuthApproval | UTF-8 HTTPS approval URL at `https://login.tailscale.com/a/`, emitted only from the pinned server auth banner; open only after explicit user action |

Only H264/Single420 is accepted. Codec/chroma are therefore implicit in event 2. SPS/PPS are in-band on IDRs; Rust rejects unsupported nonempty extradata rather than silently dropping it. StreamConfig and Video events preserve wire order. Do not paint/input against a new configuration before its video is actually shown. Rust does not decode H.264. A change of coded width/height requires decoder generation reset and a keyframe request. FPS-only config updates must preserve the decoder and reference chain. Scale/view-only changes update geometry without recreating MediaCodec. No arbitrary drops from a live reference chain occur in Rust. During explicit keyframe recovery only, non-keyframes are discarded/acked as described above until a keyframe arrives. Event backpressure is bounded and may fail the session if Kotlin stops draining. On terminal completion queued regular events are discarded; Error/Closed remain available until close.

Clipboard is explicit text only, initially capped at 256 KiB UTF-8, no images/files/background sync. `setClipboard` advertises exactly the supplied text; a trusted host can request advertised text without a remote paste gesture. `requestClipboard` requests the latest supported remote text offer. Never call either automatically during IME composition. The remote app still requires an explicit paste shortcut after an offer. Clipboard is scoped to the open session and discarded on close.

Memory: one retained session handle, at most 64 controls and four events, and an 8 MiB combined frame budget. At most 16 MiB queued clipboard command text plus 32 MiB queued event content, with up to two frame-sized working buffers during event creation and JNI copying. Caller-retained Java arrays are outside the Rust bound. Key input is explicitly provided, not read from a path. Authentication supports whatever key formats/algorithms the selected SSH library actually parses; errors are surfaced without key material. Android integration must be tested separately from Rust mock protocol tests.

Pinned startup evidence: [server session lines 114-163](https://github.com/omacom/gliff/blob/32bb0622cf2feef69515bf8f75a836f657f06083/crates/gliff-server/src/session.rs#L114) sends HelloAck, then Ping, then StreamConfig. [Protocol Pong definition](https://github.com/omacom/gliff/blob/32bb0622cf2feef69515bf8f75a836f657f06083/crates/gliff-proto/src/msg.rs) specifies answering before other client traffic. The implementation targets that pinned sequence; earlier no-Ping compatibility speculation is withdrawn.

## Tailscale SSH mode

Official Tailscale SSH uses SSH authentication method `none`, after the tailnet transport already identifies the peer. `check` policy can hold that request while emitting an SSH auth banner with a browser approval link. It does not require keyboard-interactive password prompts. The client waits up to 180 seconds for keyless setup, remains cancellable, and never supplies a fake password or changes host policy. A rejected none request fails clearly. Explicit-key setup shares the 180-second initial reachability budget; explicit authentication rejection is still terminal.

AuthApproval type7 is one bounded validated login.tailscale.com/a/ URL from the already fingerprint-verified SSH endpoint. Kotlin must display an explicit approval action, not auto-open or fetch that URL; it contains session-specific approval data and must not be logged or persisted. Unsupported banner links are not opened. Browser completion is enforced by the Tailscale server: Rust waits for SSH auth success and only then executes the fixed command selected by the explicit mirror/private-screen boolean. Saved host fingerprints remain exact in both modes. First-use discovery never authenticates; explicit trust followed by a pinned retry is required. Tailscale app/VPN must already be connected on the phone, and host policy must authorize the requested Unix user. Keyless mode alone does not prove an arbitrary endpoint is Tailscale; endpoint trust comes from the supplied pin and chosen destination.

References inspected read-only: https://tailscale.com/docs/features/tailscale-ssh and https://github.com/tailscale/tailscale/blob/main/ssh/tailssh/tailssh.go (NoClientAuthCallback, clientAuth HoldAndDelegate and SendAuthBanner). No keyboard-interactive or password fallback is implemented.

## Mirror and optional private screen

Mirror is the default: --output auto chooses the host's focused output, showing its existing desktop/workspace. The optional headless boolean requests a separate private output, which can have different/empty workspaces. Both HelloAck headless values are accepted; protocol v1 and greeting ordering checks remain unchanged.

Pinned server session.rs:649-663 branches before monitor-mode changes: mirror Resize calls fit_mirror, which fits/downscales the encoded stream; only headless Resize changes a private monitor mode. Therefore phone orientation/resizing must continue sending bounded viewport Resize, but must not issue physical host monitor configuration commands. No host configuration edits are part of this change. Shared-frame view/coded dimensions and effectiveScale still govern pointer mapping. The Android decoder, caps and event formats are unchanged; a mode toggle is an Advanced optional UI choice, not a required setup step.

## Real-device buffered decoder flow control

Live QTI evidence showed 466 accepted access units, 450 outputs/Acks/renders and a stable pending count of16 while new video stopped; platform low-latency support was not advertised. Pointer/control remained usable. Output-only acknowledgements can make the server byte window wait for decoder output while the decoder waits for more input. Bounded input-acceptance acknowledgements break that circular dependency without removing the four-event queue or pending-frame/output watchdogs. Real-device compile/reconnect verification remains required; vendor buffering may still add display latency or hold final frames during an idle scene. Do not label accepted frames as decoded/rendered in counters.

Input-acceptance Ack rate implications: the pinned server measures its own send-to-Ack time and cumulative delivered bytes; it ignores the legacy decoded_at_ms field. Earlier Acks therefore describe network plus client admission latency, not the phone's output/render latency, and can make the server choose a higher rate than output-based feedback. The client must retain the four-event queue, bounded codec admission, pending-output/render limits and stall termination rather than accepting unbounded work. Vendor output buffering is separately reported by output/render/pending counters. The current Rust wire encoder still puts session-monotonic dispatch time in decoded_at_ms for JNI Acks (zero for Rust recovery discards); that value must never be presented as measured decode completion. No Rust source or ABI change is needed for this Android-side Ack timing change.

## Host cursor events

Mirror sessions now forward pinned upstream CursorShape and CursorPos. Private-screen sessions suppress these events because that capture already composites its cursor. Existing JNI methods and video/Ack paths are unchanged. All fields below are little endian; event payloads have no padding.

- Type9: u8 type, u32 shapeId, u32 width, u32 height, i32 hotspotX, i32 hotspotY, u32 sourceScaleMilli, then width*height*4 tightly packed BGRA8888 bytes. Header25 bytes. Dimensions are1..1024, payload at most4MiB, hotspot must lie inside the image, and length must match exactly.
- Type10: u8 type, f64 x, f64 y, u32 shapeId, u8 visible, u32 sourceScaleMilli. Total26 bytes. Position is the actual host hotspot in transformed capture-buffer pixels, not logical coordinates. Finite signed i32-range positions are accepted, including negative or outside-buffer positions. They must be clipped by the display view, not clamped into a fabricated visible pointer.

Source scale comes from the HelloAck output matching SessionInfo.output, with bounded output metadata parsing. Convert cursor positions, bitmap dimensions and hotspot offsets from buffer pixels to remote logical units by dividing by sourceScaleMilli/1000, then apply the same logical-to-display geometry as video/input, including letterbox offsets. Hide the cursor when visible is false, its shapeId has no matching bitmap, or the session/view is torn down. Retain at most one current shape and one current position; coalesce UI position updates. Apply shape changes only with their matching identity. Never synthesize a cursor at the touch position. The existing four-slot event queue and frame budget bound transit allocations; one shape is at most4MiB. Hello metadata is not allocated into an output collection.

References: pinned [CursorShape/CursorPos schema](https://github.com/omacom/gliff/blob/32bb0622cf2feef69515bf8f75a836f657f06083/crates/gliff-proto/src/msg.rs), [BGRA buffer layout](https://github.com/omacom/gliff/blob/32bb0622cf2feef69515bf8f75a836f657f06083/crates/hypr-capture/src/cursor.rs), and [Wayland cursor capture coordinate definitions](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/blob/main/staging/ext-image-copy-capture/ext-image-copy-capture-v1.xml).

## Typed authenticated desktop integration

```kotlin
external fun remoteAction(handle: Long, kind: Int, identity: String): Boolean
external fun openSessionOnOutput(host: String, port: Int, user: String,
    privateKey: String, passphrase: String, fingerprint: String,
    maxWidth: Int, maxHeight: Int, output: String): Long
```

The action queue has8 slots, separate from Gliff input/video. False means invalid arguments, unavailable handle or full queue, not success. Completion is event12: u8 type12, u32 actionKind, u8 success, then optional UTF8 error message. These action errors do not terminate streaming. Dispatch only after Connected and await completion for repeated user actions. No automatic retry of side effects. One action runs at a time on an auxiliary channel of the existing authenticated, host-verified connection; no additional login or ambient credentials. Channel opening is limited to3 seconds, execution to8 seconds, response bytes to64KiB, event delivery to2 seconds. Session cancellation interrupts the action worker. Commands are fixed helper code plus validated address/decimal identities. No Java shell commands are accepted.

| kind | action | identity |
| --- | --- | --- |
| 1 | Query desktop metadata | empty |
| 2 | Focus actual window | exact metadata address, 0x plus up to16 hex digits |
| 3 | Previous existing workspace | empty |
| 4 | Next existing workspace | empty |
| 5 | Select existing normal workspace | decimal id, max9 digits |
| 6,7,8 | Volume up, down, mute toggle | empty |
| 9,10,11 | Media play/pause, next, previous | empty |
| 12,13 | Display brightness up/down5% | empty |
| 14 | Host Omarchy launcher/menu | empty |
| 15 | Insert dictated/committed text into actual focused app | UTF8 text, max16KiB, no NUL |

Query success emits event11: u8 type11 followed by UTF8 JSON, then normal event12 success. Schema: clients array `{address,class,title,workspace,monitor}`; outputs array `{name,width,height,scale,focused}`; workspaces array `{id,name,monitor}`; battery null or `{percent,status}`; capabilities object `{overview,launcher,volume,brightness,media,text}`. Metadata is projected from actual hyprctl clients/monitors/workspaces, with at most128 clients,32 outputs,128 workspaces and bounded strings. No arbitrary host files are read. Battery includes system Battery supplies only, excludes scope Device, and is null when absent. Capability presence reports installed helpers, not guaranteed hardware support; actual failures must be shown. Query on Connected or when opening/refreshing dock/overview, not every video frame. Treat titles/classes as untrusted display text, never executable commands.

The current host has only the hyprbars plugin and no overview binding. `overview:false` means no compositor overview action. Android can display the actual workspace/window list as its overview and use actions2/5 to navigate; it must not invent thumbnails or send a nonexistent shortcut. Launcher invokes the real Omarchy menu. Media invokes Omarchy shell media IPC, volume its output-volume helper, brightness its display helper. Helpers resolve only a unique same-user Hyprland instance when SSH environment lacks compositor variables; ambiguous sessions fail instead of guessing. Text goes as bounded SSH stdin into wtype, never a shell argument. Phone recognition/permission and explicit user commit are Android responsibilities; no audio is streamed to Gliff.

`openSessionOnOutput` is mirror-only and requires closing the previous handle first. Connector identity must be selected from metadata, length1..128, begin alphanumeric and contain only ASCII alphanumeric, dash, underscore, period or colon. Rust rejects all other forms. The only generated streaming command is `gliff-server --stdio --output CONNECTOR --low-bandwidth`; Gliff resolves the existing connector and rejects missing outputs. This does not change monitor modes/configuration. Auto uses existing openSession; private output uses openSessionWithMode. No live in-protocol output-selection message exists in pinned Gliff, so reconnect is explicit and retains normal trust checks.

## Initial wake/reachability retry

Initial setup has one cancellable 180-second budget. TCP attempts are bounded to 8 seconds and SSH key exchange to 10 seconds, with 2 seconds between transient retries. Keyless authentication without an approval banner has a 12-second response deadline; a verified Tailscale approval banner preserves the same pending authentication within the overall deadline. Discovery, host-key rejection, explicit authentication rejection, and exec failures are terminal. Failed attempts drop their controlled-IO lifetime before replacement. Status events describe retry progress; retries do not create a new UI session or replay remote actions. Established-session network reconnect remains unsupported. No Wake-on-LAN packet is sent.
