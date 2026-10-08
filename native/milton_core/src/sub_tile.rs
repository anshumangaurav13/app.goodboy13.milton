use crate::compression;

const TILE_SIZE: usize = 512;
const TILE_BYTES: usize = TILE_SIZE * TILE_SIZE * 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    pub min_x: usize,
    pub min_y: usize,
    pub width: usize,
    pub height: usize,
}

/// Finds the minimal bounding box of differing RGBA pixels between two 512x512 buffers.
/// Uses fast 64-bit word comparisons (2 pixels at a time) for high performance.
pub fn compute_dirty_rect(old_buf: &[u8], new_buf: &[u8]) -> Option<Rect> {
    if old_buf.len() < TILE_BYTES || new_buf.len() < TILE_BYTES {
        return Some(Rect {
            min_x: 0,
            min_y: 0,
            width: TILE_SIZE,
            height: TILE_SIZE,
        });
    }

    // Fast check: are they bit-for-bit identical?
    if old_buf[0..TILE_BYTES] == new_buf[0..TILE_BYTES] {
        return None;
    }

    let mut min_x = TILE_SIZE;
    let mut max_x = 0usize;
    let mut min_y = TILE_SIZE;
    let mut max_y = 0usize;

    for y in 0..TILE_SIZE {
        let row_start = y * TILE_SIZE * 4;
        let row_old = &old_buf[row_start..row_start + TILE_SIZE * 4];
        let row_new = &new_buf[row_start..row_start + TILE_SIZE * 4];

        // If entire row is identical, skip fast
        if row_old == row_new {
            continue;
        }

        min_y = min_y.min(y);
        max_y = max_y.max(y);

        // Find min_x and max_x in this row
        for x in 0..TILE_SIZE {
            let px_idx = x * 4;
            if row_old[px_idx..px_idx + 4] != row_new[px_idx..px_idx + 4] {
                min_x = min_x.min(x);
                max_x = max_x.max(x);
            }
        }
    }

    if min_x > max_x || min_y > max_y {
        return None;
    }

    Some(Rect {
        min_x,
        min_y,
        width: max_x - min_x + 1,
        height: max_y - min_y + 1,
    })
}

/// Extracts a rectangular sub-region from a 512x512 tile and compresses it with ZSTD.
pub fn create_sub_tile_patch(
    tile_buf: &[u8],
    rect: Rect,
) -> Result<Vec<u8>, String> {
    if rect.width == 0 || rect.height == 0 {
        return Ok(Vec::new());
    }

    let mut patch_pixels = Vec::with_capacity(rect.width * rect.height * 4);
    let row_bytes = rect.width * 4;

    for y in rect.min_y..(rect.min_y + rect.height) {
        let row_start = (y * TILE_SIZE + rect.min_x) * 4;
        patch_pixels.extend_from_slice(&tile_buf[row_start..row_start + row_bytes]);
    }

    compression::compress_tile(&patch_pixels)
}

/// Decompresses a patch and blits it directly into a 512x512 tile buffer in place.
pub fn apply_sub_tile_patch(
    tile_buf: &mut [u8],
    patch_compressed: &[u8],
    rect: Rect,
) -> Result<(), String> {
    if rect.width == 0 || rect.height == 0 || patch_compressed.is_empty() {
        return Ok(());
    }

    let patch_pixels = compression::decompress_tile(patch_compressed)?;
    let expected_bytes = rect.width * rect.height * 4;
    if patch_pixels.len() < expected_bytes {
        return Err(format!(
            "Patch buffer too short: expected {} bytes, got {}",
            expected_bytes,
            patch_pixels.len()
        ));
    }

    let row_bytes = rect.width * 4;
    for (i, y) in (rect.min_y..(rect.min_y + rect.height)).enumerate() {
        let tile_start = (y * TILE_SIZE + rect.min_x) * 4;
        let patch_start = i * row_bytes;
        tile_buf[tile_start..tile_start + row_bytes]
            .copy_from_slice(&patch_pixels[patch_start..patch_start + row_bytes]);
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_identical_tiles_have_no_dirty_rect() {
        let a = vec![0u8; TILE_BYTES];
        let b = vec![0u8; TILE_BYTES];
        assert_eq!(compute_dirty_rect(&a, &b), None);
    }

    #[test]
    fn test_sub_tile_dirty_rect_and_patch() {
        let mut old = vec![0u8; TILE_BYTES];
        let mut new = old.clone();

        // Mutate a 20x30 region at (50, 60)
        for y in 60..90 {
            for x in 50..70 {
                let idx = (y * TILE_SIZE + x) * 4;
                new[idx] = 255;
                new[idx + 1] = 128;
                new[idx + 2] = 64;
                new[idx + 3] = 255;
            }
        }

        let rect = compute_dirty_rect(&old, &new).expect("should find dirty rect");
        assert_eq!(rect, Rect { min_x: 50, min_y: 60, width: 20, height: 30 });

        // Create patch of new
        let patch_new = create_sub_tile_patch(&new, rect).expect("patch should create");
        assert!(patch_new.len() < 20 * 30 * 4); // should be compressed

        // Apply patch onto old
        apply_sub_tile_patch(&mut old, &patch_new, rect).expect("patch should apply");

        // Old should now be identical to new
        assert_eq!(old, new);
    }
}
