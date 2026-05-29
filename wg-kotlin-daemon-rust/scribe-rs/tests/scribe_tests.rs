use scribe_rs::{Saver, Scribe, SealedScroll};
use serde_json::Value;
use std::sync::{Arc, Mutex};

struct MemorySaver {
    written: Mutex<Vec<SealedScroll>>,
}

impl Saver for MemorySaver {
    fn save(&self, scroll: &SealedScroll) {
        self.written.lock().unwrap().push(scroll.clone());
    }
}

#[tokio::test]
async fn new_scroll_contains_scroll_id() {
    let scribe = Scribe::builder().build();
    let scroll = scribe.new_scroll(None);

    let id = scroll.get("scroll_id").and_then(Value::as_str).expect("scroll_id should exist and be string");
    assert!(!id.is_empty(), "scroll_id should not be empty");
}

#[tokio::test]
async fn new_scroll_inherits_imprint_values() {
    let scribe = Scribe::builder().imprint("service", "wg-daemon").imprint("version", "0.1.0").build();

    let scroll = scribe.new_scroll(None);
    assert_eq!(scroll.get("service").and_then(Value::as_str), Some("wg-daemon"));
    assert_eq!(scroll.get("version").and_then(Value::as_str), Some("0.1.0"));
}

#[tokio::test]
async fn saver_receives_sealed_scrolls() {
    let memory = Arc::new(MemorySaver { written: Mutex::new(Vec::new()) });

    let mut scribe = Scribe::builder().saver(memory.clone()).build();
    scribe.hire();

    let mut scroll = scribe.new_scroll(Some("scroll-1".to_string()));
    scroll.insert("event".to_string(), Value::String("daemon_startup".to_string()));
    scribe.seal(scroll, true);

    scribe.retire().await;

    let written = memory.written.lock().unwrap();
    assert_eq!(written.len(), 1, "saver should have received one scroll");
    let sealed = &written[0];
    assert!(sealed.success);
    assert_eq!(sealed.data.get("scroll_id").and_then(Value::as_str), Some("scroll-1"));
    assert_eq!(sealed.data.get("event").and_then(Value::as_str), Some("daemon_startup"));
}

#[tokio::test]
async fn sealed_scroll_matches_expected_wire_shape() {
    let mut scribe = Scribe::builder().imprint("service", "wg-daemon").build();
    scribe.hire();

    let mut scroll = scribe.new_scroll(Some("shape-1".to_string()));
    scroll.insert("event".to_string(), Value::String("daemon_check".to_string()));
    let sealed = scribe.seal(scroll, false);
    scribe.retire().await;

    let json = serde_json::to_value(sealed).expect("sealed scroll should serialize");
    let object = json.as_object().expect("sealed scroll should be object");
    assert_eq!(object.len(), 2, "sealed scroll top-level should have success + data only");
    assert_eq!(json["success"], Value::Bool(false));
    assert_eq!(json["data"]["scroll_id"], Value::String("shape-1".to_string()));
    assert_eq!(json["data"]["service"], Value::String("wg-daemon".to_string()));
    assert_eq!(json["data"]["event"], Value::String("daemon_check".to_string()));
}
