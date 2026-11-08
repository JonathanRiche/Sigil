//! Async protocol engine, independent of SSH and JNI for in-memory tests.

use crate::protocol::{self, ClientMessage as Out, ServerMessage as In, StreamConfig, TEXT_LIMIT};
use crate::wire::{FrameReader, OpaqueFrame};
use std::collections::BTreeSet;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{mpsc, watch};

pub const CONTROL_QUEUE: usize = 64;
pub const EVENT_QUEUE: usize = 4;

#[derive(Debug)]
pub enum Control {
    Resize {
        width: u32,
        height: u32,
        scale_milli: u32,
    },
    Key {
        code: u32,
        pressed: bool,
    },
    Motion {
        x: f64,
        y: f64,
    },
    Button {
        button: u32,
        pressed: bool,
    },
    Axis {
        horizontal: bool,
        value: f64,
        stop: bool,
    },
    Ack(u64),
    Keyframe,
    ReleaseAll,
    SetClipboard(String),
    RequestClipboard,
}

impl Control {
    pub fn from_jni(kind: i32, a: f64, b: f64, c: i32, d: i32) -> Option<Self> {
        match kind {
            1 if a.is_finite()
                && b.is_finite()
                && a.fract() == 0.0
                && b.fract() == 0.0
                && (2.0..=4096.0).contains(&a)
                && (2.0..=4096.0).contains(&b)
                && protocol::valid_dimensions(a as u32, b as u32)
                && (500..=4000).contains(&c) =>
            {
                Some(Self::Resize {
                    width: a as u32,
                    height: b as u32,
                    scale_milli: c as u32,
                })
            }
            2 if (1..=767).contains(&c) && (0..=1).contains(&d) => Some(Self::Key {
                code: c as u32,
                pressed: d == 1,
            }),
            3 if a.is_finite()
                && b.is_finite()
                && (0.0..=65536.0).contains(&a)
                && (0.0..=65536.0).contains(&b) =>
            {
                Some(Self::Motion { x: a, y: b })
            }
            4 if (272..=274).contains(&c) && (0..=1).contains(&d) => Some(Self::Button {
                button: c as u32,
                pressed: d == 1,
            }),
            5 if a.is_finite()
                && a.abs() <= 10000.0
                && (0..=1).contains(&c)
                && (0..=1).contains(&d) =>
            {
                Some(Self::Axis {
                    horizontal: c == 1,
                    value: a,
                    stop: d == 1,
                })
            }
            6 => Some(Self::Ack((u64::from(d as u32) << 32) | u64::from(c as u32))),
            7 => Some(Self::Keyframe),
            8 => Some(Self::ReleaseAll),
            _ => None,
        }
    }
}

pub fn text_event(kind: u8, text: &str) -> Vec<u8> {
    let mut event = Vec::with_capacity(1 + text.len());
    event.push(kind);
    event.extend_from_slice(text.as_bytes());
    event
}

fn config_event(c: &StreamConfig) -> Vec<u8> {
    let mut event = vec![2];
    for value in [
        c.width,
        c.height,
        c.scale_milli,
        c.view_width,
        c.view_height,
        c.fps_cap,
    ] {
        event.extend_from_slice(&value.to_le_bytes());
    }
    event
}

async fn emit(tx: &mpsc::Sender<Vec<u8>>, event: Vec<u8>) -> Result<(), &'static str> {
    tokio::time::timeout(Duration::from_secs(5), tx.send(event))
        .await
        .map_err(|_| "Android event consumer stalled")?
        .map_err(|_| "Android event consumer closed")
}

struct Reader<R> {
    io: R,
    framing: FrameReader,
    buffer: [u8; 16384],
    start: usize,
    end: usize,
}
impl<R: AsyncRead + Unpin> Reader<R> {
    fn new(io: R) -> Self {
        Self {
            io,
            framing: FrameReader::default(),
            buffer: [0; 16384],
            start: 0,
            end: 0,
        }
    }
    async fn next(&mut self) -> Result<Option<OpaqueFrame>, &'static str> {
        loop {
            if self.start != self.end {
                let (used, frame) = self
                    .framing
                    .feed(&self.buffer[self.start..self.end])
                    .map_err(|_| "invalid or oversized wire frame")?;
                self.start += used;
                if frame.is_some() {
                    return Ok(frame);
                }
            }
            self.start = 0;
            // `next` is selected against controls. If read is cancelled, an old
            // end offset must never make the last packet look unread again.
            self.end = 0;
            self.end = self
                .io
                .read(&mut self.buffer)
                .await
                .map_err(|_| "remote stream read failed")?;
            if self.end == 0 {
                self.framing
                    .finish()
                    .map_err(|_| "truncated remote wire frame")?;
                return Ok(None);
            }
        }
    }
}

struct Writer<W> {
    io: W,
    // True from the start of a frame write until the ENTIRE frame succeeds.
    // A dropped/failed write is terminal: cleanup must not append another frame.
    dirty: bool,
}
impl<W> Writer<W> {
    fn new(io: W) -> Self {
        Self { io, dirty: false }
    }
}

async fn write<W: AsyncWrite + Unpin>(
    writer: &mut Writer<W>,
    msg: &Out<'_>,
    payload: &[u8],
) -> Result<(), &'static str> {
    let bytes = protocol::encode(msg, payload);
    writer.dirty = true;
    tokio::time::timeout(Duration::from_secs(3), writer.io.write_all(&bytes))
        .await
        .map_err(|_| "remote stream write timed out")?
        .map_err(|_| "remote stream write failed")?;
    writer.dirty = false;
    Ok(())
}

pub(crate) async fn cancelled(cancel: &mut watch::Receiver<bool>) {
    loop {
        if *cancel.borrow() {
            return;
        }
        if cancel.changed().await.is_err() {
            return;
        }
    }
}

struct State {
    keys: BTreeSet<u32>,
    buttons: BTreeSet<u32>,
    connected: bool,
    hello_received: bool,
    headless: bool,
    source_scale_milli: u32,
    viewport: (u32, u32, u32),
    config: Option<StreamConfig>,
    local_clipboard: Option<String>,
    local_serial: u32,
    remote_offer: Option<(u32, String)>,
    incoming_clipboard: Option<(u32, Vec<u8>)>,
    next_clipboard: u32,
    last_frame: Option<u64>,
    awaiting_keyframe: bool,
}
impl State {
    fn new(width: u32, height: u32) -> Self {
        Self {
            keys: BTreeSet::new(),
            buttons: BTreeSet::new(),
            connected: false,
            hello_received: false,
            headless: false,
            source_scale_milli: 1000,
            viewport: (width, height, 1000),
            config: None,
            local_clipboard: None,
            local_serial: 0,
            remote_offer: None,
            incoming_clipboard: None,
            next_clipboard: 1,
            last_frame: None,
            awaiting_keyframe: true,
        }
    }
    async fn release<W: AsyncWrite + Unpin>(
        &mut self,
        io: &mut Writer<W>,
    ) -> Result<(), &'static str> {
        // One aggregate deadline, not one deadline per held input.
        tokio::time::timeout(Duration::from_millis(200), async {
            for code in std::mem::take(&mut self.keys) {
                write(
                    io,
                    &Out::Key {
                        code,
                        pressed: false,
                    },
                    &[],
                )
                .await?;
            }
            for button in std::mem::take(&mut self.buttons) {
                write(
                    io,
                    &Out::Button {
                        button,
                        pressed: false,
                    },
                    &[],
                )
                .await?;
            }
            Ok(())
        })
        .await
        .map_err(|_| "held input release timed out")?
    }
    fn retain_pre_ready_resize(&mut self, commands: &mut mpsc::Receiver<Control>) {
        for _ in 0..CONTROL_QUEUE {
            match commands.try_recv() {
                Ok(Control::Resize {
                    width,
                    height,
                    scale_milli,
                }) => self.viewport = (width, height, scale_milli),
                Ok(_) => {} // Do not replay pre-ready keys/clipboard after startup.
                Err(_) => break,
            }
        }
    }
    async fn activate<W: AsyncWrite + Unpin>(
        &mut self,
        io: &mut Writer<W>,
        events: &mpsc::Sender<Vec<u8>>,
    ) -> Result<(), &'static str> {
        self.connected = true;
        self.resize(io).await?;
        emit(events, text_event(1, "Connected")).await
    }

    async fn resize<W: AsyncWrite + Unpin>(&self, io: &mut Writer<W>) -> Result<(), &'static str> {
        let (width, height, scale) = self.viewport;
        write(
            io,
            &Out::Resize {
                width,
                height,
                scale: scale as f32 / 1000.0,
            },
            &[],
        )
        .await
    }
    async fn command<W: AsyncWrite + Unpin>(
        &mut self,
        io: &mut Writer<W>,
        cmd: Control,
        elapsed: u64,
    ) -> Result<(), &'static str> {
        if let Control::Resize {
            width,
            height,
            scale_milli,
        } = cmd
        {
            self.viewport = (width, height, scale_milli);
            if self.connected {
                self.release(io).await?;
                self.resize(io).await?;
            }
            return Ok(());
        }
        if !self.connected {
            return Ok(());
        }
        match cmd {
            Control::Key { code, pressed } => {
                if pressed {
                    self.keys.insert(code);
                } else {
                    self.keys.remove(&code);
                }
                write(io, &Out::Key { code, pressed }, &[]).await?;
            }
            Control::Button { button, pressed } => {
                if pressed {
                    self.buttons.insert(button);
                } else {
                    self.buttons.remove(&button);
                }
                write(io, &Out::Button { button, pressed }, &[]).await?;
            }
            Control::Motion { x, y } => {
                if let Some(c) = &self.config {
                    let scale = c.scale_milli as f64 / 1000.0;
                    write(
                        io,
                        &Out::Motion {
                            x: x.min(c.width as f64 / scale),
                            y: y.min(c.height as f64 / scale),
                        },
                        &[],
                    )
                    .await?;
                }
            }
            Control::Axis {
                horizontal,
                value,
                stop,
            } => {
                write(
                    io,
                    &Out::Axis {
                        horizontal,
                        value,
                        stop,
                    },
                    &[],
                )
                .await?
            }
            Control::Ack(id) => {
                // Never let UI acknowledge beyond the latest frame received.
                if self.last_frame.is_some_and(|last| id <= last) {
                    write(
                        io,
                        &Out::Ack {
                            id,
                            decoded_at_ms: elapsed,
                        },
                        &[],
                    )
                    .await?;
                }
            }
            Control::Keyframe => {
                self.awaiting_keyframe = true;
                write(io, &Out::Keyframe, &[]).await?;
            }
            Control::ReleaseAll => self.release(io).await?,
            Control::SetClipboard(text) => {
                if text.len() > TEXT_LIMIT {
                    return Err("local clipboard exceeds text limit");
                }
                self.local_serial = self.local_serial.wrapping_add(1);
                self.local_clipboard = Some(text);
                write(
                    io,
                    &Out::ClipboardOffer {
                        serial: self.local_serial,
                    },
                    &[],
                )
                .await?;
            }
            Control::RequestClipboard => {
                if let Some((id, _)) = self.incoming_clipboard.take() {
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                }
                if let Some((serial, mime)) = &self.remote_offer {
                    let id = self.next_clipboard;
                    self.next_clipboard = self.next_clipboard.wrapping_add(1);
                    write(
                        io,
                        &Out::ClipboardRequest {
                            id,
                            serial: *serial,
                            mime,
                        },
                        &[],
                    )
                    .await?;
                    self.incoming_clipboard = Some((id, Vec::new()));
                }
            }
            Control::Resize { .. } => unreachable!(),
        }
        Ok(())
    }
    async fn incoming<W: AsyncWrite + Unpin>(
        &mut self,
        io: &mut Writer<W>,
        events: &mpsc::Sender<Vec<u8>>,
        frame: OpaqueFrame,
        caps: (u32, u32),
    ) -> Result<(), &'static str> {
        let msg = protocol::decode(frame.body(), frame.payload().len())?;
        if !self.hello_received && !matches!(msg, In::Hello { .. } | In::ServerError(_)) {
            return Err("expected server HelloAck");
        }
        if self.hello_received
            && !self.connected
            && !matches!(msg, In::Hello { .. } | In::Ping(_) | In::ServerError(_))
        {
            return Err("expected initial server Ping");
        }
        match msg {
            In::Hello {
                version,
                headless,
                source_scale_milli,
            } => {
                if self.hello_received || version != 1 {
                    return Err("incompatible or duplicate protocol greeting");
                }
                self.hello_received = true;
                self.headless = headless;
                self.source_scale_milli = source_scale_milli;
            }
            In::Ping(t) => {
                write(io, &Out::Pong(t), &[]).await?;
                if !self.connected {
                    self.activate(io, events).await?;
                }
            }
            In::Pong => {}
            In::Config(config) => {
                if config.width > caps.0 || config.height > caps.1 {
                    return Err("stream exceeds advertised decoder capabilities");
                }
                let geometry_changed = self.config.as_ref().is_none_or(|old| {
                    old.width != config.width
                        || old.height != config.height
                        || old.scale_milli != config.scale_milli
                        || old.view_width != config.view_width
                        || old.view_height != config.view_height
                });
                let decoder_changed = self
                    .config
                    .as_ref()
                    .is_none_or(|old| old.width != config.width || old.height != config.height);
                if geometry_changed {
                    self.release(io).await?;
                }
                // FPS-only updates do not restart the reference chain upstream.
                if decoder_changed {
                    self.awaiting_keyframe = true;
                }
                emit(events, config_event(&config)).await?;
                self.config = Some(config);
            }
            In::Video {
                id,
                pts_us,
                keyframe,
            } => {
                if self.config.is_none() {
                    return Err("video arrived before configuration");
                }
                if self.last_frame.is_some_and(|last| id <= last) {
                    return Err("non-increasing video frame id");
                }
                self.last_frame = Some(id);
                if self.awaiting_keyframe && !keyframe {
                    // A dropped reference chain is recovered only at an IDR.
                    write(
                        io,
                        &Out::Ack {
                            id,
                            decoded_at_ms: 0,
                        },
                        &[],
                    )
                    .await?;
                    write(io, &Out::Keyframe, &[]).await?;
                    return Ok(());
                }
                self.awaiting_keyframe = false;
                let mut event = Vec::with_capacity(18 + frame.payload().len());
                event.push(3);
                event.extend_from_slice(&id.to_le_bytes());
                event.extend_from_slice(&pts_us.to_le_bytes());
                event.push(u8::from(keyframe));
                event.extend_from_slice(frame.payload());
                emit(events, event).await?;
            }
            In::ClipboardOffer { serial, mime } => {
                if let Some((id, _)) = self.incoming_clipboard.take() {
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                }
                self.remote_offer = mime.map(|m| (serial, m));
            }
            In::ClipboardRequest { id, serial, mime } => {
                if serial == self.local_serial && mime.is_some() {
                    if let Some(text) = &self.local_clipboard {
                        write(
                            io,
                            &Out::ClipboardData {
                                id,
                                len: text.len() as u32,
                            },
                            text.as_bytes(),
                        )
                        .await?;
                    } else {
                        write(io, &Out::ClipboardAbort { id }, &[]).await?;
                    }
                } else {
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                }
            }
            In::ClipboardData { id, offset, done } => {
                let Some((expected, data)) = self.incoming_clipboard.as_mut() else {
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                    return Ok(());
                };
                if id != *expected {
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                    return Ok(());
                }
                if offset != data.len() as u64 || frame.payload().len() > TEXT_LIMIT - data.len() {
                    self.incoming_clipboard = None;
                    write(io, &Out::ClipboardAbort { id }, &[]).await?;
                    return Ok(());
                }
                data.extend_from_slice(frame.payload());
                write(
                    io,
                    &Out::ClipboardAck {
                        id,
                        received: data.len() as u64,
                    },
                    &[],
                )
                .await?;
                if done {
                    let (_, bytes) = self.incoming_clipboard.take().unwrap();
                    let text =
                        std::str::from_utf8(&bytes).map_err(|_| "remote clipboard is not UTF-8")?;
                    emit(events, text_event(4, text)).await?;
                }
            }
            In::ClipboardAbort(id) => {
                if self
                    .incoming_clipboard
                    .as_ref()
                    .is_some_and(|(expected, _)| *expected == id)
                {
                    self.incoming_clipboard = None;
                }
            }
            In::ClipboardAck => {}
            In::ServerError(code) => {
                return Err(if (1..256).contains(&code) {
                    "server/client protocol incompatible"
                } else {
                    "remote server could not start session"
                })
            }
            In::CursorShape {
                id,
                width,
                height,
                hot_x,
                hot_y,
            } => {
                if !self.headless {
                    let mut event = Vec::with_capacity(25 + frame.payload().len());
                    event.push(9);
                    event.extend_from_slice(&id.to_le_bytes());
                    event.extend_from_slice(&width.to_le_bytes());
                    event.extend_from_slice(&height.to_le_bytes());
                    event.extend_from_slice(&hot_x.to_le_bytes());
                    event.extend_from_slice(&hot_y.to_le_bytes());
                    event.extend_from_slice(&self.source_scale_milli.to_le_bytes());
                    event.extend_from_slice(frame.payload());
                    emit(events, event).await?;
                }
            }
            In::CursorPosition {
                x,
                y,
                shape_id,
                visible,
            } => {
                if !self.headless {
                    let mut event = Vec::with_capacity(26);
                    event.push(10);
                    event.extend_from_slice(&x.to_le_bytes());
                    event.extend_from_slice(&y.to_le_bytes());
                    event.extend_from_slice(&shape_id.to_le_bytes());
                    event.push(u8::from(visible));
                    event.extend_from_slice(&self.source_scale_milli.to_le_bytes());
                    emit(events, event).await?;
                }
            }
            In::Ignored => {}
        }
        Ok(())
    }
}

/// Run on any full duplex stream. Tests use tokio::io::duplex, never a host.
/// Controls and frame reads are selected independently. Backpressure is bounded;
/// stuck writes or event consumers terminate instead of building a frame backlog.
pub async fn run<S: AsyncRead + AsyncWrite + Unpin>(
    stream: S,
    width: u32,
    height: u32,
    commands: mpsc::Receiver<Control>,
    events: mpsc::Sender<Vec<u8>>,
    cancel: watch::Receiver<bool>,
) -> Result<(), &'static str> {
    run_with_handshake_timeout(
        stream,
        width,
        height,
        commands,
        events,
        cancel,
        Duration::from_secs(15),
    )
    .await
}

async fn run_with_handshake_timeout<S: AsyncRead + AsyncWrite + Unpin>(
    stream: S,
    width: u32,
    height: u32,
    mut commands: mpsc::Receiver<Control>,
    events: mpsc::Sender<Vec<u8>>,
    mut cancel: watch::Receiver<bool>,
    handshake_timeout: Duration,
) -> Result<(), &'static str> {
    let (rd, wr) = tokio::io::split(stream);
    let mut writer = Writer::new(wr);
    let mut reader = Reader::new(rd);
    let mut state = State::new(width, height);
    let started = tokio::time::Instant::now();
    let mut deadline = tokio::time::Instant::now() + handshake_timeout;
    let mut heartbeat = tokio::time::interval(Duration::from_secs(10));
    heartbeat.tick().await;
    let result = {
        let normal = async {
            write(&mut writer, &Out::Hello { width, height }, &[]).await?;
            loop {
                if !state.connected {
                    // Bounded drain retains the newest queued pre-Pong viewport.
                    // Other pre-ready application controls are deliberately discarded.
                    state.retain_pre_ready_resize(&mut commands);
                }
                tokio::select! {
                    // Fair selection: continuously ready controls cannot monopolize reads.
                    command = commands.recv() => match command {
                        Some(cmd) => state.command(&mut writer, cmd, started.elapsed().as_millis() as u64).await?,
                        None => return Ok(()),
                    },
                    _ = tokio::time::sleep_until(deadline) => return Err(if state.hello_received && !state.connected {
                        "initial server Ping timed out"
                    } else { "remote protocol timed out" }),
                    frame = reader.next() => match frame? {
                        Some(frame) => {
                            if !state.connected { state.retain_pre_ready_resize(&mut commands); }
                            state.incoming(&mut writer, &events, frame, (width, height)).await?;
                            deadline = tokio::time::Instant::now() + if state.connected { Duration::from_secs(45) } else { handshake_timeout };
                        },
                        None => return if state.connected {Ok(())} else {Err("remote closed before handshake completed")},
                    },
                    _ = heartbeat.tick() => {
                        if state.connected { write(&mut writer, &Out::Ping(started.elapsed().as_millis() as u64), &[]).await?; }
                    }
                }
            }
        };
        tokio::pin!(normal);
        // This race encloses WHOLE selected operations, including release loops,
        // writes and event emission. Drop means terminal stream.
        tokio::select! {
            biased;
            _ = cancelled(&mut cancel) => Ok(()),
            result = &mut normal => result,
        }
    };
    let _ = tokio::time::timeout(Duration::from_millis(300), async {
        if !writer.dirty && state.connected {
            state.release(&mut writer).await?;
            write(&mut writer, &Out::Bye, &[]).await?;
        }
        // In particular, NEVER append release/Bye to a partially written frame.
        writer.io.shutdown().await.map_err(|_| "shutdown failed")
    })
    .await;
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use minicbor::{Decoder, Encoder};
    use tokio::io::DuplexStream;

    pub(super) fn packet(
        kind: u32,
        fields: u64,
        body: impl FnOnce(&mut Encoder<Vec<u8>>),
        payload: &[u8],
    ) -> Vec<u8> {
        let mut e = Encoder::new(Vec::new());
        e.array(2)
            .unwrap()
            .u32(kind)
            .unwrap()
            .array(fields)
            .unwrap();
        body(&mut e);
        let data = e.into_writer();
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&(data.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&data);
        bytes.extend_from_slice(payload);
        bytes
    }
    pub(super) fn hello(version: u16) -> Vec<u8> {
        packet(
            0,
            4,
            |e| {
                e.u16(version)
                    .unwrap()
                    .array(2)
                    .unwrap()
                    .bool(true)
                    .unwrap()
                    .str("private")
                    .unwrap()
                    .array(0)
                    .unwrap()
                    .array(0)
                    .unwrap();
            },
            &[],
        )
    }
    pub(super) fn config(chroma: u32) -> Vec<u8> {
        packet(
            1,
            11,
            |e| {
                e.u32(1).unwrap().u32(chroma).unwrap().null().unwrap();
                e.u32(1280)
                    .unwrap()
                    .u32(720)
                    .unwrap()
                    .u32(1000)
                    .unwrap()
                    .bytes(&[])
                    .unwrap()
                    .null()
                    .unwrap();
                e.u32(1280).unwrap().u32(720).unwrap().u32(30).unwrap();
            },
            &[],
        )
    }
    async fn outbound(
        reader: &mut Reader<tokio::io::ReadHalf<DuplexStream>>,
        kind: u32,
    ) -> OpaqueFrame {
        let frame = tokio::time::timeout(Duration::from_secs(2), reader.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        let mut d = Decoder::new(frame.body());
        assert_eq!(d.array().unwrap(), Some(2));
        assert_eq!(d.u32().unwrap(), kind);
        frame
    }
    type Harness = (
        DuplexStream,
        mpsc::Sender<Control>,
        mpsc::Receiver<Vec<u8>>,
        watch::Sender<bool>,
        tokio::task::JoinHandle<Result<(), &'static str>>,
    );
    fn harness() -> Harness {
        let (client, server) = tokio::io::duplex(65536);
        let (tx, rx) = mpsc::channel(CONTROL_QUEUE);
        let (events, out) = mpsc::channel(EVENT_QUEUE);
        let (cancel, cancel_rx) = watch::channel(false);
        let task = tokio::spawn(run(client, 1280, 720, rx, events, cancel_rx));
        (server, tx, out, cancel, task)
    }

    #[tokio::test]
    async fn mock_handshake_video_controls_clipboard_and_cancellation() {
        let (server, commands, mut events, cancel, task) = harness();
        let (rd, mut wr) = tokio::io::split(server);
        let mut reader = Reader::new(rd);
        let sent = outbound(&mut reader, 0).await;
        assert_eq!(
            sent.body(),
            &protocol::encode(
                &Out::Hello {
                    width: 1280,
                    height: 720
                },
                &[]
            )[8..]
        );
        // Fragment server Hello into individual bytes.
        for byte in hello(1) {
            wr.write_all(&[byte]).await.unwrap();
        }
        wr.write_all(&packet(
            12,
            1,
            |e| {
                e.u64(123).unwrap();
            },
            &[],
        ))
        .await
        .unwrap();
        assert_eq!(
            outbound(&mut reader, 16).await.body(),
            &protocol::encode(&Out::Pong(123), &[])[8..]
        );
        outbound(&mut reader, 1).await;
        assert_eq!(events.recv().await.unwrap(), text_event(1, "Connected"));
        wr.write_all(&config(2)).await.unwrap();
        let event = events.recv().await.unwrap();
        assert_eq!(event.len(), 25);
        assert_eq!(event[0], 2);
        let access_unit = [0, 0, 0, 1, 0x65, 0x01];
        wr.write_all(&packet(
            2,
            6,
            |e| {
                e.u64(42)
                    .unwrap()
                    .u64(123456)
                    .unwrap()
                    .bool(true)
                    .unwrap()
                    .array(0)
                    .unwrap()
                    .u32(6)
                    .unwrap()
                    .u32(0)
                    .unwrap();
            },
            &access_unit,
        ))
        .await
        .unwrap();
        let event = events.recv().await.unwrap();
        assert_eq!(event[0], 3);
        assert_eq!(&event[18..], access_unit);
        assert_eq!(u64::from_le_bytes(event[1..9].try_into().unwrap()), 42);
        commands.send(Control::Ack(42)).await.unwrap();
        outbound(&mut reader, 6).await;
        commands
            .send(Control::Key {
                code: 30,
                pressed: true,
            })
            .await
            .unwrap();
        assert_eq!(
            outbound(&mut reader, 2).await.body(),
            &protocol::encode(
                &Out::Key {
                    code: 30,
                    pressed: true
                },
                &[]
            )[8..]
        );
        commands.send(Control::ReleaseAll).await.unwrap();
        assert_eq!(
            outbound(&mut reader, 2).await.body(),
            &protocol::encode(
                &Out::Key {
                    code: 30,
                    pressed: false
                },
                &[]
            )[8..]
        );
        commands
            .send(Control::Resize {
                width: 720,
                height: 1280,
                scale_milli: 1250,
            })
            .await
            .unwrap();
        assert_eq!(
            outbound(&mut reader, 1).await.body(),
            &protocol::encode(
                &Out::Resize {
                    width: 720,
                    height: 1280,
                    scale: 1.25
                },
                &[]
            )[8..]
        );
        commands
            .send(Control::SetClipboard("hello 🌱".to_string()))
            .await
            .unwrap();
        outbound(&mut reader, 8).await;
        wr.write_all(&packet(
            6,
            3,
            |e| {
                e.u32(7)
                    .unwrap()
                    .u32(1)
                    .unwrap()
                    .array(2)
                    .unwrap()
                    .u32(0)
                    .unwrap()
                    .array(1)
                    .unwrap()
                    .str(protocol::TEXT_MIME)
                    .unwrap();
            },
            &[],
        ))
        .await
        .unwrap();
        assert_eq!(
            outbound(&mut reader, 10).await.payload(),
            "hello 🌱".as_bytes()
        );
        wr.write_all(&packet(
            5,
            3,
            |e| {
                e.u32(2)
                    .unwrap()
                    .array(1)
                    .unwrap()
                    .str(protocol::TEXT_MIME)
                    .unwrap()
                    .array(0)
                    .unwrap();
            },
            &[],
        ))
        .await
        .unwrap();
        // Ping roundtrip proves preceding offer was processed before UI requests it.
        wr.write_all(&packet(
            12,
            1,
            |e| {
                e.u64(124).unwrap();
            },
            &[],
        ))
        .await
        .unwrap();
        outbound(&mut reader, 16).await;
        commands.send(Control::RequestClipboard).await.unwrap();
        outbound(&mut reader, 9).await;
        wr.write_all(&packet(
            7,
            4,
            |e| {
                e.u32(1)
                    .unwrap()
                    .u64(0)
                    .unwrap()
                    .u32(5)
                    .unwrap()
                    .bool(true)
                    .unwrap();
            },
            b"world",
        ))
        .await
        .unwrap();
        outbound(&mut reader, 13).await;
        assert_eq!(events.recv().await.unwrap(), text_event(4, "world"));
        cancel.send(true).unwrap();
        outbound(&mut reader, 12).await;
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), task)
                .await
                .unwrap()
                .unwrap(),
            Ok(())
        );
    }

    #[tokio::test]
    async fn mock_rejects_version_chroma_and_truncated_frame() {
        for scenario in 0..3 {
            let (server, _commands, mut events, _cancel, task) = harness();
            let (rd, mut wr) = tokio::io::split(server);
            let mut reader = Reader::new(rd);
            outbound(&mut reader, 0).await;
            if scenario == 0 {
                wr.write_all(&hello(99)).await.unwrap();
            } else {
                wr.write_all(&hello(1)).await.unwrap();
                wr.write_all(&packet(
                    12,
                    1,
                    |e| {
                        e.u64(1).unwrap();
                    },
                    &[],
                ))
                .await
                .unwrap();
                events.recv().await.unwrap();
                if scenario == 1 {
                    wr.write_all(&config(1)).await.unwrap();
                } else {
                    wr.write_all(&[1, 0, 0]).await.unwrap();
                    wr.shutdown().await.unwrap();
                }
            }
            assert!(tokio::time::timeout(Duration::from_secs(2), task)
                .await
                .unwrap()
                .unwrap()
                .is_err());
        }
    }

    #[test]
    fn jni_control_validation_preserves_full_frame_id_and_rejects_nonfinite_input() {
        assert!(Control::from_jni(3, f64::NAN, 0.0, 0, 0).is_none());
        assert!(Control::from_jni(1, 1280.5, 720.0, 1000, 0).is_none());
        assert!(Control::from_jni(1, 1280.0, 720.0, 0, 0).is_none());
        assert!(Control::from_jni(2, 0.0, 0.0, 768, 1).is_none());
        assert!(matches!(
            Control::from_jni(6, 0.0, 0.0, -1, 0x12345678),
            Some(Control::Ack(0x12345678ffffffff))
        ));
    }
}

#[cfg(test)]
mod config_tests {
    use super::*;
    #[tokio::test]
    async fn fps_only_configuration_preserves_reference_chain_and_held_keys() {
        let mut state = State::new(1280, 720);
        state.connected = true;
        state.hello_received = true;
        state.awaiting_keyframe = false;
        state.keys.insert(30);
        state.config = Some(StreamConfig {
            width: 1280,
            height: 720,
            scale_milli: 1000,
            view_width: 1280,
            view_height: 720,
            fps_cap: 60,
        });
        let mut e = minicbor::Encoder::new(Vec::new());
        e.array(2).unwrap().u32(1).unwrap().array(11).unwrap();
        e.u32(1)
            .unwrap()
            .u32(2)
            .unwrap()
            .null()
            .unwrap()
            .u32(1280)
            .unwrap()
            .u32(720)
            .unwrap()
            .u32(1000)
            .unwrap()
            .bytes(&[])
            .unwrap()
            .null()
            .unwrap()
            .u32(1280)
            .unwrap()
            .u32(720)
            .unwrap()
            .u32(30)
            .unwrap();
        let body = e.into_writer();
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&(body.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes.extend_from_slice(&body);
        let frame = FrameReader::default().feed(&bytes).unwrap().1.unwrap();
        let (events, mut output) = mpsc::channel(1);
        state
            .incoming(
                &mut Writer::new(tokio::io::sink()),
                &events,
                frame,
                (1280, 720),
            )
            .await
            .unwrap();
        assert!(!state.awaiting_keyframe);
        assert!(state.keys.contains(&30));
        assert_eq!(output.recv().await.unwrap()[0], 2);
    }
}

#[cfg(test)]
mod review_tests {
    use super::tests::{config, hello, packet};
    use super::*;
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    };
    use std::task::{Context, Poll};
    use tokio::io::{DuplexStream, ReadBuf};

    async fn poll_pending<F: Future>(mut future: Pin<&mut F>) {
        std::future::poll_fn(|cx| match future.as_mut().poll(cx) {
            Poll::Pending => Poll::Ready(()),
            Poll::Ready(_) => panic!("expected an interrupted pending operation"),
        })
        .await;
    }

    #[tokio::test]
    async fn pending_read_cancellation_at_every_fragment_boundary_never_replays_bytes() {
        let first = protocol::encode(&Out::Keyframe, &[]);
        let second = protocol::encode(&Out::ClipboardData { id: 7, len: 4 }, b"text");
        let third = protocol::encode(&Out::Bye, &[]);
        for cut in 0..second.len() {
            let (client, mut server) = tokio::io::duplex(4096);
            let mut reader = Reader::new(client);
            server.write_all(&first).await.unwrap();
            server.write_all(&second[..cut]).await.unwrap();
            assert_eq!(reader.next().await.unwrap().unwrap().body(), &first[8..]);
            // Poll into a real pending read, then DROP it as select! would.
            {
                let mut pending = Box::pin(reader.next());
                poll_pending(pending.as_mut()).await;
            }
            server.write_all(&second[cut..]).await.unwrap();
            server.write_all(&third).await.unwrap();
            server.shutdown().await.unwrap();
            let frame = reader.next().await.unwrap().unwrap();
            assert_eq!(frame.payload(), b"text", "fragment {cut}");
            assert_eq!(frame.body(), &second[8..second.len() - 4]);
            assert_eq!(reader.next().await.unwrap().unwrap().body(), &third[8..]);
            assert!(reader.next().await.unwrap().is_none());
        }
    }

    async fn next_capture<F: Future>(
        mut task: Pin<&mut F>,
        writes: &mut mpsc::UnboundedReceiver<Vec<u8>>,
    ) -> Vec<u8> {
        tokio::time::timeout(
            Duration::from_secs(1),
            std::future::poll_fn(|cx| {
                if let Poll::Ready(Some(bytes)) = writes.poll_recv(cx) {
                    return Poll::Ready(bytes);
                }
                assert!(
                    task.as_mut().poll(cx).is_pending(),
                    "session ended before expected output"
                );
                match writes.poll_recv(cx) {
                    Poll::Ready(Some(bytes)) => Poll::Ready(bytes),
                    _ => Poll::Pending,
                }
            }),
        )
        .await
        .expect("expected captured output")
    }

    struct CaptureStream {
        read: DuplexStream,
        allowance: Arc<AtomicUsize>,
        writes: mpsc::UnboundedSender<Vec<u8>>,
    }
    impl AsyncRead for CaptureStream {
        fn poll_read(
            mut self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &mut ReadBuf<'_>,
        ) -> Poll<std::io::Result<()>> {
            Pin::new(&mut self.read).poll_read(cx, buf)
        }
    }
    impl AsyncWrite for CaptureStream {
        fn poll_write(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            let mut old = self.allowance.load(Ordering::Relaxed);
            loop {
                let next = if old == usize::MAX {
                    old
                } else {
                    old.saturating_sub(buf.len())
                };
                match self.allowance.compare_exchange_weak(
                    old,
                    next,
                    Ordering::Relaxed,
                    Ordering::Relaxed,
                ) {
                    Ok(_) => break,
                    Err(current) => old = current,
                }
            }
            let count = old.min(buf.len());
            if count == 0 {
                return Poll::Pending;
            }
            self.writes.send(buf[..count].to_vec()).unwrap();
            Poll::Ready(Ok(count))
        }
        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }
    fn capture() -> (
        CaptureStream,
        DuplexStream,
        Arc<AtomicUsize>,
        mpsc::UnboundedReceiver<Vec<u8>>,
    ) {
        let (client, server) = tokio::io::duplex(65536);
        let allowance = Arc::new(AtomicUsize::new(usize::MAX));
        let (writes, rx) = mpsc::unbounded_channel();
        (
            CaptureStream {
                read: client,
                allowance: allowance.clone(),
                writes,
            },
            server,
            allowance,
            rx,
        )
    }
    fn variant(bytes: &[u8]) -> u32 {
        let mut decoder = minicbor::Decoder::new(&bytes[8..]);
        decoder.array().unwrap();
        decoder.u32().unwrap()
    }

    #[tokio::test]
    async fn queued_controls_wait_for_initial_pong_and_resize_uses_latest_viewport() {
        let (stream, mut server, _allowance, mut writes) = capture();
        let (commands, rx) = mpsc::channel(CONTROL_QUEUE);
        let (events, mut output) = mpsc::channel(EVENT_QUEUE);
        let (cancel, cancel_rx) = watch::channel(false);
        commands
            .send(Control::Resize {
                width: 720,
                height: 1280,
                scale_milli: 1250,
            })
            .await
            .unwrap();
        commands
            .send(Control::Key {
                code: 30,
                pressed: true,
            })
            .await
            .unwrap();
        commands
            .send(Control::SetClipboard("pre-ready text".into()))
            .await
            .unwrap();
        let mut task = Box::pin(run(stream, 1280, 720, rx, events, cancel_rx));
        poll_pending(task.as_mut()).await;
        assert_eq!(variant(&next_capture(task.as_mut(), &mut writes).await), 0);
        server.write_all(&hello(1)).await.unwrap();
        poll_pending(task.as_mut()).await;
        assert!(writes.try_recv().is_err());
        assert!(output.try_recv().is_err());
        commands
            .send(Control::Resize {
                width: 640,
                height: 480,
                scale_milli: 1000,
            })
            .await
            .unwrap();
        server
            .write_all(&packet(
                12,
                1,
                |e| {
                    e.u64(123).unwrap();
                },
                &[],
            ))
            .await
            .unwrap();
        poll_pending(task.as_mut()).await;
        assert_eq!(variant(&next_capture(task.as_mut(), &mut writes).await), 16);
        assert_eq!(
            next_capture(task.as_mut(), &mut writes).await,
            protocol::encode(
                &Out::Resize {
                    width: 640,
                    height: 480,
                    scale: 1.0
                },
                &[]
            )
        );
        assert_eq!(output.try_recv().unwrap(), text_event(1, "Connected"));
        assert!(writes.try_recv().is_err());
        cancel.send(true).unwrap();
        assert_eq!(task.await, Ok(()));
    }

    #[tokio::test]
    async fn delayed_initial_ping_still_precedes_all_application_traffic() {
        let (stream, mut server, _allowance, mut writes) = capture();
        let (_commands, rx) = mpsc::channel(CONTROL_QUEUE);
        let (events, mut output) = mpsc::channel(EVENT_QUEUE);
        let (cancel, cancel_rx) = watch::channel(false);
        let task = tokio::spawn(run(stream, 1280, 720, rx, events, cancel_rx));
        assert_eq!(variant(&writes.recv().await.unwrap()), 0);
        server.write_all(&hello(1)).await.unwrap();
        // In particular, the removed 250ms fallback cannot activate the session.
        assert!(
            tokio::time::timeout(Duration::from_millis(350), output.recv())
                .await
                .is_err()
        );
        assert!(writes.try_recv().is_err());
        server
            .write_all(&packet(
                12,
                1,
                |e| {
                    e.u64(5).unwrap();
                },
                &[],
            ))
            .await
            .unwrap();
        assert_eq!(variant(&writes.recv().await.unwrap()), 16);
        assert_eq!(variant(&writes.recv().await.unwrap()), 1);
        assert_eq!(output.recv().await.unwrap(), text_event(1, "Connected"));
        cancel.send(true).unwrap();
        assert_eq!(task.await.unwrap(), Ok(()));
    }

    #[tokio::test]
    async fn missing_initial_ping_times_out_without_resize_or_connected() {
        let (stream, mut server, _allowance, mut writes) = capture();
        let (_commands, rx) = mpsc::channel(CONTROL_QUEUE);
        let (events, mut output) = mpsc::channel(EVENT_QUEUE);
        let (_cancel, cancel_rx) = watch::channel(false);
        let task = tokio::spawn(run_with_handshake_timeout(
            stream,
            1280,
            720,
            rx,
            events,
            cancel_rx,
            Duration::from_millis(20),
        ));
        assert_eq!(variant(&writes.recv().await.unwrap()), 0);
        server.write_all(&hello(1)).await.unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(1), task)
                .await
                .unwrap()
                .unwrap(),
            Err("initial server Ping timed out")
        );
        assert!(writes.try_recv().is_err());
        assert!(output.try_recv().is_err());
    }

    #[tokio::test]
    async fn cancellation_interrupts_partial_normal_write_and_large_release_without_appending_frames(
    ) {
        for during_release in [false, true] {
            let (stream, mut server, allowance, mut writes) = capture();
            let (commands, rx) = mpsc::channel(CONTROL_QUEUE);
            let (events, mut output) = mpsc::channel(EVENT_QUEUE);
            let (cancel, cancel_rx) = watch::channel(false);
            let task = tokio::spawn(run(stream, 1280, 720, rx, events, cancel_rx));
            assert_eq!(variant(&writes.recv().await.unwrap()), 0);
            server.write_all(&hello(1)).await.unwrap();
            server
                .write_all(&packet(
                    12,
                    1,
                    |e| {
                        e.u64(1).unwrap();
                    },
                    &[],
                ))
                .await
                .unwrap();
            assert_eq!(variant(&writes.recv().await.unwrap()), 16);
            assert_eq!(variant(&writes.recv().await.unwrap()), 1);
            assert_eq!(output.recv().await.unwrap(), text_event(1, "Connected"));
            if during_release {
                for code in 1..=200 {
                    commands
                        .send(Control::Key {
                            code,
                            pressed: true,
                        })
                        .await
                        .unwrap();
                    assert_eq!(variant(&writes.recv().await.unwrap()), 2);
                }
            }
            allowance.store(5, Ordering::Relaxed);
            commands
                .send(if during_release {
                    Control::ReleaseAll
                } else {
                    Control::Key {
                        code: 30,
                        pressed: true,
                    }
                })
                .await
                .unwrap();
            assert_eq!(writes.recv().await.unwrap().len(), 5); // a partial frame really reached the peer
            let now = tokio::time::Instant::now();
            cancel.send(true).unwrap();
            assert_eq!(
                tokio::time::timeout(Duration::from_millis(500), task)
                    .await
                    .unwrap()
                    .unwrap(),
                Ok(())
            );
            assert!(now.elapsed() < Duration::from_millis(500));
            assert!(
                writes.try_recv().is_err(),
                "no release or Bye appended after partial frame"
            );
        }
    }

    #[tokio::test]
    async fn cancellation_interrupts_blocked_event_emission() {
        let (stream, mut server, _allowance, mut writes) = capture();
        let (_commands, rx) = mpsc::channel(CONTROL_QUEUE);
        let (events, mut output) = mpsc::channel(1);
        let (cancel, cancel_rx) = watch::channel(false);
        let mut task = Box::pin(run(stream, 1280, 720, rx, events, cancel_rx));
        poll_pending(task.as_mut()).await;
        assert_eq!(variant(&next_capture(task.as_mut(), &mut writes).await), 0);
        server.write_all(&hello(1)).await.unwrap();
        server
            .write_all(&packet(
                12,
                1,
                |e| {
                    e.u64(1).unwrap();
                },
                &[],
            ))
            .await
            .unwrap();
        poll_pending(task.as_mut()).await;
        assert_eq!(variant(&next_capture(task.as_mut(), &mut writes).await), 16);
        assert_eq!(variant(&next_capture(task.as_mut(), &mut writes).await), 1);
        // Connected fills the only event slot. Config must block on the next emit.
        server.write_all(&config(2)).await.unwrap();
        for _ in 0..4 {
            poll_pending(task.as_mut()).await;
            tokio::task::yield_now().await;
        }
        cancel.send(true).unwrap();
        assert_eq!(
            tokio::time::timeout(Duration::from_millis(500), task)
                .await
                .unwrap(),
            Ok(())
        );
        assert_eq!(variant(&writes.try_recv().unwrap()), 12); // clean boundary permits one Bye
        assert_eq!(output.try_recv().unwrap(), text_event(1, "Connected"));
        assert!(output.try_recv().is_err());
    }

    #[tokio::test]
    async fn recovery_discards_only_pre_keyframe_video_and_acks_for_flow_accounting() {
        let (stream, _server, _allowance, mut writes) = capture();
        let mut writer = Writer::new(stream);
        let mut state = State::new(1280, 720);
        state.hello_received = true;
        state.connected = true;
        state.config = Some(StreamConfig {
            width: 1280,
            height: 720,
            scale_milli: 1000,
            view_width: 1280,
            view_height: 720,
            fps_cap: 30,
        });
        let (events, mut output) = mpsc::channel(EVENT_QUEUE);
        for (id, keyframe) in [(7, false), (8, true)] {
            let bytes = packet(
                2,
                6,
                |e| {
                    e.u64(id)
                        .unwrap()
                        .u64(100)
                        .unwrap()
                        .bool(keyframe)
                        .unwrap()
                        .array(0)
                        .unwrap()
                        .u32(1)
                        .unwrap()
                        .u32(0)
                        .unwrap();
                },
                &[0x65],
            );
            let frame = FrameReader::default().feed(&bytes).unwrap().1.unwrap();
            state
                .incoming(&mut writer, &events, frame, (1280, 720))
                .await
                .unwrap();
            if !keyframe {
                assert_eq!(
                    writes.try_recv().unwrap(),
                    protocol::encode(
                        &Out::Ack {
                            id,
                            decoded_at_ms: 0
                        },
                        &[]
                    )
                );
                assert_eq!(variant(&writes.try_recv().unwrap()), 7);
                assert!(output.try_recv().is_err());
                assert!(state.awaiting_keyframe);
            } else {
                assert_eq!(output.try_recv().unwrap()[0], 3);
                assert!(writes.try_recv().is_err());
                assert!(!state.awaiting_keyframe);
            }
        }
    }

    struct SlowProgress {
        delay: Option<Pin<Box<tokio::time::Sleep>>>,
        completed: usize,
    }
    impl AsyncWrite for SlowProgress {
        fn poll_write(
            mut self: Pin<&mut Self>,
            cx: &mut Context<'_>,
            buf: &[u8],
        ) -> Poll<std::io::Result<usize>> {
            let delay = self
                .delay
                .get_or_insert_with(|| Box::pin(tokio::time::sleep(Duration::from_millis(50))));
            if delay.as_mut().poll(cx).is_pending() {
                return Poll::Pending;
            }
            self.delay = None;
            self.completed += 1;
            Poll::Ready(Ok(buf.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }
    #[tokio::test]
    async fn aggregate_release_deadline_bounds_a_slowly_progressing_peer() {
        let mut state = State::new(1280, 720);
        state.keys.extend(1..=767);
        let mut writer = Writer::new(SlowProgress {
            delay: None,
            completed: 0,
        });
        let now = tokio::time::Instant::now();
        assert_eq!(
            state.release(&mut writer).await,
            Err("held input release timed out")
        );
        assert!(now.elapsed() < Duration::from_millis(500));
        assert!(writer.io.completed < 10);
        assert!(writer.dirty);
    }
}
