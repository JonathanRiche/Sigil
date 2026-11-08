//! Bounded Gliff protocol session, pinned SSH transport and direct JNI bridge.
//! Video decoding and Android UI live outside this crate.

mod bridge;
mod desktop;
pub mod protocol;
pub mod session;
pub mod transport;
pub mod wire;

pub const UPSTREAM_REVISION: &str = "32bb0622cf2feef69515bf8f75a836f657f06083";
pub const PROTOCOL_VERSION: u16 = 1;
pub const SERVER_COMMAND: &str = "gliff-server --stdio --output auto --low-bandwidth";
pub const HEADLESS_SERVER_COMMAND: &str = "gliff-server --stdio --headless --low-bandwidth";
pub const MAX_BODY: u32 = 1 << 20;
pub const MAX_PAYLOAD: u32 = 128 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub struct FrameHeader {
    pub body_len: u32,
    pub payload_len: u32,
}

impl FrameHeader {
    /// Validate before allocation. Payload limit is a local policy capped by upstream.
    /// Message-specific payload sums still need validation by a future CBOR parser.
    pub fn parse(bytes: [u8; 8], local_payload_limit: u32) -> Result<Self, &'static str> {
        let body_len = u32::from_le_bytes(bytes[..4].try_into().unwrap());
        let payload_len = u32::from_le_bytes(bytes[4..].try_into().unwrap());
        if body_len == 0 || body_len > MAX_BODY {
            return Err("invalid CBOR body length");
        }
        if payload_len > MAX_PAYLOAD.min(local_payload_limit) {
            return Err("payload exceeds memory policy");
        }
        Ok(Self {
            body_len,
            payload_len,
        })
    }
}

/// Conservative proposed viewport budget, not a decoder capability query.
/// Fits inside 1920 per side and 1080*1920 pixels; rounds down to even sides.
/// Tiny/hidden views are rejected, rather than inventing a remote aspect ratio.
pub fn viewport(width: u32, height: u32) -> Option<(u32, u32)> {
    if width < 2 || height < 2 {
        return None;
    }
    let (w, h) = (f64::from(width), f64::from(height));
    let fit = (1920.0 / w)
        .min(1920.0 / h)
        .min((1080.0 * 1920.0 / (w * h)).sqrt())
        .min(1.0);
    let result = ((w * fit) as u32 & !1, (h * fit) as u32 & !1);
    (result.0 >= 2 && result.1 >= 2).then_some(result)
}

/// Minimal JNI ABI: no JNI calls, objects, allocation or retained JVM pointers.
/// Returns width in high 32 bits, height in low 32 bits; zero means invalid.
/// Kotlin declaration: NativeCore.viewport(Int, Int): Long on an object instance.
#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_viewport(
    _env: *mut std::ffi::c_void,
    _object: *mut std::ffi::c_void,
    width: i32,
    height: i32,
) -> i64 {
    if width < 2 || height < 2 {
        return 0;
    }
    viewport(width as u32, height as u32)
        .map(|(w, h)| ((u64::from(w) << 32) | u64::from(h)) as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(body: u32, payload: u32) -> [u8; 8] {
        let mut bytes = [0; 8];
        bytes[..4].copy_from_slice(&body.to_le_bytes());
        bytes[4..].copy_from_slice(&payload.to_le_bytes());
        bytes
    }

    #[test]
    fn framing_is_little_endian_and_bounded_before_allocation() {
        assert_eq!(
            FrameHeader::parse(header(256, 65536), 65536),
            Ok(FrameHeader {
                body_len: 256,
                payload_len: 65536
            })
        );
        for (body, payload) in [(0, 0), (MAX_BODY + 1, 0), (1, MAX_PAYLOAD + 1), (1, 65537)] {
            assert!(FrameHeader::parse(header(body, payload), 65536).is_err());
        }
        assert!(FrameHeader::parse(header(MAX_BODY, MAX_PAYLOAD), u32::MAX).is_ok());
    }

    #[test]
    fn fold_sizes_stay_even_bounded_and_keep_aspect() {
        for (w, h) in [
            (1080, 2520),
            (2208, 1840),
            (1840, 2208),
            (1919, 1079),
            (u32::MAX, u32::MAX),
        ] {
            let (x, y) = viewport(w, h).unwrap();
            assert!(x <= 1920 && y <= 1920);
            assert!(u64::from(x) * u64::from(y) <= 1080 * 1920);
            assert_eq!((x % 2, y % 2), (0, 0));
            assert!((f64::from(x) / f64::from(y) - f64::from(w) / f64::from(h)).abs() < 0.005);
        }
        assert_eq!(viewport(0, 1080), None);
        assert_eq!(viewport(1, 1080), None);
        assert_eq!(viewport(u32::MAX, 2), None);
    }

    #[test]
    fn jni_scalar_encoding_matches_kotlin_contract() {
        let value = Java_dev_omarchy_gliff_NativeCore_viewport(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            1080,
            1920,
        );
        assert_eq!((value >> 32, value & 0xffff_ffff), (1080, 1920));
        assert_eq!(
            Java_dev_omarchy_gliff_NativeCore_viewport(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                -1,
                1920
            ),
            0
        );
    }
}
