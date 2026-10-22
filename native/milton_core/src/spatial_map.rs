// native/milton_core/src/spatial_map.rs
//! High-performance sparse 2D spatial tile map and VRAM LRU eviction manager.
//!
//! Tracks tile placement on the infinite plane, provides fast bounding box queries,
//! and computes LRU eviction sets with hysteresis and anti-thrashing grace periods.

use std::collections::{HashMap, HashSet};

pub const TILE_SIZE: f32 = 512.0;
pub const TILE_EVICTION_SLACK_DIVISOR: usize = 8;
pub const TILE_EVICTION_GRACE_PERIOD_NS: u64 = 3_000_000_000; // 3 seconds
pub const DEFAULT_MAX_EVICTIONS_PER_FRAME: usize = 4;

/// 64-bit packed tile coordinate: (tx as u32 << 32) | (ty as u32)
#[inline]
pub fn pack_coord(tx: i32, ty: i32) -> u64 {
    ((tx as u32 as u64) << 32) | (ty as u32 as u64)
}

#[inline]
pub fn unpack_coord(packed: u64) -> (i32, i32) {
    let tx = (packed >> 32) as u32 as i32;
    let ty = packed as u32 as i32;
    (tx, ty)
}

/// Metadata for a single canvas tile in the spatial grid.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TileMeta {
    pub tx: i32,
    pub ty: i32,
    pub slice_index: i32,
    pub has_content: bool,
    pub is_resident: bool,
    pub is_on_disk: bool,
    pub is_dirty: bool,
    pub last_access_time_ns: u64,
}

impl TileMeta {
    pub fn new(tx: i32, ty: i32) -> Self {
        Self {
            tx,
            ty,
            slice_index: -1,
            has_content: false,
            is_resident: false,
            is_on_disk: false,
            is_dirty: false,
            last_access_time_ns: 0,
        }
    }
}

/// Sparse 2D Tile Map on the infinite canvas.
pub struct SpatialTileMap {
    pub tiles: HashMap<u64, TileMeta>,
    pub max_resident_tiles: usize,
}

impl SpatialTileMap {
    pub fn new(max_resident_tiles: usize) -> Self {
        Self {
            tiles: HashMap::new(),
            max_resident_tiles: max_resident_tiles.max(1),
        }
    }

    #[inline]
    pub fn count(&self) -> usize {
        self.tiles.len()
    }

    #[inline]
    pub fn count_resident(&self) -> usize {
        self.tiles.values().filter(|t| t.is_resident).count()
    }

    #[inline]
    pub fn count_on_disk(&self) -> usize {
        self.tiles.values().filter(|t| t.is_on_disk).count()
    }

    pub fn set_tile(
        &mut self,
        tx: i32,
        ty: i32,
        slice_index: i32,
        has_content: bool,
        is_resident: bool,
        is_on_disk: bool,
        is_dirty: bool,
        timestamp_ns: u64,
    ) {
        let key = pack_coord(tx, ty);
        let entry = self.tiles.entry(key).or_insert_with(|| TileMeta::new(tx, ty));
        entry.slice_index = slice_index;
        entry.has_content = has_content;
        entry.is_resident = is_resident;
        entry.is_on_disk = is_on_disk;
        entry.is_dirty = is_dirty;
        if timestamp_ns > 0 {
            entry.last_access_time_ns = timestamp_ns;
        }
    }

    pub fn update_tile_state(
        &mut self,
        tx: i32,
        ty: i32,
        slice_index: i32,
        has_content: bool,
        is_resident: bool,
    ) {
        let key = pack_coord(tx, ty);
        if let Some(meta) = self.tiles.get_mut(&key) {
            meta.slice_index = slice_index;
            meta.has_content = has_content;
            meta.is_resident = is_resident;
        } else {
            let mut meta = TileMeta::new(tx, ty);
            meta.slice_index = slice_index;
            meta.has_content = has_content;
            meta.is_resident = is_resident;
            self.tiles.insert(key, meta);
        }
    }

    #[inline]
    pub fn get_tile(&self, tx: i32, ty: i32) -> Option<&TileMeta> {
        self.tiles.get(&pack_coord(tx, ty))
    }

    #[inline]
    pub fn get_tile_mut(&mut self, tx: i32, ty: i32) -> Option<&mut TileMeta> {
        self.tiles.get_mut(&pack_coord(tx, ty))
    }

    #[inline]
    pub fn remove_tile(&mut self, tx: i32, ty: i32) -> bool {
        self.tiles.remove(&pack_coord(tx, ty)).is_some()
    }

    #[inline]
    pub fn touch_tile(&mut self, tx: i32, ty: i32, timestamp_ns: u64) {
        if let Some(tile) = self.tiles.get_mut(&pack_coord(tx, ty)) {
            tile.last_access_time_ns = timestamp_ns;
        }
    }

    pub fn batch_touch(&mut self, packed_keys: &[u64], timestamp_ns: u64) {
        for &key in packed_keys {
            if let Some(tile) = self.tiles.get_mut(&key) {
                tile.last_access_time_ns = timestamp_ns;
            }
        }
    }

    /// Queries all existing tiles overlapping `[min_tx..=max_tx, min_ty..=max_ty]`.
    /// Updates their access timestamp and writes their packed keys to `out_keys`.
    pub fn query_visible(
        &mut self,
        min_tx: i32,
        min_ty: i32,
        max_tx: i32,
        max_ty: i32,
        timestamp_ns: u64,
        out_keys: &mut Vec<u64>,
    ) {
        for (&key, meta) in self.tiles.iter_mut() {
            if meta.tx >= min_tx && meta.tx <= max_tx && meta.ty >= min_ty && meta.ty <= max_ty {
                if timestamp_ns > 0 {
                    meta.last_access_time_ns = timestamp_ns;
                }
                out_keys.push(key);
            }
        }
    }

    /// Queries visible tiles directly into a flat slice of i64 packed keys.
    /// Returns the number of visible tiles found (up to `out_slice.len()`).
    pub fn query_visible_into_slice(
        &mut self,
        min_tx: i32,
        min_ty: i32,
        max_tx: i32,
        max_ty: i32,
        timestamp_ns: u64,
        out_slice: &mut [i64],
    ) -> usize {
        let max_len = out_slice.len();
        let mut count = 0;

        for (&key, meta) in self.tiles.iter_mut() {
            if meta.tx >= min_tx && meta.tx <= max_tx && meta.ty >= min_ty && meta.ty <= max_ty {
                if timestamp_ns > 0 {
                    meta.last_access_time_ns = timestamp_ns;
                }
                if count < max_len {
                    out_slice[count] = key as i64;
                    count += 1;
                }
            }
        }
        count
    }

    /// Computes world-space axis-aligned bounding box `(min_x, min_y, max_x, max_y)`
    /// encompassing all tiles that currently contain painted pixel content.
    pub fn compute_bounds(&self) -> Option<(f32, f32, f32, f32)> {
        let mut min_x = f32::MAX;
        let mut min_y = f32::MAX;
        let mut max_x = f32::MIN;
        let mut max_y = f32::MIN;
        let mut has_any = false;

        for meta in self.tiles.values() {
            if meta.has_content {
                let left = meta.tx as f32 * TILE_SIZE;
                let top = meta.ty as f32 * TILE_SIZE;
                let right = left + TILE_SIZE;
                let bottom = top + TILE_SIZE;

                if left < min_x { min_x = left; }
                if top < min_y { min_y = top; }
                if right > max_x { max_x = right; }
                if bottom > max_y { max_y = bottom; }
                has_any = true;
            }
        }

        if has_any {
            Some((min_x, min_y, max_x, max_y))
        } else {
            None
        }
    }

    /// Identifies the oldest non-visible resident tiles eligible for disk swap eviction.
    ///
    /// Respects the memory budget hysteresis slack and anti-thrashing grace period.
    /// Writes candidate packed keys into `out_evictions`.
    pub fn find_eviction_candidates(
        &self,
        visible_keys: &[u64],
        now_ns: u64,
        max_evictions: usize,
        out_evictions: &mut Vec<u64>,
    ) -> usize {
        let slack = self.max_resident_tiles / TILE_EVICTION_SLACK_DIVISOR;
        let resident_count = self.count_resident();
        let threshold = self.max_resident_tiles + slack;

        if resident_count <= threshold {
            return 0;
        }

        let excess = resident_count - threshold;
        let limit = excess.min(max_evictions);
        let enforce_grace_period = resident_count <= self.max_resident_tiles * 2;

        let visible_set: HashSet<u64> = visible_keys.iter().copied().collect();

        let mut candidates: Vec<(u64, u64)> = self.tiles.iter()
            .filter(|(&key, meta)| {
                meta.is_resident
                    && !visible_set.contains(&key)
                    && (!enforce_grace_period || (now_ns.saturating_sub(meta.last_access_time_ns)) > TILE_EVICTION_GRACE_PERIOD_NS)
            })
            .map(|(&key, meta)| (key, meta.last_access_time_ns))
            .collect();

        if candidates.is_empty() {
            return 0;
        }

        // Sort ascending by last_access_time_ns (least recently used first)
        candidates.sort_unstable_by_key(|&(_, time)| time);

        let count_to_add = limit.min(candidates.len());
        for &(key, _) in candidates.iter().take(count_to_add) {
            out_evictions.push(key);
        }

        count_to_add
    }

    /// Identifies the oldest non-visible resident tiles eligible for disk swap eviction,
    /// given the axis-aligned bounding tile coordinates `[min_tx..=max_tx, min_ty..=max_ty]`.
    pub fn find_eviction_candidates_for_bounds(
        &self,
        min_tx: i32,
        min_ty: i32,
        max_tx: i32,
        max_ty: i32,
        now_ns: u64,
        max_evictions: usize,
        out_evictions: &mut Vec<u64>,
    ) -> usize {
        let slack = self.max_resident_tiles / TILE_EVICTION_SLACK_DIVISOR;
        let resident_count = self.count_resident();
        let threshold = self.max_resident_tiles + slack;

        if resident_count <= threshold {
            return 0;
        }

        let excess = resident_count - threshold;
        let limit = excess.min(max_evictions);
        let enforce_grace_period = resident_count <= self.max_resident_tiles * 2;

        let mut candidates: Vec<(u64, u64)> = self.tiles.iter()
            .filter(|(_, meta)| {
                if !meta.is_resident {
                    return false;
                }
                // Check if inside visible bounds
                if meta.tx >= min_tx && meta.tx <= max_tx && meta.ty >= min_ty && meta.ty <= max_ty {
                    return false;
                }
                if enforce_grace_period && (now_ns.saturating_sub(meta.last_access_time_ns)) <= TILE_EVICTION_GRACE_PERIOD_NS {
                    return false;
                }
                true
            })
            .map(|(&key, meta)| (key, meta.last_access_time_ns))
            .collect();

        if candidates.is_empty() {
            return 0;
        }

        candidates.sort_unstable_by_key(|&(_, time)| time);

        let count_to_add = limit.min(candidates.len());
        for &(key, _) in candidates.iter().take(count_to_add) {
            out_evictions.push(key);
        }
        count_to_add
    }

    pub fn clear(&mut self) {
        self.tiles.clear();
    }
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_coord_pack_unpack() {
        let coords = [(0, 0), (1, -1), (-500, 1000), (i32::MAX, i32::MIN)];
        for (tx, ty) in coords {
            let packed = pack_coord(tx, ty);
            let (un_tx, un_ty) = unpack_coord(packed);
            assert_eq!(tx, un_tx);
            assert_eq!(ty, un_ty);
        }
    }

    #[test]
    fn test_tile_lifecycle_and_spatial_query() {
        let mut map = SpatialTileMap::new(64);
        map.set_tile(0, 0, 1, true, true, false, false, 100);
        map.set_tile(1, 0, 2, true, true, false, false, 200);
        map.set_tile(5, 5, 3, true, true, false, false, 300);

        assert_eq!(map.count(), 3);
        assert_eq!(map.count_resident(), 3);

        let mut visible = Vec::new();
        map.query_visible(0, 0, 1, 1, 500, &mut visible);
        assert_eq!(visible.len(), 2);
        assert!(visible.contains(&pack_coord(0, 0)));
        assert!(visible.contains(&pack_coord(1, 0)));
        assert!(!visible.contains(&pack_coord(5, 5)));

        // Verify timestamp updated
        assert_eq!(map.get_tile(0, 0).unwrap().last_access_time_ns, 500);
        assert_eq!(map.get_tile(5, 5).unwrap().last_access_time_ns, 300);
    }

    #[test]
    fn test_bounds_computation() {
        let mut map = SpatialTileMap::new(64);
        assert_eq!(map.compute_bounds(), None);

        map.set_tile(-1, -1, 0, true, true, false, false, 0);
        map.set_tile(2, 3, 1, true, true, false, false, 0);

        let bounds = map.compute_bounds().unwrap();
        // -1 * 512 = -512
        // (2 + 1) * 512 = 1536
        // (3 + 1) * 512 = 2048
        assert_eq!(bounds, (-512.0, -512.0, 1536.0, 2048.0));
    }

    #[test]
    fn test_lru_eviction_candidates() {
        // Budget = 4. Slack = 4 / 8 = 0. Threshold = 4.
        let mut map = SpatialTileMap::new(4);

        // Add 6 resident tiles with increasing access times
        for i in 0..6 {
            map.set_tile(i, 0, i, true, true, false, false, (i as u64 + 1) * 1000);
        }
        assert_eq!(map.count_resident(), 6);

        // Excess = 6 - 4 = 2.
        // Let tile 0, 1, 2 be outside grace period (now = 10_000_000_000).
        let now = 10_000_000_000u64;
        let visible_keys = [pack_coord(0, 0)]; // Tile 0 is visible, so it cannot be evicted

        let mut evictions = Vec::new();
        let count = map.find_eviction_candidates(&visible_keys, now, 4, &mut evictions);

        assert_eq!(count, 2);
        assert_eq!(evictions.len(), 2);
        // The oldest non-visible tiles are tile 1 and tile 2
        assert_eq!(evictions[0], pack_coord(1, 0));
        assert_eq!(evictions[1], pack_coord(2, 0));
    }

    #[test]
    fn test_lru_eviction_candidates_for_bounds() {
        let mut map = SpatialTileMap::new(2);
        let now = 10_000_000_000u64;

        map.set_tile(0, 0, 0, true, true, false, false, now - 5_000_000_000);
        map.set_tile(1, 0, 1, true, true, false, false, now - 4_000_000_000);
        map.set_tile(2, 0, 2, true, true, false, false, now - 3_000_000_000);

        let mut evictions = Vec::new();
        // Visible bounds is tile (0, 0)
        let count = map.find_eviction_candidates_for_bounds(0, 0, 0, 0, now, 4, &mut evictions);

        assert_eq!(count, 1);
        assert_eq!(evictions[0], pack_coord(1, 0));
    }
}
