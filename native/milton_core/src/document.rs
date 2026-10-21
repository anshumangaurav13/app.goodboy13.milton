use std::collections::HashMap;

pub const TILE_SIZE: f32 = 512.0;
pub const OVERVIEW_TILE_SIZE: f32 = 1024.0;
pub const FLOATS_PER_INSTANCE: usize = 5; // (x, y, size, slice_index, opacity)

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

#[derive(Clone, Debug, PartialEq)]
pub struct TileDescriptor {
    pub tx: i32,
    pub ty: i32,
    pub slice_index: i32,
    pub has_content: bool,
}

#[derive(Clone, Debug)]
pub struct MiltonLayer {
    pub id: i64,
    pub name: String,
    pub opacity: f32,
    pub visible: bool,
    pub tiles: HashMap<u64, TileDescriptor>,
    pub overview_tiles: HashMap<u64, TileDescriptor>,
}

impl MiltonLayer {
    pub fn new(id: i64, name: String, opacity: f32, visible: bool) -> Self {
        Self {
            id,
            name,
            opacity: opacity.clamp(0.0, 1.0),
            visible,
            tiles: HashMap::new(),
            overview_tiles: HashMap::new(),
        }
    }

    pub fn set_tile(&mut self, tx: i32, ty: i32, slice_index: i32, has_content: bool) {
        let key = pack_coord(tx, ty);
        if has_content || slice_index >= 0 {
            self.tiles.insert(key, TileDescriptor { tx, ty, slice_index, has_content });
        } else {
            self.tiles.remove(&key);
        }
    }

    pub fn set_overview_tile(&mut self, otx: i32, oty: i32, slice_index: i32, has_content: bool) {
        let key = pack_coord(otx, oty);
        if has_content || slice_index >= 0 {
            self.overview_tiles.insert(key, TileDescriptor { tx: otx, ty: oty, slice_index, has_content });
        } else {
            self.overview_tiles.remove(&key);
        }
    }

    pub fn clear(&mut self) {
        self.tiles.clear();
        self.overview_tiles.clear();
    }
}

pub struct MiltonDocument {
    pub layers: Vec<MiltonLayer>,
    pub active_layer_id: i64,
    pub background_color_rgb: i32,
}

impl MiltonDocument {
    pub fn new() -> Self {
        Self {
            layers: Vec::new(),
            active_layer_id: 1,
            background_color_rgb: 0xFFF6F4ED_u32 as i32,
        }
    }

    pub fn add_layer(&mut self, id: i64, name: String, opacity: f32, visible: bool) {
        self.layers.push(MiltonLayer::new(id, name, opacity, visible));
        self.active_layer_id = id;
    }

    pub fn remove_layer(&mut self, id: i64) -> bool {
        if self.layers.len() <= 1 {
            return false;
        }
        if let Some(pos) = self.layers.iter().position(|l| l.id == id) {
            self.layers.remove(pos);
            if self.active_layer_id == id {
                self.active_layer_id = self.layers.last().map(|l| l.id).unwrap_or(1);
            }
            true
        } else {
            false
        }
    }

    pub fn set_layer_opacity(&mut self, id: i64, opacity: f32) {
        if let Some(layer) = self.get_layer_mut(id) {
            layer.opacity = opacity.clamp(0.0, 1.0);
        }
    }

    pub fn set_layer_visible(&mut self, id: i64, visible: bool) {
        if let Some(layer) = self.get_layer_mut(id) {
            layer.visible = visible;
        }
    }

    pub fn reorder_layers(&mut self, layer_ids: &[i64]) {
        let mut new_layers = Vec::with_capacity(self.layers.len());
        for &id in layer_ids {
            if let Some(pos) = self.layers.iter().position(|l| l.id == id) {
                new_layers.push(self.layers.remove(pos));
            }
        }
        new_layers.append(&mut self.layers);
        self.layers = new_layers;
    }

    pub fn clear_layer(&mut self, id: i64) {
        if let Some(layer) = self.get_layer_mut(id) {
            layer.clear();
        }
    }

    pub fn set_tile(&mut self, layer_id: i64, tx: i32, ty: i32, slice_index: i32, has_content: bool) {
        if let Some(layer) = self.get_layer_mut(layer_id) {
            layer.set_tile(tx, ty, slice_index, has_content);
        }
    }

    pub fn set_overview_tile(&mut self, layer_id: i64, otx: i32, oty: i32, slice_index: i32, has_content: bool) {
        if let Some(layer) = self.get_layer_mut(layer_id) {
            layer.set_overview_tile(otx, oty, slice_index, has_content);
        }
    }

    pub fn get_layer_mut(&mut self, id: i64) -> Option<&mut MiltonLayer> {
        self.layers.iter_mut().find(|l| l.id == id)
    }

    pub fn get_bounds(&self) -> Option<(f32, f32, f32, f32)> {
        let mut min_x = f32::MAX;
        let mut min_y = f32::MAX;
        let mut max_x = f32::MIN;
        let mut max_y = f32::MIN;
        let mut has_any = false;

        for layer in &self.layers {
            if !layer.visible {
                continue;
            }
            for tile in layer.tiles.values() {
                if tile.has_content {
                    let left = tile.tx as f32 * TILE_SIZE;
                    let top = tile.ty as f32 * TILE_SIZE;
                    let right = left + TILE_SIZE;
                    let bottom = top + TILE_SIZE;

                    if left < min_x { min_x = left; }
                    if top < min_y { min_y = top; }
                    if right > max_x { max_x = right; }
                    if bottom > max_y { max_y = bottom; }
                    has_any = true;
                }
            }
        }

        if has_any {
            Some((min_x, min_y, max_x, max_y))
        } else {
            None
        }
    }

    /// Generates flat float stream: [world_x, world_y, world_tile_size, slice_index, opacity]
    /// directly into the out_slice for hardware instanced rendering.
    /// Excludes tiles in `exclude_rect` for `exclude_layer_id` (e.g. active liquify / transform).
    /// Returns the number of quad instances generated.
    pub fn generate_render_packet(
        &self,
        view_left: f32,
        view_top: f32,
        view_right: f32,
        view_bottom: f32,
        zoom: f32,
        force_overview: bool,
        exclude_layer_id: i64,
        exclude_left: f32,
        exclude_top: f32,
        exclude_right: f32,
        exclude_bottom: f32,
        out_slice: &mut [f32],
    ) -> usize {
        let use_overview = force_overview || zoom < 0.35;
        let max_instances = out_slice.len() / FLOATS_PER_INSTANCE;
        let mut instance_count = 0;
        let has_exclusion = exclude_layer_id != 0 && exclude_left < exclude_right && exclude_top < exclude_bottom;

        for layer in &self.layers {
            if !layer.visible || layer.opacity <= 0.001 {
                continue;
            }

            let check_exclude = has_exclusion && layer.id == exclude_layer_id;

            if use_overview && !layer.overview_tiles.is_empty() {
                // Overview Level 1
                let min_otx = (view_left / OVERVIEW_TILE_SIZE).floor() as i32;
                let max_otx = (view_right / OVERVIEW_TILE_SIZE).floor() as i32;
                let min_oty = (view_top / OVERVIEW_TILE_SIZE).floor() as i32;
                let max_oty = (view_bottom / OVERVIEW_TILE_SIZE).floor() as i32;

                for tile in layer.overview_tiles.values() {
                    if tile.tx >= min_otx && tile.tx <= max_otx && tile.ty >= min_oty && tile.ty <= max_oty {
                        if tile.has_content && tile.slice_index >= 0 {
                            if check_exclude {
                                let t_left = tile.tx as f32 * OVERVIEW_TILE_SIZE;
                                let t_top = tile.ty as f32 * OVERVIEW_TILE_SIZE;
                                let t_right = t_left + OVERVIEW_TILE_SIZE;
                                let t_bottom = t_top + OVERVIEW_TILE_SIZE;
                                if t_left < exclude_right && t_right > exclude_left && t_top < exclude_bottom && t_bottom > exclude_top {
                                    continue;
                                }
                            }

                            if instance_count >= max_instances {
                                return instance_count;
                            }
                            let offset = instance_count * FLOATS_PER_INSTANCE;
                            out_slice[offset] = tile.tx as f32 * OVERVIEW_TILE_SIZE;
                            out_slice[offset + 1] = tile.ty as f32 * OVERVIEW_TILE_SIZE;
                            out_slice[offset + 2] = OVERVIEW_TILE_SIZE;
                            out_slice[offset + 3] = tile.slice_index as f32;
                            out_slice[offset + 4] = layer.opacity;
                            instance_count += 1;
                        }
                    }
                }
            } else {
                // Full Resolution Level 0
                let min_tx = (view_left / TILE_SIZE).floor() as i32;
                let max_tx = (view_right / TILE_SIZE).floor() as i32;
                let min_ty = (view_top / TILE_SIZE).floor() as i32;
                let max_ty = (view_bottom / TILE_SIZE).floor() as i32;

                for tile in layer.tiles.values() {
                    if tile.tx >= min_tx && tile.tx <= max_tx && tile.ty >= min_ty && tile.ty <= max_ty {
                        if tile.has_content && tile.slice_index >= 0 {
                            if check_exclude {
                                let t_left = tile.tx as f32 * TILE_SIZE;
                                let t_top = tile.ty as f32 * TILE_SIZE;
                                let t_right = t_left + TILE_SIZE;
                                let t_bottom = t_top + TILE_SIZE;
                                if t_left < exclude_right && t_right > exclude_left && t_top < exclude_bottom && t_bottom > exclude_top {
                                    continue;
                                }
                            }

                            if instance_count >= max_instances {
                                return instance_count;
                            }
                            let offset = instance_count * FLOATS_PER_INSTANCE;
                            out_slice[offset] = tile.tx as f32 * TILE_SIZE;
                            out_slice[offset + 1] = tile.ty as f32 * TILE_SIZE;
                            out_slice[offset + 2] = TILE_SIZE;
                            out_slice[offset + 3] = tile.slice_index as f32;
                            out_slice[offset + 4] = layer.opacity;
                            instance_count += 1;
                        }
                    }
                }
            }
        }

        instance_count
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_coord_packing() {
        let (tx, ty) = (-42, 1337);
        let packed = pack_coord(tx, ty);
        let (unpacked_x, unpacked_y) = unpack_coord(packed);
        assert_eq!(tx, unpacked_x);
        assert_eq!(ty, unpacked_y);
    }

    #[test]
    fn test_document_layer_lifecycle() {
        let mut doc = MiltonDocument::new();
        doc.add_layer(1, "Layer 1".to_string(), 1.0, true);
        doc.add_layer(2, "Layer 2".to_string(), 0.5, true);

        assert_eq!(doc.layers.len(), 2);
        assert_eq!(doc.active_layer_id, 2);

        let removed = doc.remove_layer(2);
        assert!(removed);
        assert_eq!(doc.layers.len(), 1);
        assert_eq!(doc.active_layer_id, 1);

        // Cannot remove last layer
        let removed_last = doc.remove_layer(1);
        assert!(!removed_last);
        assert_eq!(doc.layers.len(), 1);
    }

    #[test]
    fn test_render_packet_generation() {
        let mut doc = MiltonDocument::new();
        doc.add_layer(1, "Background".to_string(), 1.0, true);

        let layer = doc.get_layer_mut(1).unwrap();
        // Add tile (0, 0) slice 5
        layer.set_tile(0, 0, 5, true);
        // Add tile (1, 0) slice 6
        layer.set_tile(1, 0, 6, true);
        // Add tile (10, 10) outside view slice 7
        layer.set_tile(10, 10, 7, true);

        let mut packet_buf = vec![0.0f32; 100];
        // View covers (0, 0) to (1000, 500)
        let count = doc.generate_render_packet(
            0.0, 0.0, 1000.0, 500.0, 1.0, false,
            0, 0.0, 0.0, 0.0, 0.0,
            &mut packet_buf
        );

        assert_eq!(count, 2);
        // Check first instance
        assert_eq!(packet_buf[2], 512.0); // Tile size
        assert_eq!(packet_buf[4], 1.0);   // Layer opacity

        // Test exclusion
        let count_excluded = doc.generate_render_packet(
            0.0, 0.0, 1000.0, 500.0, 1.0, false,
            1, 0.0, 0.0, 512.0, 512.0, // Exclude tile (0, 0)
            &mut packet_buf
        );
        assert_eq!(count_excluded, 1);
    }

    #[test]
    fn test_document_bounds_calculation() {
        let mut doc = MiltonDocument::new();
        doc.add_layer(1, "Art".to_string(), 1.0, true);
        let layer = doc.get_layer_mut(1).unwrap();

        layer.set_tile(-1, 0, 0, true); // covers -512..0, 0..512
        layer.set_tile(2, 3, 1, true);  // covers 1024..1536, 1536..2048

        let bounds = doc.get_bounds().expect("Bounds should exist");
        assert_eq!(bounds, (-512.0, 0.0, 1536.0, 2048.0));
    }
}
