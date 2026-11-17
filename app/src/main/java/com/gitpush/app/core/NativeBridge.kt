package com.gitpush.app.core

/** Called from Rust (by name!) while a native operation runs. Keep the signature in sync. */
fun interface ProgressListener {
    fun onProgress(phase: String, done: Long, total: Long, message: String)
}

/** Thin JNI surface of the Rust engine (`rust/src/lib.rs`). Everything is JSON in, JSON out. */
object NativeBridge {
    init {
        System.loadLibrary("gitpush_core")
    }

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeCancel()
    @JvmStatic external fun nativePlan(requestJson: String, listener: ProgressListener): String
    @JvmStatic external fun nativePush(requestJson: String, listener: ProgressListener): String
    @JvmStatic external fun nativeExtractZip(
        fd: Int,
        destDir: String,
        stripRoot: Boolean,
        maxFileBytes: Long,
        listener: ProgressListener,
    ): String
}
