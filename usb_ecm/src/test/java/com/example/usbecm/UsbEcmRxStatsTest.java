package com.example.usbecm;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class UsbEcmRxStatsTest {
    @Test
    public void negativeCompletionIsCountedSeparatelyFromIoException() {
        UsbEcmRxStats stats = new UsbEcmRxStats();

        stats.recordCompletion(-1);

        assertEquals(1L, stats.getNegativeCompletions());
        assertEquals(0L, stats.getIoExceptions());
        assertEquals(0L, stats.getFrames());
        assertEquals(0L, stats.getMalformedFrames());
    }

    @Test
    public void runtimeIoExceptionDoesNotMasqueradeAsNegativeCompletion() {
        UsbEcmRxStats stats = new UsbEcmRxStats();

        stats.recordIoException();

        assertEquals(0L, stats.getNegativeCompletions());
        assertEquals(1L, stats.getIoExceptions());
    }

    @Test
    public void validFrameUpdatesFrameAndByteCounters() {
        UsbEcmRxStats stats = new UsbEcmRxStats();

        stats.recordCompletion(60);
        stats.recordCompletion(74);

        assertEquals(2L, stats.getFrames());
        assertEquals(134L, stats.getBytes());
        assertEquals(0L, stats.getMalformedFrames());
        assertEquals(0L, stats.getNegativeCompletions());
    }

    @Test
    public void undersizedAndZeroLengthCompletionsAreHandledSeparately() {
        UsbEcmRxStats stats = new UsbEcmRxStats();

        stats.recordCompletion(13);
        stats.recordCompletion(0);

        assertEquals(0L, stats.getFrames());
        assertEquals(0L, stats.getBytes());
        assertEquals(1L, stats.getMalformedFrames());
        assertEquals(0L, stats.getNegativeCompletions());
    }
}
