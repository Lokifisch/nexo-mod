//! The wire format for async job results (`NexoNative.jobTake`).
//!
//! Why a hand-rolled binary format instead of JSON: the two producers are a
//! chat-history search and a chunk-history query. Chunk snapshots are opaque
//! blobs, which JSON can only carry base64'd — 33% bigger and an extra decode
//! per record — and a chat search over a long-running history can return
//! thousands of rows onto the render thread's doorstep. The format below costs
//! Java one `ByteBuffer` and no allocation per field it skips.
//!
//! Everything is **big-endian**, which is not an arbitrary choice: `ByteBuffer`
//! and `DataInputStream` are big-endian by default, so the Java reader is
//! `buf.getLong()` with no byte shuffling anywhere.
//!
//! ```text
//! magic   4 bytes  "NXJ1"
//! kind    u8       KIND_* below
//! count   u32      number of records
//! records count × (kind-specific fields, in the order documented per kind)
//! ```
//!
//! Field encodings:
//! * `i32` / `i64` — fixed width, big-endian, two's complement.
//! * `str` — `u16` byte length, then that many UTF-8 bytes. **Not**
//!   `DataInput.readUTF`: that is modified UTF-8, which mangles NUL and
//!   astral-plane characters, and chat messages are full of emoji.
//! * `bytes` — `u32` byte length, then raw bytes.
//!
//! The version lives in the magic rather than a separate field: a reader that
//! doesn't recognise "NXJ1" rejects the buffer outright instead of
//! misinterpreting a newer layout as the old one.

pub const MAGIC: [u8; 4] = *b"NXJ1";

/// No records. Returned by a job that legitimately found nothing, so callers
/// can tell "finished, empty" from "failed" without consulting the status.
pub const KIND_EMPTY: u8 = 0;
/// Records of `i64 tsMillis, str server, str sender, str message`.
pub const KIND_CHAT_SEARCH: u8 = 1;
/// Records of `str dimension, i32 chunkX, i32 chunkZ, i64 tsMillis, bytes payload`.
pub const KIND_CHUNK_QUERY: u8 = 2;

const HEADER_LEN: usize = 9;
const COUNT_OFFSET: usize = 5;

pub struct PayloadWriter {
    buf: Vec<u8>,
    count: u32,
}

impl PayloadWriter {
    pub fn new(kind: u8) -> Self {
        let mut buf = Vec::with_capacity(HEADER_LEN + 256);
        buf.extend_from_slice(&MAGIC);
        buf.push(kind);
        // Patched in `finish` — the count isn't known until the producer stops
        // pushing, and buffering records separately just to count them first
        // would double the peak memory for the exact case (a big search) this
        // format exists to make cheap.
        buf.extend_from_slice(&0u32.to_be_bytes());
        Self { buf, count: 0 }
    }

    /// Call once per record, before its fields. Only bumps the counter; the
    /// records themselves are self-delimiting given the kind's field list.
    pub fn begin_record(&mut self) {
        self.count += 1;
    }

    pub fn put_i32(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    pub fn put_i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    /// Truncates at a char boundary rather than a byte offset for anything past
    /// 65535 bytes. Chat lines can't reach that, but a server name coming off
    /// the wire is attacker-controlled, and splitting a multi-byte sequence
    /// would hand Java bytes that decode to a replacement character — or, in a
    /// stricter decoder, throw.
    pub fn put_str(&mut self, s: &str) {
        let bytes = s.as_bytes();
        let len = if bytes.len() <= u16::MAX as usize {
            bytes.len()
        } else {
            let mut cut = u16::MAX as usize;
            while cut > 0 && !s.is_char_boundary(cut) {
                cut -= 1;
            }
            cut
        };
        self.buf.extend_from_slice(&(len as u16).to_be_bytes());
        self.buf.extend_from_slice(&bytes[..len]);
    }

    pub fn put_bytes(&mut self, b: &[u8]) {
        // Clamped rather than asserted: a 4 GiB chunk snapshot is a bug
        // upstream, but truncating one record beats panicking out of a worker
        // thread and losing the whole query.
        let len = b.len().min(u32::MAX as usize);
        self.buf.extend_from_slice(&(len as u32).to_be_bytes());
        self.buf.extend_from_slice(&b[..len]);
    }

    pub fn finish(mut self) -> Vec<u8> {
        self.buf[COUNT_OFFSET..COUNT_OFFSET + 4].copy_from_slice(&self.count.to_be_bytes());
        self.buf
    }
}

/// An empty result of the given kind. Cheaper to say than to build via the
/// writer, and used often enough (every query that matches nothing) to earn a
/// name.
pub fn empty(kind: u8) -> Vec<u8> {
    PayloadWriter::new(kind).finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_carries_kind_and_count() {
        let mut w = PayloadWriter::new(KIND_CHAT_SEARCH);
        for i in 0..3i64 {
            w.begin_record();
            w.put_i64(i);
            w.put_str("s");
        }
        let out = w.finish();
        assert_eq!(&out[0..4], &MAGIC);
        assert_eq!(out[4], KIND_CHAT_SEARCH);
        assert_eq!(u32::from_be_bytes(out[5..9].try_into().unwrap()), 3);
    }

    #[test]
    fn strings_are_length_prefixed_utf8() {
        let mut w = PayloadWriter::new(KIND_EMPTY);
        w.begin_record();
        w.put_str("héllo");
        let out = w.finish();
        let len = u16::from_be_bytes(out[9..11].try_into().unwrap()) as usize;
        assert_eq!(len, "héllo".len(), "length is in bytes, not chars");
        assert_eq!(&out[11..11 + len], "héllo".as_bytes());
    }

    #[test]
    fn oversized_string_truncates_on_a_char_boundary() {
        // 'ä' is two bytes, so a naive byte cut at 65535 would land mid-glyph.
        let s: String = std::iter::repeat_n('ä', 40_000).collect();
        let mut w = PayloadWriter::new(KIND_EMPTY);
        w.begin_record();
        w.put_str(&s);
        let out = w.finish();
        let len = u16::from_be_bytes(out[9..11].try_into().unwrap()) as usize;
        assert!(len <= u16::MAX as usize);
        assert!(
            std::str::from_utf8(&out[11..11 + len]).is_ok(),
            "truncation must leave valid UTF-8"
        );
    }

    #[test]
    fn empty_payload_is_just_a_header() {
        assert_eq!(empty(KIND_CHUNK_QUERY).len(), HEADER_LEN);
    }
}
