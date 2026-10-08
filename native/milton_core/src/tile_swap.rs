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
        if decompressed.is_empty() {
            return Ok(false);
        }

        let copy_len = decompressed.len().min(out_buf.len());
        out_buf[0..copy_len].copy_from_slice(&decompressed[0..copy_len]);
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
