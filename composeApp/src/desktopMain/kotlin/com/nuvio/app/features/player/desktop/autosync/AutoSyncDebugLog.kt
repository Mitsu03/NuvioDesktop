package com.nuvio.app.features.player.desktop.autosync

import co.touchlab.kermit.Logger

/**
 * Thin lazy-message logging wrapper for AutoSync diagnostics, backed by Kermit (the multiplatform
 * logger already used elsewhere in this codebase).
 *
 * NuvioTV's AutoSync V2 has a fuller `AutoSyncDebugLog` with an in-memory ring buffer and a
 * clipboard-export action for its debug-logs settings toggle. That UI-facing piece wasn't ported
 * here; this only covers the lazy `warn`/`info` calls the ported matching/loading code makes.
 */
internal object AutoSyncDebugLog {
    private val log = Logger.withTag("AutoSyncV2")

    fun info(message: () -> String) {
        log.i { message() }
    }

    fun warn(message: () -> String) {
        log.w { message() }
    }
}
