//! High-performance off-heap undo/redo delta history manager.
//!
//! Stores compressed sub-tile patches and layer lifecycle events outside the
//! Android ART JVM garbage-collected heap, eliminating memory fragmentation
//! and GC pauses during prolonged creative painting sessions.

use std::collections::VecDeque;

#[derive(Clone, Debug, PartialEq)]
pub struct TileDelta {
    pub layer_id: i64,
    pub tx: i32,
    pub ty: i32,
    pub min_x: i32,
    pub min_y: i32,
    pub width: i32,
    pub height: i32,
    pub before_patch: Option<Vec<u8>>,
    pub after_patch: Option<Vec<u8>>,
}

impl TileDelta {
    pub fn is_sub_tile(&self) -> bool {
        self.width < crate::constants::TILE_SIZE as i32 || self.height < crate::constants::TILE_SIZE as i32
    }

    pub fn memory_bytes(&self) -> usize {
        let before_len = self.before_patch.as_ref().map(|b| b.len()).unwrap_or(0);
        let after_len = self.after_patch.as_ref().map(|a| a.len()).unwrap_or(0);
        std::mem::size_of::<Self>() + before_len + after_len
    }
}

#[derive(Clone, Debug)]
pub enum HistoryCommand {
    Stroke(Vec<TileDelta>),
    AddLayer {
        layer_id: i64,
        name: String,
        opacity: f32,
        is_visible: bool,
        storage_index: i32,
        active_layer_id_before: i64,
    },
    DeleteLayer {
        layer_id: i64,
        name: String,
        opacity: f32,
        is_visible: bool,
        storage_index: i32,
        active_layer_id_before: i64,
        tiles_snapshot: Vec<(i32, i32, Vec<u8>)>,
    },
}

impl HistoryCommand {
    pub fn memory_bytes(&self) -> usize {
        match self {
            Self::Stroke(deltas) => {
                deltas.iter().map(|d| d.memory_bytes()).sum::<usize>() + std::mem::size_of::<Self>()
            }
            Self::AddLayer { name, .. } => {
                name.len() + std::mem::size_of::<Self>()
            }
            Self::DeleteLayer { name, tiles_snapshot, .. } => {
                let snap_bytes: usize = tiles_snapshot.iter().map(|(_, _, b)| b.len()).sum();
                name.len() + snap_bytes + std::mem::size_of::<Self>()
            }
        }
    }
}

pub struct HistoryManager {
    max_history_size: usize,
    undo_stack: VecDeque<HistoryCommand>,
    redo_stack: VecDeque<HistoryCommand>,
}

impl HistoryManager {
    pub fn new(max_history_size: usize) -> Self {
        Self {
            max_history_size,
            undo_stack: VecDeque::with_capacity(max_history_size),
            redo_stack: VecDeque::new(),
        }
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo_stack.is_empty()
    }

    pub fn undo_count(&self) -> usize {
        self.undo_stack.len()
    }

    pub fn redo_count(&self) -> usize {
        self.redo_stack.len()
    }

    pub fn push_stroke(&mut self, deltas: Vec<TileDelta>) {
        if deltas.is_empty() {
            return;
        }
        self.redo_stack.clear();
        self.undo_stack.push_back(HistoryCommand::Stroke(deltas));
        while self.undo_stack.len() > self.max_history_size {
            self.undo_stack.pop_front();
        }
    }

    pub fn begin_stroke(&mut self) {
        self.redo_stack.clear();
        self.undo_stack.push_back(HistoryCommand::Stroke(Vec::new()));
        while self.undo_stack.len() > self.max_history_size {
            self.undo_stack.pop_front();
        }
    }

    pub fn append_stroke_delta(&mut self, delta: TileDelta) {
        if let Some(HistoryCommand::Stroke(deltas)) = self.undo_stack.back_mut() {
            deltas.push(delta);
        }
    }

    pub fn peek_undo_stroke(&self) -> Option<&Vec<TileDelta>> {
        if let Some(HistoryCommand::Stroke(deltas)) = self.undo_stack.back() {
            Some(deltas)
        } else {
            None
        }
    }

    pub fn peek_redo_stroke(&self) -> Option<&Vec<TileDelta>> {
        if let Some(HistoryCommand::Stroke(deltas)) = self.redo_stack.back() {
            Some(deltas)
        } else {
            None
        }
    }

    pub fn commit_undo(&mut self) -> bool {
        if let Some(cmd) = self.undo_stack.pop_back() {
            self.redo_stack.push_back(cmd);
            true
        } else {
            false
        }
    }

    pub fn commit_redo(&mut self) -> bool {
        if let Some(cmd) = self.redo_stack.pop_back() {
            self.undo_stack.push_back(cmd);
            true
        } else {
            false
        }
    }

    /// Decompresses and applies either before_patch (for undo) or after_patch (for redo)
    /// directly to a 512x512 tile buffer.
    pub fn apply_delta_to_slice(delta: &TileDelta, is_undo: bool, target_slice: &mut [u8]) -> Result<bool, String> {
        let patch = if is_undo {
            &delta.before_patch
        } else {
            &delta.after_patch
        };

        if let Some(compressed) = patch {
            if compressed.is_empty() {
                target_slice.fill(0);
                return Ok(true);
            }

            if delta.is_sub_tile() {
                let rect = crate::sub_tile::Rect {
                    min_x: delta.min_x as usize,
                    min_y: delta.min_y as usize,
                    width: delta.width as usize,
                    height: delta.height as usize,
                };
                crate::sub_tile::apply_sub_tile_patch(target_slice, compressed, rect)?;
            } else {
                crate::compression::decompress_tile_to_slice(compressed, target_slice)?;
            }
            Ok(true)
        } else {
            target_slice.fill(0);
            Ok(false)
        }
    }

    pub fn push_add_layer(
        &mut self,
        layer_id: i64,
        name: String,
        opacity: f32,
        is_visible: bool,
        storage_index: i32,
        active_layer_id_before: i64,
    ) {
        self.redo_stack.clear();
        self.undo_stack.push_back(HistoryCommand::AddLayer {
            layer_id,
            name,
            opacity,
            is_visible,
            storage_index,
            active_layer_id_before,
        });
        while self.undo_stack.len() > self.max_history_size {
            self.undo_stack.pop_front();
        }
    }

    pub fn push_delete_layer(
        &mut self,
        layer_id: i64,
        name: String,
        opacity: f32,
        is_visible: bool,
        storage_index: i32,
        active_layer_id_before: i64,
        tiles_snapshot: Vec<(i32, i32, Vec<u8>)>,
    ) {
        self.redo_stack.clear();
        self.undo_stack.push_back(HistoryCommand::DeleteLayer {
            layer_id,
            name,
            opacity,
            is_visible,
            storage_index,
            active_layer_id_before,
            tiles_snapshot,
        });
        while self.undo_stack.len() > self.max_history_size {
            self.undo_stack.pop_front();
        }
    }

    pub fn append_delete_layer_tile(&mut self, tx: i32, ty: i32, tile_bytes: Vec<u8>) {
        if let Some(HistoryCommand::DeleteLayer { tiles_snapshot, .. }) = self.undo_stack.back_mut() {
            tiles_snapshot.push((tx, ty, tile_bytes));
        }
    }

    pub fn undo_delete_layer_tiles_count(&self) -> usize {
        self.peek_undo_delete_layer_tiles().map(|v| v.len()).unwrap_or(0)
    }

    pub fn undo_delete_layer_tile_at(&self, index: usize) -> Option<(i32, i32, &[u8])> {
        self.peek_undo_delete_layer_tiles().and_then(|v| v.get(index)).map(|(tx, ty, bytes)| (*tx, *ty, bytes.as_slice()))
    }

    pub fn pop_undo(&mut self) -> Option<HistoryCommand> {
        let cmd = self.undo_stack.pop_back()?;
        self.redo_stack.push_back(cmd.clone());
        Some(cmd)
    }

    pub fn pop_redo(&mut self) -> Option<HistoryCommand> {
        let cmd = self.redo_stack.pop_back()?;
        self.undo_stack.push_back(cmd.clone());
        Some(cmd)
    }

    pub fn clear(&mut self) {
        self.undo_stack.clear();
        self.redo_stack.clear();
    }

    pub fn peek_undo_command_type(&self) -> i32 {
        match self.undo_stack.back() {
            None => 0,
            Some(HistoryCommand::Stroke(_)) => 1,
            Some(HistoryCommand::AddLayer { .. }) => 2,
            Some(HistoryCommand::DeleteLayer { .. }) => 3,
        }
    }

    pub fn peek_redo_command_type(&self) -> i32 {
        match self.redo_stack.back() {
            None => 0,
            Some(HistoryCommand::Stroke(_)) => 1,
            Some(HistoryCommand::AddLayer { .. }) => 2,
            Some(HistoryCommand::DeleteLayer { .. }) => 3,
        }
    }

    pub fn peek_undo_layer_meta(&self) -> Option<(i64, String, f32, bool, i32, i64)> {
        match self.undo_stack.back() {
            Some(HistoryCommand::AddLayer { layer_id, name, opacity, is_visible, storage_index, active_layer_id_before }) => {
                Some((*layer_id, name.clone(), *opacity, *is_visible, *storage_index, *active_layer_id_before))
            }
            Some(HistoryCommand::DeleteLayer { layer_id, name, opacity, is_visible, storage_index, active_layer_id_before, .. }) => {
                Some((*layer_id, name.clone(), *opacity, *is_visible, *storage_index, *active_layer_id_before))
            }
            _ => None,
        }
    }

    pub fn peek_redo_layer_meta(&self) -> Option<(i64, String, f32, bool, i32, i64)> {
        match self.redo_stack.back() {
            Some(HistoryCommand::AddLayer { layer_id, name, opacity, is_visible, storage_index, active_layer_id_before }) => {
                Some((*layer_id, name.clone(), *opacity, *is_visible, *storage_index, *active_layer_id_before))
            }
            Some(HistoryCommand::DeleteLayer { layer_id, name, opacity, is_visible, storage_index, active_layer_id_before, .. }) => {
                Some((*layer_id, name.clone(), *opacity, *is_visible, *storage_index, *active_layer_id_before))
            }
            _ => None,
        }
    }

    pub fn peek_undo_delete_layer_tiles(&self) -> Option<&Vec<(i32, i32, Vec<u8>)>> {
        match self.undo_stack.back() {
            Some(HistoryCommand::DeleteLayer { tiles_snapshot, .. }) => Some(tiles_snapshot),
            _ => None,
        }
    }

    pub fn peek_redo_delete_layer_tiles(&self) -> Option<&Vec<(i32, i32, Vec<u8>)>> {
        match self.redo_stack.back() {
            Some(HistoryCommand::DeleteLayer { tiles_snapshot, .. }) => Some(tiles_snapshot),
            _ => None,
        }
    }

    pub fn total_memory_bytes(&self) -> usize {
        let u: usize = self.undo_stack.iter().map(|c| c.memory_bytes()).sum();
        let r: usize = self.redo_stack.iter().map(|c| c.memory_bytes()).sum();
        u + r
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_history_push_and_undo_redo_stroke() {
        let mut history = HistoryManager::new(5);
        assert!(!history.can_undo());
        assert!(!history.can_redo());

        let delta1 = TileDelta {
            layer_id: 1,
            tx: 0,
            ty: 0,
            min_x: 10,
            min_y: 20,
            width: 50,
            height: 30,
            before_patch: Some(vec![1, 2, 3]),
            after_patch: Some(vec![4, 5, 6]),
        };
        assert!(delta1.is_sub_tile());

        history.push_stroke(vec![delta1.clone()]);
        assert!(history.can_undo());
        assert!(!history.can_redo());
        assert_eq!(history.undo_count(), 1);

        // Undo
        let undone = history.pop_undo();
        assert!(undone.is_some());
        if let Some(HistoryCommand::Stroke(deltas)) = undone {
            assert_eq!(deltas.len(), 1);
            assert_eq!(deltas[0].tx, 0);
            assert_eq!(deltas[0].width, 50);
        } else {
            panic!("Expected stroke command");
        }
        assert!(!history.can_undo());
        assert!(history.can_redo());

        // Redo
        let redone = history.pop_redo();
        assert!(redone.is_some());
        assert!(history.can_undo());
        assert!(!history.can_redo());
    }

    #[test]
    fn test_history_max_capacity_trimming() {
        let mut history = HistoryManager::new(3);

        for i in 0..5 {
            let delta = TileDelta {
                layer_id: 1,
                tx: i,
                ty: 0,
                min_x: 0,
                min_y: 0,
                width: 512,
                height: 512,
                before_patch: None,
                after_patch: Some(vec![i as u8; 100]),
            };
            history.push_stroke(vec![delta]);
        }

        assert_eq!(history.undo_count(), 3);
        assert!(history.total_memory_bytes() > 0);
    }

    #[test]
    fn test_history_push_and_undo_redo_add_layer() {
        let mut history = HistoryManager::new(5);
        history.push_add_layer(2, "Layer 2".to_string(), 0.8, true, 1, 1);
        assert!(history.can_undo());
        assert_eq!(history.peek_undo_command_type(), 2);

        let meta = history.peek_undo_layer_meta().unwrap();
        assert_eq!(meta.0, 2);
        assert_eq!(meta.1, "Layer 2");
        assert_eq!(meta.4, 1);
        assert_eq!(meta.5, 1);

        assert!(history.commit_undo());
        assert!(!history.can_undo());
        assert!(history.can_redo());
        assert_eq!(history.peek_redo_command_type(), 2);

        let redo_meta = history.peek_redo_layer_meta().unwrap();
        assert_eq!(redo_meta.0, 2);
        assert_eq!(redo_meta.1, "Layer 2");

        assert!(history.commit_redo());
        assert!(history.can_undo());
        assert!(!history.can_redo());
    }

    #[test]
    fn test_history_push_and_undo_redo_delete_layer() {
        let mut history = HistoryManager::new(5);
        history.push_delete_layer(3, "Layer 3".to_string(), 1.0, true, 2, 1, Vec::new());
        history.append_delete_layer_tile(0, 0, vec![10, 20, 30]);
        history.append_delete_layer_tile(1, 0, vec![40, 50, 60]);

        assert!(history.can_undo());
        assert_eq!(history.peek_undo_command_type(), 3);
        assert_eq!(history.undo_delete_layer_tiles_count(), 2);

        let t0 = history.undo_delete_layer_tile_at(0).unwrap();
        assert_eq!(t0.0, 0);
        assert_eq!(t0.1, 0);
        assert_eq!(t0.2, &[10, 20, 30]);

        let t1 = history.undo_delete_layer_tile_at(1).unwrap();
        assert_eq!(t1.0, 1);
        assert_eq!(t1.1, 0);
        assert_eq!(t1.2, &[40, 50, 60]);

        assert!(history.commit_undo());
        assert!(!history.can_undo());
        assert!(history.can_redo());
        assert_eq!(history.peek_redo_command_type(), 3);

        assert!(history.commit_redo());
        assert!(history.can_undo());
        assert!(!history.can_redo());
    }

    #[test]
    fn test_incremental_stroke_and_apply_delta() {
        let mut history = HistoryManager::new(5);
        history.begin_stroke();

        let patch_before = crate::compression::compress_tile(&vec![0xAA; 50 * 50 * 4]).unwrap();
        let patch_after = crate::compression::compress_tile(&vec![0xBB; 50 * 50 * 4]).unwrap();

        let delta = TileDelta {
            layer_id: 1,
            tx: 0,
            ty: 0,
            min_x: 0,
            min_y: 0,
            width: 50,
            height: 50,
            before_patch: Some(patch_before),
            after_patch: Some(patch_after),
        };
        history.append_stroke_delta(delta);

        assert!(history.can_undo());
        let peeked = history.peek_undo_stroke();
        assert!(peeked.is_some());
        assert_eq!(peeked.unwrap().len(), 1);

        let mut target = vec![0u8; crate::constants::TILE_BYTES];
        let has_content = HistoryManager::apply_delta_to_slice(&peeked.unwrap()[0], true, &mut target).unwrap();
        assert!(has_content);
        assert_eq!(target[0], 0xAA);

        let committed = history.commit_undo();
        assert!(committed);
        assert!(!history.can_undo());
        assert!(history.can_redo());
    }
}
