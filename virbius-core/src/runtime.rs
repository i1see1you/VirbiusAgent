use crate::audit;
use crate::manifest;
use std::sync::OnceLock;
use std::thread;
use std::time::Duration;

static FLUSH_STARTED: OnceLock<()> = OnceLock::new();

pub fn ensure_flush_loop() {
    FLUSH_STARTED.get_or_init(|| {
        thread::spawn(|| loop {
            thread::sleep(Duration::from_millis(
                manifest::effective_sdk_config()
                    .audit_flush_interval_ms
                    .max(5000),
            ));
            audit::flush_pending(&manifest::effective_sdk_config());
        });
    });
}
