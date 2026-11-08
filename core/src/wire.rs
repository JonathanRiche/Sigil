//! Incremental wire envelope reader. CBOR bytes are opaque and untrusted.
//!
//! `feed` consumes at most one frame, returning the consumed input count so the
//! caller can process concatenated frames without an internal output queue.
//! Header validation precedes allocation. Internal storage is one 8-byte header
//! and one allocation requested at the validated body+payload size, bounded by
//! both the supplied budget and upstream limits (1 MiB body + 128 MiB payload).
//! Allocator overhead, input slices and frames retained by callers are separate.
//! Use bounded network reads and process/drop each returned frame promptly.
//! Any error poisons the reader until `reset`, intended only for a NEW transport.

use crate::{FrameHeader, MAX_PAYLOAD};

pub const DEFAULT_FRAME_BUDGET: usize = 8 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum ReadError {
    InvalidHeader(&'static str),
    FrameTooLarge,
    AllocationFailed,
    Truncated,
    Failed,
}

/// A complete envelope, not a decoded or semantically validated message.
#[derive(Debug, PartialEq, Eq)]
pub struct OpaqueFrame {
    bytes: Vec<u8>,
    body_len: usize,
}

impl OpaqueFrame {
    pub fn body(&self) -> &[u8] {
        &self.bytes[..self.body_len]
    }

    pub fn payload(&self) -> &[u8] {
        &self.bytes[self.body_len..]
    }
}

#[derive(Debug)]
pub struct FrameReader {
    budget: usize,
    header: [u8; 8],
    header_used: usize,
    body_len: usize,
    total: usize,
    bytes: Vec<u8>,
    failed: bool,
}

impl Default for FrameReader {
    fn default() -> Self {
        Self::new(DEFAULT_FRAME_BUDGET)
    }
}

impl FrameReader {
    /// Budget includes body and payload, excluding the fixed 8-byte header.
    /// A smaller budget may reject otherwise valid upstream keyframes.
    pub fn new(max_frame_bytes: usize) -> Self {
        Self {
            budget: max_frame_bytes,
            header: [0; 8],
            header_used: 0,
            body_len: 0,
            total: 0,
            bytes: Vec::new(),
            failed: false,
        }
    }

    /// Consume up to one frame. Loop over the remaining slice after Some(frame).
    /// An error makes the current stream unusable; do not retry its remaining bytes.
    pub fn feed(&mut self, input: &[u8]) -> Result<(usize, Option<OpaqueFrame>), ReadError> {
        if self.failed {
            return Err(ReadError::Failed);
        }
        match self.feed_inner(input) {
            Ok(result) => Ok(result),
            Err(error) => {
                self.poison();
                Err(error)
            }
        }
    }

    fn feed_inner(&mut self, input: &[u8]) -> Result<(usize, Option<OpaqueFrame>), ReadError> {
        let mut consumed = 0;
        if self.header_used < 8 {
            let count = (8 - self.header_used).min(input.len());
            self.header[self.header_used..self.header_used + count]
                .copy_from_slice(&input[..count]);
            self.header_used += count;
            consumed += count;
            if self.header_used < 8 {
                return Ok((consumed, None));
            }
            let header =
                FrameHeader::parse(self.header, MAX_PAYLOAD).map_err(ReadError::InvalidHeader)?;
            self.body_len =
                usize::try_from(header.body_len).map_err(|_| ReadError::FrameTooLarge)?;
            let payload =
                usize::try_from(header.payload_len).map_err(|_| ReadError::FrameTooLarge)?;
            self.total = self
                .body_len
                .checked_add(payload)
                .ok_or(ReadError::FrameTooLarge)?;
            if self.total > self.budget {
                return Err(ReadError::FrameTooLarge);
            }
            // No capacity growth occurs while accepting subsequent fragments.
            self.bytes
                .try_reserve_exact(self.total)
                .map_err(|_| ReadError::AllocationFailed)?;
        }
        let count = (self.total - self.bytes.len()).min(input.len() - consumed);
        self.bytes
            .extend_from_slice(&input[consumed..consumed + count]);
        consumed += count;
        if self.bytes.len() != self.total {
            return Ok((consumed, None));
        }
        let frame = OpaqueFrame {
            bytes: std::mem::take(&mut self.bytes),
            body_len: self.body_len,
        };
        self.clear();
        Ok((consumed, Some(frame)))
    }

    /// Call at EOF. A partial header, body or payload is a terminal error.
    /// Clean EOF at a frame boundary is accepted. Start a new transport with reset.
    pub fn finish(&mut self) -> Result<(), ReadError> {
        if self.failed {
            return Err(ReadError::Failed);
        }
        if self.header_used != 0 {
            self.poison();
            return Err(ReadError::Truncated);
        }
        Ok(())
    }

    /// Drop all buffered storage and failure state, preserving the memory budget.
    /// This is not a resynchronization strategy for a corrupt live byte stream.
    pub fn reset(&mut self) {
        self.clear();
        self.failed = false;
    }

    fn clear(&mut self) {
        self.header = [0; 8];
        self.header_used = 0;
        self.body_len = 0;
        self.total = 0;
        self.bytes = Vec::new();
    }

    fn poison(&mut self) {
        self.clear();
        self.failed = true;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::MAX_BODY;

    fn envelope(body: &[u8], payload: &[u8]) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&(body.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(body);
        bytes.extend_from_slice(payload);
        bytes
    }

    #[test]
    fn every_split_and_single_byte_fragments_preserve_opaque_bytes() {
        // Deliberately invalid CBOR: envelope parsing must not claim to decode it.
        let bytes = envelope(&[0xff, 0x00, 0xff], &[1, 2, 3, 4]);
        for split in 0..=bytes.len() {
            let mut reader = FrameReader::new(7);
            let (used, first) = reader.feed(&bytes[..split]).unwrap();
            assert_eq!(used, split);
            let (used, second) = reader.feed(&bytes[split..]).unwrap();
            assert_eq!(used, bytes.len() - split);
            let frame = first.or(second).unwrap();
            assert_eq!(frame.body(), &[0xff, 0x00, 0xff]);
            assert_eq!(frame.payload(), &[1, 2, 3, 4]);
            assert_eq!(reader.finish(), Ok(()));
        }
        let mut reader = FrameReader::new(7);
        for (index, byte) in bytes.iter().enumerate() {
            let (used, frame) = reader.feed(std::slice::from_ref(byte)).unwrap();
            assert_eq!(used, 1);
            assert_eq!(frame.is_some(), index == bytes.len() - 1);
        }
        assert_eq!(reader.finish(), Ok(()));
    }

    #[test]
    fn concatenated_frames_stop_at_each_boundary_without_queuing() {
        let first = envelope(&[0x80], &[]);
        let second = envelope(&[0x81, 0x01], &[9, 8]);
        let bytes = [first.clone(), second.clone(), first.clone()].concat();
        let mut reader = FrameReader::new(4);
        let mut remaining = bytes.as_slice();
        for expected in [first.len(), second.len(), first.len()] {
            let (used, frame) = reader.feed(remaining).unwrap();
            assert_eq!(used, expected);
            assert!(frame.is_some());
            assert_eq!(reader.bytes.capacity(), 0);
            remaining = &remaining[used..];
        }
        assert!(remaining.is_empty());
        assert_eq!(reader.feed(&[]), Ok((0, None)));
        assert_eq!(reader.finish(), Ok(()));
    }

    #[test]
    fn oversized_headers_fail_before_allocation_and_require_reset() {
        for (body, payload, budget) in [
            (0, 0, usize::MAX),
            (MAX_BODY + 1, 0, usize::MAX),
            (1, MAX_PAYLOAD + 1, usize::MAX),
            (1, u32::MAX, usize::MAX),
            (5, 4, 8), // Combined size, not just the payload, exceeds budget.
            (1, 0, 0),
        ] {
            let bytes = [body.to_le_bytes(), payload.to_le_bytes()].concat();
            let mut reader = FrameReader::new(budget);
            assert_eq!(reader.feed(&bytes[..7]), Ok((7, None)));
            assert_eq!(reader.bytes.capacity(), 0);
            assert!(reader.feed(&bytes[7..]).is_err());
            assert_eq!(reader.bytes.capacity(), 0);
            assert_eq!(reader.feed(&[]), Err(ReadError::Failed));
            assert_eq!(reader.finish(), Err(ReadError::Failed));
            reader.reset();
            assert_eq!(reader.budget, budget);
            assert_eq!(reader.finish(), Ok(()));
        }
    }

    #[test]
    fn truncation_at_every_partial_boundary_discards_state_and_reset_recovers() {
        let bytes = envelope(&[0x82, 1, 2], &[7, 8, 9]);
        for end in 1..bytes.len() {
            let mut reader = FrameReader::new(6);
            assert_eq!(reader.feed(&bytes[..end]), Ok((end, None)));
            assert_eq!(reader.finish(), Err(ReadError::Truncated));
            assert_eq!(reader.bytes.capacity(), 0);
            assert_eq!(reader.feed(&bytes), Err(ReadError::Failed));
            reader.reset();
            let (used, frame) = reader.feed(&bytes).unwrap();
            assert_eq!(used, bytes.len());
            assert_eq!(frame.unwrap().payload(), &[7, 8, 9]);
            assert_eq!(reader.finish(), Ok(()));
        }
        // Reset also discards a partial frame before an EOF/error has occurred.
        let mut reader = FrameReader::new(6);
        reader.feed(&bytes[..10]).unwrap();
        reader.reset();
        assert_eq!(reader.bytes.capacity(), 0);
        assert!(reader.feed(&bytes).unwrap().1.is_some());
    }
}
