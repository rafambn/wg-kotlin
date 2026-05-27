use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub type Scroll = Map<String, Value>;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SealedScroll {
    pub success: bool,
    pub data: Map<String, Value>,
}

pub fn new_scroll_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

pub trait ScrollExt {
    fn extend(&mut self, other: &Scroll);
    fn append(&mut self, key: &str, nested: &Scroll);
}

impl ScrollExt for Scroll {
    fn extend(&mut self, other: &Scroll) {
        for (key, value) in other {
            if !self.contains_key(key) {
                self.insert(key.clone(), value.clone());
            }
        }
    }

    fn append(&mut self, key: &str, nested: &Scroll) {
        self.insert(key.to_string(), Value::Object(nested.clone()));
    }
}
