use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;

use crate::error::{Error, Result};

static CANCEL: AtomicBool = AtomicBool::new(false);

pub fn reset_cancel() {
    CANCEL.store(false, Ordering::SeqCst);
}

pub fn request_cancel() {
    CANCEL.store(true, Ordering::SeqCst);
}

pub fn check_cancel() -> Result<()> {
    if CANCEL.load(Ordering::Relaxed) {
        Err(Error::cancelled())
    } else {
        Ok(())
    }
}

/// Progress sink. Implementations must be cheap when `force == false`
/// because it is called from hot loops on worker threads.
pub trait Reporter: Send + Sync {
    fn report(&self, phase: &str, done: u64, total: u64, msg: &str, force: bool);
}

pub type Rep = Arc<dyn Reporter>;

#[allow(dead_code)]
pub struct NoopReporter;

impl Reporter for NoopReporter {
    fn report(&self, _: &str, _: u64, _: u64, _: &str, _: bool) {}
}

/// Forwards progress to `ProgressListener.onProgress(String, long, long, String)` in Kotlin.
pub struct JniReporter {
    vm: JavaVM,
    listener: GlobalRef,
    start: Instant,
    last_ms: AtomicU64,
}

impl JniReporter {
    pub fn new(vm: JavaVM, listener: GlobalRef) -> Self {
        JniReporter {
            vm,
            listener,
            start: Instant::now(),
            last_ms: AtomicU64::new(0),
        }
    }
}

impl Reporter for JniReporter {
    fn report(&self, phase: &str, done: u64, total: u64, msg: &str, force: bool) {
        let now = self.start.elapsed().as_millis() as u64 + 1;
        if force {
            self.last_ms.store(now, Ordering::Relaxed);
        } else {
            let last = self.last_ms.load(Ordering::Relaxed);
            if last != 0 && now.saturating_sub(last) < 80 {
                return;
            }
            if self
                .last_ms
                .compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed)
                .is_err()
            {
                return;
            }
        }

        let mut guard = match self.vm.attach_current_thread() {
            Ok(g) => g,
            Err(_) => return,
        };
        // Android caps local references at 512, so every callback runs in its own frame.
        let _ = guard.with_local_frame(8, |env| -> jni::errors::Result<()> {
            let p: JObject = env.new_string(phase)?.into();
            let m: JObject = env.new_string(msg)?.into();
            env.call_method(
                &self.listener,
                "onProgress",
                "(Ljava/lang/String;JJLjava/lang/String;)V",
                &[
                    JValue::Object(&p),
                    JValue::Long(done as i64),
                    JValue::Long(total as i64),
                    JValue::Object(&m),
                ],
            )?;
            Ok(())
        });
        if guard.exception_check().unwrap_or(false) {
            let _ = guard.exception_clear();
        }
    }
}
