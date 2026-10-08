use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use crate::compression;

const TILE_BYTE_SIZE: usize = 512 * 512 * 4;

fn get_swap_path(swap_dir: &str, layer_id: i64, tx: i32, ty: i32) -> PathBuf {
    let dir = Path::new(swap_dir);
    if layer_id > 0 {
        dir.join(format!("tile_{layer_id}_{tx}_{ty}.bin"))
    } else {
        dir.join(format!("tile_{tx}_{ty}.bin"))
    }
}

/// Writes tile RGBA bytes to swap disk storage using fast direct I/O.
pub fn swap_write(
    swap_dir: &str,
    layer_id: i64,
    tx: i32,
    ty: i32,
    raw_bytes: &[u8],
) -> Result<(), String> {
    let path = get_swap_path(swap_dir, layer_id, tx, ty);
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    // Write raw bytes directly to disk with buffered I/O
    let file = File::create(&path).map_err(|e| format!("Cannot create swap file: {e}"))?;
    let mut writer = BufWriter::with_capacity(64 * 1024, file);
    writer.write_all(raw_bytes).map_err(|e| format!("Cannot write swap file: {e}"))?;
    writer.flush().map_err(|e| format!("Cannot flush swap file: {e}"))?;

    Ok(())
}

/// Reads tile RGBA bytes from swap disk storage directly into target slice.
/// Handles both raw 1MB streams and compressed tiles transparently.
pub fn swap_read(
    swap_dir: &str,
    layer_id: i64,
    tx: i32,
    ty: i32,
    out_buf: &mut [u8],
) -> Result<bool, String> {
    let path = get_swap_path(swap_dir, layer_id, tx, ty);
    if !path.is_file() {
        return Ok(false);
    }

    let file = File::open(&path).map_err(|e| format!("Cannot open swap file: {e}"))?;
    let file_len = file.metadata().map_err(|e| e.to_string())?.len() as usize;

    if file_len == TILE_BYTE_SIZE && out_buf.len() >= TILE_BYTE_SIZE {
        // Fast path: raw uncompressed 1MB stream
        let mut reader = BufReader::with_capacity(64 * 1024, file);
        reader.read_exact(&mut out_buf[0..TILE_BYTE_SIZE])
            .map_err(|e| format!("Failed to read raw swap bytes: {e}"))?;
        Ok(true)
    } else {
        // Compressed stream: read all and decompress
        let mut reader = BufReader::new(file);
        let mut compressed = Vec::with_capacity(file_len);
        reader.read_to_end(&mut compressed)
            .map_err(|e| format!("Failed to read compressed swap file: {e}"))?;

        let decompressed = compression::decompress_tile(&compressed)?;
        if decompressed.len() != TILE_BYTE_SIZE || out_buf.len() < TILE_BYTE_SIZE {
            return Err(format!(
                "Invalid tile size in swap file: expected {TILE_BYTE_SIZE} bytes, got {}",
                decompressed.len()
            ));
        }

        out_buf[0..TILE_BYTE_SIZE].copy_from_slice(&decompressed[0..TILE_BYTE_SIZE]);
        Ok(true)
    }
}

/// Deletes swap file for given tile coordinate.
pub fn swap_delete(swap_dir: &str, layer_id: i64, tx: i32, ty: i32) -> Result<bool, String> {
    let path = get_swap_path(swap_dir, layer_id, tx, ty);
    if path.exists() {
        fs::remove_file(path).map(|_| true).map_err(|e| e.to_string())
    } else {
        Ok(false)
    }
}

/// Cleans up all swap files in directory.
pub fn swap_clear_all(swap_dir: &str) -> Result<(), String> {
    let dir = Path::new(swap_dir);
    if dir.is_dir() {
        if let Ok(entries) = fs::read_dir(dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_file() && p.extension().and_then(|s| s.to_str()) == Some("bin") {
                    let _ = fs::remove_file(p);
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_swap_write_and_read_raw() {
        let temp_dir = std::env::temp_dir().join("milton_test_swap_raw");
        let dir_str = temp_dir.to_str().unwrap();
        let _ = fs::remove_dir_all(&temp_dir);

        let dummy = vec![170u8; TILE_BYTE_SIZE];
        swap_write(dir_str, 0, 1, 2, &dummy).expect("swap_write should succeed");

        let mut out = vec![0u8; TILE_BYTE_SIZE];
        let ok = swap_read(dir_str, 0, 1, 2, &mut out).expect("swap_read should succeed");
        assert!(ok);
        assert_eq!(out, dummy);

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_swap_read_compressed() {
        let temp_dir = std::env::temp_dir().join("milton_test_swap_comp");
        let dir_str = temp_dir.to_str().unwrap();
        let _ = fs::remove_dir_all(&temp_dir);
        let _ = fs::create_dir_all(&temp_dir);

        let dummy = vec![42u8; TILE_BYTE_SIZE];
        let comp = compression::compress_tile(&dummy).expect("compression should succeed");
        let path = get_swap_path(dir_str, 0, 3, 4);
        fs::write(&path, &comp).expect("write should succeed");

        let mut out = vec![0u8; TILE_BYTE_SIZE];
        let ok = swap_read(dir_str, 0, 3, 4, &mut out).expect("swap_read should succeed");
        assert!(ok);
        assert_eq!(out, dummy);

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn test_swap_rejects_truncated_sub_tile_patch() {
        let temp_dir = std::env::temp_dir().join("milton_test_swap_reject");
        let dir_str = temp_dir.to_str().unwrap();
        let _ = fs::remove_dir_all(&temp_dir);
        let _ = fs::create_dir_all(&temp_dir);

        // A sub-tile patch of size 100x100
        let patch = vec![123u8; 100 * 100 * 4];
        let comp = compression::compress_tile(&patch).expect("compression should succeed");
        let path = get_swap_path(dir_str, 0, 0, 0);
        fs::write(&path, &comp).expect("write should succeed");

        let mut out = vec![0u8; TILE_BYTE_SIZE];
        let result = swap_read(dir_str, 0, 0, 0, &mut out);
        assert!(result.is_err(), "Must reject compressed tile smaller than 512x512");

        let _ = fs::remove_dir_all(&temp_dir);
    }
}
