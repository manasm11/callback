package com.shopcallback.tracker.sync

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock for every step that reads callbacks and writes them back by the resolution
 * rules: the call-log scan (CallWatcherService.scanOnce) and applying other phones' events
 * (RemoteEventApplier.apply). Both read a thread and then upsert or resolve it; without one lock,
 * one could overwrite the other's change made in between (e.g. a remote Mark resolved lost for good,
 * since its event is already marked applied).
 *
 * Hold it only around local database work, never network I/O. Kotlin's Mutex is not reentrant:
 * never take it on a path that already holds it (scanOnce calls RemoteEventApplier.apply directly).
 */
object CallbackRulesLock {
    val mutex = Mutex()
}
