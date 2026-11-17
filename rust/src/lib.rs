//! GitPush native engine (JNI entry points).
//!
//! Every call takes/returns JSON so the Kotlin side stays decoupled from Rust types:
//!   success: {"ok":true,"data":{...}}
//!   failure: {"ok":false,"error":{"code":"...","message":"..."}}

mod engine;
mod error;
mod gitobj;
mod pack;
mod pktline;
mod progress;
mod remote;
mod scan;
mod types;
mod zipx;

use std::fs::File;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};

use crate::error::{Error, Result};
use crate::progress::{JniReporter, Rep};
use crate::types::EngineRequest;

fn envelope(r: std::thread::Result<Result<Value>>) -> String {
    let v = match r {
        Ok(Ok(data)) => json!({ "ok": true, "data": data }),
        Ok(Err(e)) => json!({ "ok": false, "error": { "code": e.code, "message": e.message } }),
        Err(p) => {
            let msg = p
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "native panic".to_string());
            json!({ "ok": false, "error": { "code": "PANIC", "message": msg } })
        }
    };
    v.to_string()
}

fn err_envelope(code: &'static str, message: String) -> String {
    envelope(Ok(Err(Error::new(code, message))))
}

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn make_reporter(env: &mut JNIEnv, listener: &JObject) -> Result<Rep> {
    let vm = env
        .get_java_vm()
        .map_err(|e| Error::new("JNI", e.to_string()))?;
    let global = env
        .new_global_ref(listener)
        .map_err(|e| Error::new("JNI", e.to_string()))?;
    Ok(Arc::new(JniReporter::new(vm, global)))
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Result<String> {
    let js = env
        .get_string(s)
        .map_err(|e| Error::new("JNI", e.to_string()))?;
    Ok(js.into())
}

#[no_mangle]
pub extern "system" fn Java_com_gitpush_app_core_NativeBridge_nativeVersion<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    to_jstring(&mut env, env!("CARGO_PKG_VERSION").to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_gitpush_app_core_NativeBridge_nativeCancel<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    progress::request_cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_gitpush_app_core_NativeBridge_nativePlan<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    request: JString<'l>,
    listener: JObject<'l>,
) -> jstring {
    progress::reset_cancel();
    let json_in = match read_string(&mut env, &request) {
        Ok(s) => s,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let rep = match make_reporter(&mut env, &listener) {
        Ok(r) => r,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let r = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let req: EngineRequest = serde_json::from_str(&json_in)?;
        let out = engine::plan(&req, rep.clone())?;
        Ok(serde_json::to_value(out)?)
    }));
    to_jstring(&mut env, envelope(r))
}

#[no_mangle]
pub extern "system" fn Java_com_gitpush_app_core_NativeBridge_nativePush<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    request: JString<'l>,
    listener: JObject<'l>,
) -> jstring {
    progress::reset_cancel();
    let json_in = match read_string(&mut env, &request) {
        Ok(s) => s,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let rep = match make_reporter(&mut env, &listener) {
        Ok(r) => r,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let r = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let req: EngineRequest = serde_json::from_str(&json_in)?;
        let out = engine::push(&req, rep.clone())?;
        Ok(serde_json::to_value(out)?)
    }));
    to_jstring(&mut env, envelope(r))
}

#[cfg(unix)]
#[no_mangle]
pub extern "system" fn Java_com_gitpush_app_core_NativeBridge_nativeExtractZip<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    dest: JString<'l>,
    strip_root: jboolean,
    max_file_bytes: jlong,
    listener: JObject<'l>,
) -> jstring {
    use std::os::unix::io::FromRawFd;
    progress::reset_cancel();
    // Take ownership of the descriptor first so it is always closed.
    let file = unsafe { File::from_raw_fd(fd) };
    let dest_s = match read_string(&mut env, &dest) {
        Ok(s) => s,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let rep = match make_reporter(&mut env, &listener) {
        Ok(r) => r,
        Err(e) => return to_jstring(&mut env, err_envelope(e.code, e.message)),
    };
    let max = if max_file_bytes > 0 { max_file_bytes as u64 } else { u64::MAX };
    let r = catch_unwind(AssertUnwindSafe(|| -> Result<Value> {
        let out = zipx::extract(file, std::path::Path::new(&dest_s), strip_root != 0, max, &rep)?;
        Ok(serde_json::to_value(out)?)
    }));
    to_jstring(&mut env, envelope(r))
}
