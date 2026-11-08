//! Narrow protocol-v1 schema adaptation from Gliff revision
//! 32bb0622cf2feef69515bf8f75a836f657f06083, crates/gliff-proto/src/{msg,clipboard}.rs.
//! Copyright (c) 2026 Kevin McConnell. MIT, see ../UPSTREAM-LICENSE.
//! No upstream engine is vendored. Parsing is bounded before semantic decoding.

use minicbor::{Decoder, Encoder};

pub const TEXT_MIME: &str = "text/plain;charset=utf-8";
pub const TEXT_LIMIT: usize = 256 * 1024;
pub const MAX_PIXELS: u64 = 3840 * 2160;
pub const MAX_SIDE: u32 = 4096;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamConfig {
    pub width: u32,
    pub height: u32,
    pub scale_milli: u32,
    pub view_width: u32,
    pub view_height: u32,
    pub fps_cap: u32,
}

#[derive(Debug, PartialEq)]
pub enum ServerMessage {
    Hello {
        version: u16,
        headless: bool,
        source_scale_milli: u32,
    },
    Config(StreamConfig),
    Video {
        id: u64,
        pts_us: u64,
        keyframe: bool,
    },
    CursorShape {
        id: u32,
        width: u32,
        height: u32,
        hot_x: i32,
        hot_y: i32,
    },
    CursorPosition {
        x: f64,
        y: f64,
        shape_id: u32,
        visible: bool,
    },
    ClipboardOffer {
        serial: u32,
        mime: Option<String>,
    },
    ClipboardRequest {
        id: u32,
        serial: u32,
        mime: Option<String>,
    },
    ClipboardData {
        id: u32,
        offset: u64,
        done: bool,
    },
    ClipboardAck,
    ClipboardAbort(u32),
    Ping(u64),
    Pong,
    ServerError(u16),
    Ignored,
}

#[derive(Debug)]
pub enum ClientMessage<'a> {
    Hello {
        width: u32,
        height: u32,
    },
    Resize {
        width: u32,
        height: u32,
        scale: f32,
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
    Ack {
        id: u64,
        decoded_at_ms: u64,
    },
    Keyframe,
    ClipboardOffer {
        serial: u32,
    },
    ClipboardRequest {
        id: u32,
        serial: u32,
        mime: &'a str,
    },
    ClipboardData {
        id: u32,
        len: u32,
    },
    ClipboardAck {
        id: u32,
        received: u64,
    },
    ClipboardAbort {
        id: u32,
    },
    Ping(u64),
    Pong(u64),
    Bye,
}

pub fn encode(msg: &ClientMessage<'_>, payload: &[u8]) -> Vec<u8> {
    let mut e = Encoder::new(Vec::new());
    use ClientMessage::*;
    let (variant, count) = match msg {
        Hello { .. } => (0, 3),
        Resize { .. } => (1, 3),
        Key { .. } => (2, 2),
        Motion { .. } => (3, 2),
        Button { .. } => (4, 2),
        Axis { .. } => (5, 4),
        Ack { .. } => (6, 2),
        Keyframe => (7, 0),
        ClipboardOffer { .. } => (8, 3),
        ClipboardRequest { .. } => (9, 3),
        ClipboardData { .. } => (10, 4),
        Ping(_) => (11, 1),
        Bye => (12, 0),
        ClipboardAck { .. } => (13, 2),
        ClipboardAbort { .. } => (14, 1),
        Pong(_) => (16, 1),
    };
    // Vec's encoder has an infallible writer. All call sites provide bounded inputs.
    e.array(2)
        .unwrap()
        .u32(variant)
        .unwrap()
        .array(count)
        .unwrap();
    match msg {
        Hello { width, height } => {
            e.u16(1).unwrap().str("").unwrap().array(5).unwrap();
            e.array(1).unwrap().u32(1).unwrap(); // Codec::H264
            e.u32(*width).unwrap().u32(*height).unwrap();
            e.array(1).unwrap().u32(2).unwrap(); // ChromaMode::Single420 only
            e.array(0).unwrap(); // no negotiated optional features
        }
        Resize {
            width,
            height,
            scale,
        } => {
            e.u32(*width)
                .unwrap()
                .u32(*height)
                .unwrap()
                .f32(*scale)
                .unwrap();
        }
        Key { code, pressed } => {
            e.u32(*code).unwrap().bool(*pressed).unwrap();
        }
        Motion { x, y } => {
            e.f64(*x).unwrap().f64(*y).unwrap();
        }
        Button { button, pressed } => {
            e.u32(*button).unwrap().bool(*pressed).unwrap();
        }
        Axis {
            horizontal,
            value,
            stop,
        } => {
            e.u32(u32::from(*horizontal))
                .unwrap()
                .f64(*value)
                .unwrap()
                .null()
                .unwrap()
                .bool(*stop)
                .unwrap();
        }
        Ack { id, decoded_at_ms } => {
            e.u64(*id).unwrap().u64(*decoded_at_ms).unwrap();
        }
        ClipboardOffer { serial } => {
            e.u32(*serial)
                .unwrap()
                .array(1)
                .unwrap()
                .str(TEXT_MIME)
                .unwrap()
                .array(0)
                .unwrap();
        }
        ClipboardRequest { id, serial, mime } => {
            e.u32(*id)
                .unwrap()
                .u32(*serial)
                .unwrap()
                .array(2)
                .unwrap()
                .u32(0)
                .unwrap()
                .array(1)
                .unwrap()
                .str(mime)
                .unwrap();
        }
        ClipboardData { id, len } => {
            e.u32(*id)
                .unwrap()
                .u64(0)
                .unwrap()
                .u32(*len)
                .unwrap()
                .bool(true)
                .unwrap();
        }
        ClipboardAck { id, received } => {
            e.u32(*id).unwrap().u64(*received).unwrap();
        }
        ClipboardAbort { id } => {
            e.u32(*id).unwrap();
        }
        Ping(t) | Pong(t) => {
            e.u64(*t).unwrap();
        }
        Keyframe | Bye => {}
    }
    let body = e.into_writer();
    let mut frame = Vec::with_capacity(8 + body.len() + payload.len());
    frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
    frame.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    frame.extend_from_slice(&body);
    frame.extend_from_slice(payload);
    frame
}

pub fn valid_dimensions(width: u32, height: u32) -> bool {
    (2..=MAX_SIDE).contains(&width)
        && (2..=MAX_SIDE).contains(&height)
        && width.is_multiple_of(2)
        && height.is_multiple_of(2)
        && u64::from(width) * u64::from(height) <= MAX_PIXELS
}

/// Definite-length CBOR only, matching the pinned upstream encoder. Unknown
/// fields/messages are skipped after validating depth, node count and UTF-8.
/// No attacker-controlled array length reaches a Vec allocation.
pub fn validate_cbor(body: &[u8]) -> Result<(), &'static str> {
    fn value(
        bytes: &[u8],
        pos: &mut usize,
        depth: usize,
        nodes: &mut usize,
    ) -> Result<(), &'static str> {
        if depth > 16 || *nodes == 0 {
            return Err("CBOR complexity limit");
        }
        *nodes -= 1;
        let tag = *bytes.get(*pos).ok_or("truncated CBOR")?;
        *pos += 1;
        let major = tag >> 5;
        let ai = tag & 31;
        let n = match ai {
            0..=23 => u64::from(ai),
            24..=27 => {
                let count = 1usize << (ai - 24);
                let part = bytes
                    .get(*pos..pos.checked_add(count).ok_or("CBOR overflow")?)
                    .ok_or("truncated CBOR")?;
                *pos += count;
                part.iter().fold(0u64, |acc, b| (acc << 8) | u64::from(*b))
            }
            _ => return Err("indefinite or reserved CBOR unsupported"),
        };
        match major {
            0 | 1 => Ok(()),
            2 | 3 => {
                let len = usize::try_from(n).map_err(|_| "CBOR overflow")?;
                let end = pos.checked_add(len).ok_or("CBOR overflow")?;
                let part = bytes.get(*pos..end).ok_or("truncated CBOR")?;
                if major == 3 {
                    std::str::from_utf8(part).map_err(|_| "invalid CBOR UTF-8")?;
                }
                *pos = end;
                Ok(())
            }
            4 | 5 => {
                let count = n
                    .checked_mul(if major == 5 { 2 } else { 1 })
                    .ok_or("CBOR overflow")?;
                if count > *nodes as u64 {
                    return Err("CBOR complexity limit");
                }
                for _ in 0..count {
                    value(bytes, pos, depth + 1, nodes)?;
                }
                Ok(())
            }
            7 if matches!(ai, 20..=23 | 25..=27) => Ok(()),
            _ => Err("unsupported CBOR type"),
        }
    }
    if body.is_empty() || body.len() > crate::MAX_BODY as usize {
        return Err("invalid body length");
    }
    let mut pos = 0;
    value(body, &mut pos, 0, &mut 65536)?;
    if pos != body.len() {
        return Err("trailing CBOR bytes");
    }
    Ok(())
}

fn array(d: &mut Decoder<'_>, minimum: u64) -> Result<u64, minicbor::decode::Error> {
    let len = d
        .array()?
        .ok_or_else(|| minicbor::decode::Error::message("indefinite array"))?;
    if len < minimum {
        return Err(minicbor::decode::Error::message("missing field"));
    }
    Ok(len)
}

fn text_mime(s: &str) -> bool {
    matches!(
        s,
        TEXT_MIME | "text/plain" | "UTF8_STRING" | "STRING" | "TEXT"
    )
}

pub fn decode(body: &[u8], payload_len: usize) -> Result<ServerMessage, &'static str> {
    validate_cbor(body)?;
    let parse = || -> Result<(ServerMessage, usize), minicbor::decode::Error> {
        let mut d = Decoder::new(body);
        if d.array()? != Some(2) {
            return Err(minicbor::decode::Error::message("invalid message envelope"));
        }
        let variant = d.u32()?;
        let fields = array(&mut d, 0)?;
        let need = |n| {
            if fields < n {
                Err(minicbor::decode::Error::message("missing field"))
            } else {
                Ok(())
            }
        };
        let mut expected_payload = 0;
        let msg = match variant {
            0 => {
                need(4)?;
                let version = d.u16()?;
                let session_fields = array(&mut d, 2)?;
                let headless = d.bool()?;
                let output_name = d.str()?;
                if output_name.len() > 256 {
                    return Err(minicbor::decode::Error::message("output name too long"));
                }
                for _ in 2..session_fields {
                    d.skip()?;
                }
                let outputs = array(&mut d, 0)?;
                if outputs > 128 {
                    return Err(minicbor::decode::Error::message("too many outputs"));
                }
                let mut source_scale_milli = None;
                for _ in 0..outputs {
                    let count = array(&mut d, 4)?;
                    let name = d.str()?;
                    if name.len() > 256 {
                        return Err(minicbor::decode::Error::message("output name too long"));
                    }
                    d.u32()?;
                    d.u32()?;
                    let scale = d.u32()?;
                    if name == output_name && (1..=16000).contains(&scale) {
                        source_scale_milli = Some(scale);
                    }
                    for _ in 4..count {
                        d.skip()?;
                    }
                }
                let source_scale_milli = source_scale_milli
                    .ok_or_else(|| minicbor::decode::Error::message("missing output scale"))?;
                ServerMessage::Hello {
                    version,
                    headless,
                    source_scale_milli,
                }
            }
            1 => {
                need(11)?;
                if d.u32()? != 1 || d.u32()? != 2 {
                    return Err(minicbor::decode::Error::message("unsupported codec/chroma"));
                }
                d.skip()?; // optional informational pipeline
                let width = d.u32()?;
                let height = d.u32()?;
                let scale_milli = d.u32()?;
                if !d.bytes()?.is_empty() {
                    return Err(minicbor::decode::Error::message("extradata unsupported"));
                }
                if d.datatype()? == minicbor::data::Type::Null {
                    d.null()?;
                } else if !d.bytes()?.is_empty() {
                    return Err(minicbor::decode::Error::message(
                        "aux extradata unsupported",
                    ));
                }
                let view_width = d.u32()?;
                let view_height = d.u32()?;
                let fps_cap = d.u32()?;
                if !valid_dimensions(width, height)
                    || !valid_dimensions(view_width, view_height)
                    || !(1..=16000).contains(&scale_milli)
                    || !(1..=240).contains(&fps_cap)
                {
                    return Err(minicbor::decode::Error::message(
                        "invalid stream dimensions",
                    ));
                }
                ServerMessage::Config(StreamConfig {
                    width,
                    height,
                    scale_milli,
                    view_width,
                    view_height,
                    fps_cap,
                })
            }
            2 => {
                need(6)?;
                let id = d.u64()?;
                let pts_us = d.u64()?;
                let keyframe = d.bool()?;
                let damage = array(&mut d, 0)?;
                if damage > 4096 {
                    return Err(minicbor::decode::Error::message("too much damage"));
                }
                for _ in 0..damage {
                    d.skip()?;
                }
                let len = d.u32()?;
                if len == 0
                    || len as usize > crate::wire::DEFAULT_FRAME_BUDGET
                    || d.u32()? != 0
                    || pts_us > i64::MAX as u64
                {
                    return Err(minicbor::decode::Error::message("invalid video payload"));
                }
                expected_payload = len as usize;
                ServerMessage::Video {
                    id,
                    pts_us,
                    keyframe,
                }
            }
            3 => {
                need(6)?;
                let id = d.u32()?;
                let width = d.u32()?;
                let height = d.u32()?;
                let hot_x = d.i32()?;
                let hot_y = d.i32()?;
                let len = d.u32()?;
                if width == 0
                    || height == 0
                    || width > 1024
                    || height > 1024
                    || hot_x < 0
                    || hot_y < 0
                    || hot_x as u32 >= width
                    || hot_y as u32 >= height
                    || u64::from(width) * u64::from(height) * 4 != u64::from(len)
                {
                    return Err(minicbor::decode::Error::message("invalid cursor"));
                }
                expected_payload = len as usize;
                ServerMessage::CursorShape {
                    id,
                    width,
                    height,
                    hot_x,
                    hot_y,
                }
            }
            5 => {
                need(3)?;
                let serial = d.u32()?;
                let count = array(&mut d, 0)?;
                if count > 128 {
                    return Err(minicbor::decode::Error::message("too many MIME types"));
                }
                let mut mime = None;
                for _ in 0..count {
                    let s = d.str()?;
                    if s.len() > 256 {
                        return Err(minicbor::decode::Error::message("MIME too long"));
                    }
                    if mime.is_none() && text_mime(s) {
                        mime = Some(s.to_string());
                    }
                }
                d.skip()?; // files never read or materialized
                ServerMessage::ClipboardOffer { serial, mime }
            }
            6 => {
                need(3)?;
                let id = d.u32()?;
                let serial = d.u32()?;
                if d.array()? != Some(2) {
                    return Err(minicbor::decode::Error::message("bad clipboard item"));
                }
                let item = d.u32()?;
                array(&mut d, 1)?;
                let mime = if item == 0 {
                    let s = d.str()?;
                    if text_mime(s) {
                        Some(s.to_string())
                    } else {
                        None
                    }
                } else {
                    None
                };
                ServerMessage::ClipboardRequest { id, serial, mime }
            }
            7 => {
                need(4)?;
                let id = d.u32()?;
                let offset = d.u64()?;
                let len = d.u32()?;
                let done = d.bool()?;
                if len as usize > TEXT_LIMIT {
                    return Err(minicbor::decode::Error::message(
                        "clipboard chunk too large",
                    ));
                }
                expected_payload = len as usize;
                ServerMessage::ClipboardData { id, offset, done }
            }
            8 => {
                need(2)?;
                ServerMessage::Pong
            }
            9 => {
                need(2)?;
                ServerMessage::ServerError(d.u16()?)
            }
            10 => {
                need(2)?;
                ServerMessage::ClipboardAck
            }
            11 => {
                need(1)?;
                ServerMessage::ClipboardAbort(d.u32()?)
            }
            12 => {
                need(1)?;
                ServerMessage::Ping(d.u64()?)
            }
            4 => {
                need(4)?;
                let x = d.f64()?;
                let y = d.f64()?;
                let shape_id = d.u32()?;
                let visible = d.bool()?;
                // Capture coordinates may legitimately lie outside the output.
                if !x.is_finite()
                    || !y.is_finite()
                    || x < i32::MIN as f64
                    || x > i32::MAX as f64
                    || y < i32::MIN as f64
                    || y > i32::MAX as f64
                {
                    return Err(minicbor::decode::Error::message("invalid cursor position"));
                }
                ServerMessage::CursorPosition {
                    x,
                    y,
                    shape_id,
                    visible,
                }
            }
            _ => return Ok((ServerMessage::Ignored, payload_len)), // framed unknown message
        };
        Ok((msg, expected_payload))
    };
    let (msg, expected) = parse().map_err(|_| "invalid or unsupported server message")?;
    if expected != payload_len {
        return Err("message/payload length mismatch");
    }
    Ok(msg)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn unhex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn upstream_golden_encodings_and_single420_hello() {
        assert_eq!(
            &encode(
                &ClientMessage::Key {
                    code: 30,
                    pressed: true
                },
                &[]
            )[8..],
            unhex("820282181ef5")
        );
        assert_eq!(&encode(&ClientMessage::Keyframe, &[])[8..], unhex("820780"));
        assert_eq!(
            &encode(
                &ClientMessage::Ack {
                    id: 42,
                    decoded_at_ms: 1000
                },
                &[]
            )[8..],
            unhex("820682182a1903e8")
        );
        assert_eq!(
            &encode(
                &ClientMessage::Hello {
                    width: 1920,
                    height: 1080
                },
                &[]
            )[8..],
            unhex("8200830160858101190780190438810280")
        );
        assert_eq!(
            decode(&unhex("8200840182f5616f8184616f1907801904381903e880"), 0),
            Ok(ServerMessage::Hello {
                version: 1,
                headless: true,
                source_scale_milli: 1000
            })
        );
        assert_eq!(
            decode(&unhex("82098301616d01"), 0),
            Ok(ServerMessage::ServerError(1))
        );
        // Upstream Dual420 golden frame is intentionally not supported by Android.
        assert!(decode(&unhex("8202860102f5818400000a0a18641832"), 150).is_err());
    }

    #[test]
    fn hostile_cbor_and_lengths_are_rejected_without_collection_allocation() {
        assert!(validate_cbor(&unhex("9bffffffffffffffff")).is_err());
        assert!(validate_cbor(&[vec![0x81; 18], vec![0]].concat()).is_err());
        assert!(validate_cbor(&[0x9f, 0xff]).is_err());
        assert!(validate_cbor(&[0x61, 0xff]).is_err());
        assert!(validate_cbor(&[0, 0]).is_err());
        let video = unhex("8202860102f5800300");
        assert!(matches!(decode(&video, 3), Ok(ServerMessage::Video { .. })));
        assert!(decode(&video, 2).is_err());
        assert!(decode(&unhex("820080"), 0).is_err());
    }
}
