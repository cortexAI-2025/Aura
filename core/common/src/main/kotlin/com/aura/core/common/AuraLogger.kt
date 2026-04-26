package com.aura.core.common

import timber.log.Timber

/**
 * Structured, tag-based logger for the Aura agent trace.
 *
 * Produces lines in the format: [Tag] Message — matching the canonical logcat trace:
 *   [AuraAgent]  TASK / ENQUEUED
 *   [TaskQueue]  START / COMPLETE / PREEMPTED
 *   [ReAct]      STEP N → REASON / ACT / FINAL
 *   [Tool]       ToolName → OBS
 *   [ActionGuard] CHECK ToolName → ALLOWED / BLOCKED / CONFIRM
 *
 * The [delegate] is swappable so integration tests can capture log lines
 * without touching the Android runtime (no Timber.plant needed).
 */
object AuraLogger {

    @Volatile
    var delegate: AuraLogDelegate = TimberLogDelegate

    // ── Tag constants ─────────────────────────────────────────────────────────
    const val TAG_AGENT = "AuraAgent"
    const val TAG_QUEUE = "TaskQueue"
    const val TAG_REACT  = "ReAct"
    const val TAG_TOOL   = "Tool"
    const val TAG_GUARD  = "ActionGuard"

    // ── Emit ──────────────────────────────────────────────────────────────────
    fun log(tag: String, message: String) = delegate.log(tag, message)
}

// ─── Delegate interface ───────────────────────────────────────────────────────

interface AuraLogDelegate {
    fun log(tag: String, message: String)
}

/** Production delegate — writes through Timber (standard Android logcat). */
private object TimberLogDelegate : AuraLogDelegate {
    override fun log(tag: String, message: String) = Timber.tag(tag).i(message)
}
