package dev.piexel.codex.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A swap waits for active generation and blocks new requests until the replacement is published. */
internal class CodexGenerationGate(private val capacity: Int = 2) {
    private val slots = Semaphore(capacity)
    private val swaps = Mutex()

    suspend fun <T> generate(action: suspend () -> T): T = slots.withPermit { action() }

    suspend fun <T> pause(action: suspend () -> T): T = swaps.withLock {
        var acquired = 0
        try {
            repeat(capacity) {
                slots.acquire()
                acquired++
            }
            action()
        } finally {
            repeat(acquired) { slots.release() }
        }
    }
}
