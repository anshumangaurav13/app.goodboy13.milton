use std::io::Read;
use flate2::read::{DeflateDecoder, ZlibDecoder};
use rayon::prelude::*;

const ZSTD_MAGIC: [u8; 4] = [0x28, 0xb5, 0x2f, 0xfd];

/// Compresses raw RGBA tile bytes using fast ZSTD (level 3).
pub fn compress_tile(raw_bytes: &[u8]) -> Result<Vec<u8>, String> {
    zstd::encode_all(raw_bytes, 3).map_err(|e| format!("Zstd compression failed: {e}"))
}

/// Decompresses tile bytes with automatic format detection:
/// 1. ZSTD (magic bytes 0x28, 0xb5, 0x2f, 0xfd)
/// 2. Zlib / Deflate (legacy Java Deflater streams)
pub fn decompress_tile(compressed_bytes: &[u8]) -> Result<Vec<u8>, String> {
    if compressed_bytes.is_empty() {
        return Ok(Vec::new());
    }

    // 1. Check for ZSTD magic prefix
    if compressed_bytes.len() >= 4 && compressed_bytes[0..4] == ZSTD_MAGIC {
        return zstd::decode_all(compressed_bytes)
            .map_err(|e| format!("Zstd decompression failed: {e}"));
    }

    // 2. Try Zlib stream (Deflater with default wrapper)
    let mut zlib_decoder = ZlibDecoder::new(compressed_bytes);
    let mut decompressed = Vec::with_capacity(512 * 512 * 4);
    if zlib_decoder.read_to_end(&mut decompressed).is_ok() && !decompressed.is_empty() {
        return Ok(decompressed);
    }

    // 3. Fallback: Raw Deflate stream (nowrap mode)
    decompressed.clear();
    let mut raw_decoder = DeflateDecoder::new(compressed_bytes);
    if raw_decoder.read_to_end(&mut decompressed).is_ok() && !decompressed.is_empty() {
        return Ok(decompressed);
    }

    // 4. As a last resort, try zstd decoder anyway (in case magic was omitted or custom frame)
    zstd::decode_all(compressed_bytes).map_err(|e| format!("Decompression failed across all codecs: {e}"))
}

/// Compresses multiple raw tiles in parallel using Rayon thread pool.
pub fn compress_batch(tiles: &[&[u8]]) -> Vec<Result<Vec<u8>, String>> {
    tiles.par_iter().map(|raw| compress_tile(raw)).collect()
}

/// Decompresses multiple tiles in parallel using Rayon thread pool.
pub fn decompress_batch(tiles: &[&[u8]]) -> Vec<Result<Vec<u8>, String>> {
    tiles.par_iter().map(|comp| decompress_tile(comp)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zstd_roundtrip() {
        let dummy = vec![128u8; 512 * 512 * 4];
        let compressed = compress_tile(&dummy).expect("compression should succeed");
        assert!(compressed.len() < dummy.len());
        assert_eq!(&compressed[0..4], &ZSTD_MAGIC);

        let decompressed = decompress_tile(&compressed).expect("decompression should succeed");
        assert_eq!(decompressed, dummy);
    }

    #[test]
    fn test_legacy_zlib_decompression() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write;

        let dummy = vec![64u8; 512 * 512 * 4];
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(&dummy).unwrap();
        let zlib_compressed = encoder.finish().unwrap();

        let decompressed = decompress_tile(&zlib_compressed).expect("should decompress legacy zlib");
        assert_eq!(decompressed, dummy);
    }
}
