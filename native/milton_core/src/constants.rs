//! Single Source of Truth for core dimensions, buffer sizes, and limits across milton_core.

pub const TILE_SIZE: usize = 512;
pub const BYTES_PER_PIXEL: usize = 4;
pub const TILE_BYTES: usize = TILE_SIZE * TILE_SIZE * BYTES_PER_PIXEL; // 1,048,576 bytes (1 MB)
pub const TILE_MASK_BYTES: usize = TILE_SIZE * TILE_SIZE; // 262,144 bytes
