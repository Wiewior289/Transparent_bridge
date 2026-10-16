package com.example.core.net

import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-allocated Zero-Copy Ring Buffer for high-performance userland packet forwarding.
 * As specified in ETAP 3 of the blueprint:
 * Fixed pool of 2048 blocks x 1600 bytes. Avoids memory allocation and GC pauses.
 */
class RingBuffer(
    val capacity: Int = 2048,
    val blockSize: Int = 1600
) {
    private val buffer: Array<ByteArray> = Array(capacity) { ByteArray(blockSize) }
    private val lengths: IntArray = IntArray(capacity)
    
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)
    private val size = AtomicInteger(0)

    val currentSize: Int
        get() = size.get()

    fun write(src: ByteArray, offset: Int, length: Int): Boolean {
        if (size.get() >= capacity || length > blockSize) {
            return false // Drop packet on buffer overflow
        }
        val currentHead = head.get()
        System.arraycopy(src, offset, buffer[currentHead], 0, length)
        lengths[currentHead] = length
        
        head.set((currentHead + 1) % capacity)
        size.incrementAndGet()
        return true
    }

    /**
     * Reads directly into target without creating new objects.
     * Returns bytes copied, or -1 if empty.
     */
    fun read(target: ByteArray, targetOffset: Int = 0): Int {
        if (size.get() == 0) return -1
        val currentTail = tail.get()
        val len = lengths[currentTail]
        System.arraycopy(buffer[currentTail], 0, target, targetOffset, len)
        
        tail.set((currentTail + 1) % capacity)
        size.decrementAndGet()
        return len
    }

    fun clear() {
        head.set(0)
        tail.set(0)
        size.set(0)
    }
}
