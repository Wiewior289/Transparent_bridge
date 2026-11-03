package com.example.usbecm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe RX accounting separated from Android USB calls so the result
 * classification can be covered by local JVM unit tests.
 */
final class UsbEcmRxStats {
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong malformedFrames = new AtomicLong();
    private final AtomicLong negativeCompletions = new AtomicLong();
    private final AtomicLong ioExceptions = new AtomicLong();

    void recordCompletion(int result) {
        if (result > 0) {
            if (result >= 14) {
                frames.incrementAndGet();
                bytes.addAndGet(result);
            } else {
                malformedFrames.incrementAndGet();
            }
        } else if (result < 0) {
            // Android bulkTransfer does not expose enough detail to split timeout from I/O failure.
            negativeCompletions.incrementAndGet();
        }
    }

    void recordIoException() {
        ioExceptions.incrementAndGet();
    }

    long getFrames() { return frames.get(); }
    long getBytes() { return bytes.get(); }
    long getMalformedFrames() { return malformedFrames.get(); }
    long getNegativeCompletions() { return negativeCompletions.get(); }
    long getIoExceptions() { return ioExceptions.get(); }
}
