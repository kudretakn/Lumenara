package dev.lumenara.player.api

/** Cancellation unblocks reads; native stop must finish before descriptor close is attempted. */
suspend fun wp18CloseInput(cancelReads: () -> Unit, stopNative: suspend () -> Unit, closeDescriptor: () -> Unit) {
    try { cancelReads() } finally {
        try { stopNative() } finally { closeDescriptor() }
    }
}
