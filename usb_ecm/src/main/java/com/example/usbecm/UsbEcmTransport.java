package com.example.usbecm;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import java.util.concurrent.atomic.AtomicLong;

/** Frame-oriented CDC-ECM transport. Exposes Ethernet frames, not IP semantics. */
public final class UsbEcmTransport implements AutoCloseable {
    public interface Logger { void log(String message); }
    private final UsbDeviceConnection connection;
    private final CdcEcmProfile profile;
    private final CdcEcmBinder binder;
    private final Logger logger;
    private final String macAddress;
    private final AtomicLong txFrames = new AtomicLong();
    private final AtomicLong rxFrames = new AtomicLong();
    private final AtomicLong txBytes = new AtomicLong();
    private final AtomicLong rxBytes = new AtomicLong();
    private final AtomicLong txErrors = new AtomicLong();
    private final AtomicLong rxMalformedFrames = new AtomicLong();
    private final AtomicLong rxNegativeCompletions = new AtomicLong();
    private final AtomicLong rxIoExceptions = new AtomicLong();
    private volatile boolean closed;

    private UsbEcmTransport(UsbDeviceConnection connection, CdcEcmProfile profile,
                            CdcEcmBinder binder, Logger logger, String macAddress) {
        this.connection = connection;
        this.profile = profile;
        this.binder = binder;
        this.logger = logger;
        this.macAddress = macAddress;
    }

    public static UsbEcmTransport open(UsbManager manager, UsbDevice device, Logger logger) {
        if (manager == null || device == null) throw new IllegalArgumentException("USB manager/device is null");
        Logger sink = logger != null ? logger : message -> { };
        if (!manager.hasPermission(device)) {
            throw new IllegalStateException("USB permission is not granted for " + device.getDeviceName());
        }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IllegalStateException("UsbManager.openDevice returned null");

        CdcEcmBinder binder = null;
        try {
            CdcEcmProfile profile = new CdcDescriptorScanner(sink::log).scan(connection, device);
            if (profile == null) throw new IllegalStateException("No complete, unambiguous CDC-ECM function found");
            binder = new CdcEcmBinder(connection, profile, sink::log);
            if (!binder.bind()) throw new IllegalStateException("CDC-ECM bind sequence failed");

            String rawMac = readUsbString(connection, profile.macStringIndex, sink);
            if (!validMac(rawMac)) throw new IllegalStateException("CDC iMACAddress string is absent or invalid");
            String mac = canonicalMac(rawMac);

            sink.log("USB CDC-ECM transport ready config=" + profile.configurationValue +
                    " controlIF=" + profile.controlInterfaceNumber +
                    " dataIF=" + profile.dataInterfaceNumber +
                    " alt=" + profile.dataInterface.getAlternateSetting() +
                    " bulkIN=0x" + hex(profile.bulkIn.getAddress()) +
                    " bulkOUT=0x" + hex(profile.bulkOut.getAddress()) +
                    " maxSegment=" + profile.maxSegmentSize + " MAC=" + mac);
            return new UsbEcmTransport(connection, profile, binder, sink, mac);
        } catch (RuntimeException error) {
            if (binder != null) try { binder.close(); } catch (Exception ignored) { }
            try { connection.close(); } catch (Exception ignored) { }
            throw error;
        } catch (Exception error) {
            if (binder != null) try { binder.close(); } catch (Exception ignored) { }
            try { connection.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("CDC-ECM open failed", error);
        }
    }

    public static boolean hasControlInterfaceSignature(UsbDevice device) {
        if (device == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == 0x02 &&
                    iface.getInterfaceSubclass() == 0x06 &&
                    iface.getInterfaceProtocol() == 0x00) return true;
        }
        return device.getDeviceClass() == 0x02 &&
                device.getDeviceSubclass() == 0x06 &&
                device.getDeviceProtocol() == 0x00;
    }

    public int sendFrame(byte[] frame, int length, int timeoutMs) {
        if (closed || frame == null || length < 14 || length > frame.length) return -1;
        int maxFrame = profile.maxSegmentSize > 0 ? profile.maxSegmentSize : 1514;
        if (length > maxFrame) {
            txErrors.incrementAndGet();
            logger.log("TX rejected frame length=" + length + " CDC maxSegment=" + maxFrame);
            return -1;
        }
        int result;
        try {
            result = connection.bulkTransfer(profile.bulkOut, frame, length, Math.max(1, timeoutMs));
        } catch (RuntimeException error) {
            txErrors.incrementAndGet();
            logger.log("TX bulkTransfer exception: " + error);
            return -1;
        }
        if (result == length) {
            txFrames.incrementAndGet();
            txBytes.addAndGet(length);
        } else {
            txErrors.incrementAndGet();
            logger.log("TX incomplete result=" + result + "/" + length);
        }
        return result;
    }

    public int receiveFrame(byte[] target, int timeoutMs) {
        if (closed || target == null || target.length < 14) return -1;
        int requestLength = target.length;
        int maxPacket = profile.bulkIn.getMaxPacketSize();
        if (maxPacket > 0 && requestLength >= maxPacket) {
            requestLength -= requestLength % maxPacket;
            if (requestLength == 0) requestLength = maxPacket;
        }
        int result;
        try {
            result = connection.bulkTransfer(profile.bulkIn, target, requestLength, Math.max(1, timeoutMs));
        } catch (RuntimeException error) {
            rxIoExceptions.incrementAndGet();
            logger.log("RX bulkTransfer exception: " + error);
            return -1;
        }
        if (result > 0) {
            if (result >= 14) {
                rxFrames.incrementAndGet();
                rxBytes.addAndGet(result);
            } else {
                rxMalformedFrames.incrementAndGet();
            }
        } else if (result < 0) {
            // Android bulkTransfer reports timeout and I/O failures with the same negative result.
            // Count this as a negative completion, not as a confirmed USB error.
        }
        return result;
    }

    public boolean isOpen() { return !closed; }
    public String getMacAddress() { return macAddress; }
    public int getFileDescriptor() { return connection.getFileDescriptor(); }
    public int getConfigurationValue() { return profile.configurationValue; }
    public int getControlInterfaceNumber() { return profile.controlInterfaceNumber; }
    public int getDataInterfaceNumber() { return profile.dataInterfaceNumber; }
    public int getDataAlternateSetting() { return profile.dataInterface.getAlternateSetting(); }
    public int getBulkInAddress() { return profile.bulkIn.getAddress(); }
    public int getBulkOutAddress() { return profile.bulkOut.getAddress(); }
    public int getBulkInMaxPacketSize() { return profile.bulkIn.getMaxPacketSize(); }
    public int getBulkOutMaxPacketSize() { return profile.bulkOut.getMaxPacketSize(); }
    public int getMaxSegmentSize() { return profile.maxSegmentSize; }
    public long getTxFrames() { return txFrames.get(); }
    public long getRxFrames() { return rxFrames.get(); }
    public long getTxBytes() { return txBytes.get(); }
    public long getRxBytes() { return rxBytes.get(); }
    public long getTxErrors() { return txErrors.get(); }
    public long getRxMalformedFrames() { return rxMalformedFrames.get(); }
    public long getRxNegativeCompletions() { return rxNegativeCompletions.get(); }
    public long getRxIoExceptions() { return rxIoExceptions.get(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { binder.close(); } catch (Exception error) { logger.log("CDC binder close failed: " + error); }
        try { connection.close(); } catch (Exception error) { logger.log("USB connection close failed: " + error); }
        logger.log("USB CDC-ECM transport closed");
    }

    private static String readUsbString(UsbDeviceConnection connection, int index, Logger logger) {
        if (index <= 0) return null;
        byte[] buffer = new byte[256];
        int result = connection.controlTransfer(0x80, 0x06, 0x0300 | index, 0x0409,
                buffer, buffer.length, 1500);
        if (result < 4 || (buffer[1] & 0xFF) != 3) {
            logger.log("GET_DESCRIPTOR STRING index=" + index + " result=" + result);
            return null;
        }
        int length = Math.min(buffer[0] & 0xFF, result);
        StringBuilder out = new StringBuilder();
        for (int offset = 2; offset + 1 < length; offset += 2) {
            out.append((char) ((buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)));
        }
        return out.toString();
    }

    private static boolean validMac(String value) {
        if (value == null) return false;
        String normalized = value.replace(":", "").replace("-", "");
        if (!normalized.matches("(?i)[0-9a-f]{12}")) return false;
        byte[] mac = new byte[6];
        for (int i = 0; i < mac.length; i++) {
            mac[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        if ((mac[0] & 1) != 0) return false;
        boolean allZero = true;
        for (byte valueByte : mac) allZero &= valueByte == 0;
        return !allZero;
    }

    private static String canonicalMac(String value) {
        String normalized = value.replace(":", "").replace("-", "").toUpperCase(java.util.Locale.US);
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i != 0) out.append(':');
            out.append(normalized, i * 2, i * 2 + 2);
        }
        return out.toString();
    }

    private static String hex(int value) {
        return String.format(java.util.Locale.US, "%02X", value & 0xFF);
    }
}
