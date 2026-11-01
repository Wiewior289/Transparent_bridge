package com.example.usbprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental CDC-ECM userland replay.
 *
 * Replays the CDC/usbnet host sequence for this adapter using Android USB Host:
 * - claim the CDC control + CDC data interfaces with force=true (detach cdc_ether)
 * - select the CDC data alternate setting and bulk endpoints
 * - CDC SET_ETHERNET_PACKET_FILTER
 * - bounded software RX delivery queue fed by a synchronous bulk-IN worker
 * - bounded TX frame queue and usbnet-inspired maxpacket length policy
 *
 * This is deliberately not a complete Linux usbnet implementation: public Android
 * USB Host does not expose URBs, anchors, tasklets, netdev, or kernel PM primitives.
 */
public final class MainActivity extends Activity {
    private static final String ACTION_PERMISSION = "com.example.usbprobe.USB_PERMISSION";
    private static final int ETH_HLEN = 14;
    private static final int RX_URB_SIZE = 16384;
    private static final int USBNET_DEFAULT_RX_QLEN = 4;
    private static final int RX_QUEUE_DEPTH = 4;
    private static final int TX_QUEUE_DEPTH = 16;

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService rxExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean rxRunning = new AtomicBoolean(false);
    private final AtomicBoolean txWorkerStarted = new AtomicBoolean(false);
    private final ArrayBlockingQueue<byte[]> txQueue = new ArrayBlockingQueue<>(TX_QUEUE_DEPTH);
    private final ArrayBlockingQueue<byte[]> rxQueue = new ArrayBlockingQueue<>(RX_QUEUE_DEPTH);
    private final Object connectionLock = new Object();

    private UsbManager manager;
    private TextView output;
    private UsbDevice selected;
    private UsbDeviceConnection connection;
    private UsbInterface controlInterface;
    private UsbInterface dataInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private CdcEcmBinder binder;
    private final List<UsbDevice> scanCandidates = new ArrayList<>();
    private int nextCandidateIndex;
    private int maxPacket;
    private int maxPacketIn;
    private int maxSegmentSize = 1514;
    private String macAddress;
    private volatile boolean configured;
    private volatile boolean txRunning;
    private volatile int rxSubmitted;
    private volatile int rxCompleted;
    private volatile int rxErrors;
    private volatile int txSubmitted;
    private volatile int txCompleted;
    private volatile int txErrors;
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERMISSION.equals(intent.getAction())) return;
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            log("USB_PERMISSION granted=" + granted);
            if (granted && selected != null) controlExecutor.execute(() -> openAndConfigure(selected));
            else controlExecutor.execute(MainActivity.this::tryNextCandidate);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (UsbManager) getSystemService(USB_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(12, 12, 12, 12);
        root.setOrientation(LinearLayout.VERTICAL);
        addButton(root, "1. Scan / acquire / replay CDC bind", v -> scan());
        addButton(root, "2. START usbnet-style RX queue", v -> startRx());
        addButton(root, "3. TX: CDC Ethernet link-test frame", v -> enqueueLinkTestFrame());
        addButton(root, "4. RX: read next queued Ethernet frame", v -> showNextRx());
        addButton(root, "Stop RX/TX queues", v -> stopQueues());
        addButton(root, "Release interfaces / close USB", v -> controlExecutor.execute(this::closeDevice));
        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter);
        receiverRegistered = true;
        log("CDC-ECM userland replay ready; discovery uses CDC descriptors, not a VID/PID allowlist.");
        log("This application detaches cdc_ether via claimInterface(force=true); eth0 disappearance is expected.");
        log("No CH9200 vendor commands are sent. Driver match is generic CDC class/subclass/protocol.");
    }

    private void addButton(LinearLayout root, String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(click);
        root.addView(button);
    }

    private void scan() {
        scanCandidates.clear();
        scanCandidates.addAll(manager.getDeviceList().values());
        nextCandidateIndex = 0;
        log("USB scan candidates=" + scanCandidates.size());
        if (scanCandidates.isEmpty()) {
            log("No USB devices attached.");
            return;
        }
        tryNextCandidate();
    }

    private void tryNextCandidate() {
        while (nextCandidateIndex < scanCandidates.size()) {
            UsbDevice device = scanCandidates.get(nextCandidateIndex++);
            selected = device;
            log(String.format(Locale.US, "CDC candidate %04X:%04X %s configs=%d",
                    device.getVendorId(), device.getProductId(), device.getDeviceName(),
                    device.getConfigurationCount()));
            if (manager.hasPermission(device)) {
                controlExecutor.execute(() -> openAndConfigure(device));
            } else {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                manager.requestPermission(device,
                        PendingIntent.getBroadcast(this, 0, new Intent(ACTION_PERMISSION), flags));
                log("USB host permission requested for candidate.");
            }
            return;
        }
        selected = null;
        log("No attached device exposed a complete, unambiguous CDC-ECM function.");
    }

    private void openAndConfigure(UsbDevice device) {
        closeDevice();
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) {
            log("OPEN FAILED; trying next USB candidate.");
            tryNextCandidate();
            return;
        }
        synchronized (connectionLock) { connection = c; }
        log("OPEN OK fd=" + c.getFileDescriptor());

        CdcEcmProfile profile;
        try {
            profile = new CdcDescriptorScanner(this::log).scan(c, device);
        } catch (Exception e) {
            log("CDC descriptor scan failed: " + e);
            closeDevice();
            tryNextCandidate();
            return;
        }
        if (profile == null) {
            log("No complete, unambiguous CDC-ECM function on this device. STOP.");
            closeDevice();
            tryNextCandidate();
            return;
        }

        controlInterface = profile.controlInterface;
        dataInterface = profile.dataInterface;
        bulkIn = profile.bulkIn;
        bulkOut = profile.bulkOut;
        maxPacket = bulkOut.getMaxPacketSize();
        maxPacketIn = bulkIn.getMaxPacketSize();
        maxSegmentSize = profile.maxSegmentSize > ETH_HLEN ? profile.maxSegmentSize : 1514;
        macAddress = profile.macStringIndex > 0 ? readUsbString(c, profile.macStringIndex) : null;

        log("CDC PROFILE configValue=" + profile.configurationValue +
                " bcdCDC=" + String.format(Locale.US, "%04X", profile.bcdCdc) +
                " control=" + profile.controlInterfaceNumber +
                " data=" + profile.dataInterfaceNumber +
                " iMAC=" + profile.macStringIndex +
                " maxSegment=" + profile.maxSegmentSize);
        log("CDC control IF id=" + controlInterface.getId() +
                " alt=" + controlInterface.getAlternateSetting() +
                " interruptEPs=" + controlInterface.getEndpointCount());
        log("CDC data IF id=" + dataInterface.getId() +
                " alt=" + dataInterface.getAlternateSetting() +
                " bulk IN=0x" + hex(bulkIn.getAddress()) +
                " OUT=0x" + hex(bulkOut.getAddress()) +
                " wMaxPacketSize OUT=" + maxPacket + " IN=" + maxPacketIn +
                " maxSegment=" + maxSegmentSize);
        log("CDC iMACAddress string=" + (macAddress == null ? "(unavailable)" : macAddress));
        log("CONFIG NOTE: Android public USB Host APIs do not expose a reliable active-configuration query here; " +
                "scanner resolved descriptor configurationValue=" + profile.configurationValue +
                ". Claim/setInterface results remain the runtime test.");

        binder = new CdcEcmBinder(c, profile, this::log);
        if (!binder.bind()) {
            closeDevice();
            tryNextCandidate();
            return;
        }

        configured = true;
        log("CDC BIND READY. This confirms descriptor validation and control setup, not successful Ethernet RX/TX.");
        log("API-level claim success is not independent sysfs proof of usbfs ownership.");
        startTxWorker();
    }

    private String readUsbString(UsbDeviceConnection c, int index) {
        byte[] buffer = new byte[256];
        int result = c.controlTransfer(0x80, 0x06, 0x0300 | index, 0x0409,
                buffer, buffer.length, 1500);
        if (result < 4 || u8(buffer, 1) != 3) {
            log("GET_DESCRIPTOR STRING index=" + index + " result=" + result);
            return null;
        }
        int length = Math.min(u8(buffer, 0), result);
        StringBuilder out = new StringBuilder();
        for (int p = 2; p + 1 < length; p += 2)
            out.append((char)(u8(buffer, p) | (u8(buffer, p + 1) << 8)));
        String value = out.toString();
        log("USB string index=" + index + " -> " + value);
        return value;
    }

    private void startTxWorker() {
        if (!configured) return;
        txRunning = true;
        if (!txWorkerStarted.compareAndSet(false, true)) return;
        txExecutor.execute(() -> {
            try {
                while (txRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] frame = txQueue.poll(500, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        UsbDeviceConnection c = connection;
                        UsbEndpoint endpoint = bulkOut;
                        if (!configured || c == null || endpoint == null) continue;
                        byte[] usbPayload = applyUsbnetTxLengthPolicy(frame);
                        int result = c.bulkTransfer(endpoint, usbPayload, usbPayload.length, 5000);
                        txSubmitted++;
                        if (result == usbPayload.length) txCompleted++;
                        else {
                            txErrors++;
                            log("TX completion short/error result=" + result + "/" + usbPayload.length);
                        }
                        log("TX submit frame=" + frame.length + " USB-transfer=" + usbPayload.length +
                                " result=" + result + " maxpacket=" + maxPacket +
                                " policy=" + (usbPayload.length == frame.length ? "unchanged" : "usbnet-pad-byte"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        txErrors++;
                        log("TX exception: " + e);
                    }
                }
            } finally {
                txWorkerStarted.set(false);
                // A reconnect can race the old worker's final transfer. If the link
                // is ready again, make sure a worker exists for the new session.
                if (configured && txRunning) startTxWorker();
            }
        });
    }

    /**
     * usbnet_start_xmit(): only pad a full maxpacket-multiple if the kernel driver
     * does not use FLAG_SEND_ZLP. The generic CDC driver_info for this device must
     * be checked to establish that flag. In our userland profile, use one-byte
     * padding only when the adapter's binding path is confirmed to need it.
     *
     * For the captured adapter, 60-byte frames and maxpacket=512 do not hit this case.
     */
    private byte[] applyUsbnetTxLengthPolicy(byte[] frame) {
        if (maxPacket <= 0 || frame.length % maxPacket != 0) return frame;
        // The generic CDC binding shown in cdc_ether.c does not set a per-device
        // FLAG_SEND_ZLP here. usbnet core therefore adds one byte on this path.
        return Arrays.copyOf(frame, frame.length + 1);
    }

    private void enqueueLinkTestFrame() {
        if (!configured) { log("TX blocked: CDC bind not complete."); return; }
        byte[] frame = createArpWhoHasFrame();
        if (frame == null) return;
        startTxWorker();
        if (!txQueue.offer(frame)) { log("TX queue full; frame not submitted."); return; }
        log("TX queued kernel-style Ethernet ARP who-has frame len=" + frame.length +
                " sourceMAC=" + macAddress);
    }

    /**
     * A protocol-valid Ethernet/ARP request (not a random EtherType payload):
     * destination broadcast, source adapter MAC, EtherType ARP, Ethernet/IPv4,
     * operation request. It uses a stable, documented test target 192.0.2.1.
     */
    private byte[] createArpWhoHasFrame() {
        byte[] source = parseMac(macAddress);
        if (source == null) {
            log("TX blocked: CDC iMACAddress string unavailable/invalid; kernel usbnet_cdc_bind() would fail MAC acquisition.");
            return null;
        }
        byte[] f = new byte[60];
        Arrays.fill(f, 0, 6, (byte)0xFF);
        System.arraycopy(source, 0, f, 6, 6);
        f[12] = 0x08; f[13] = 0x06; // EtherType ARP
        f[14] = 0x00; f[15] = 0x01; // Ethernet
        f[16] = 0x08; f[17] = 0x00; // IPv4
        f[18] = 0x06; f[19] = 0x04;
        f[20] = 0x00; f[21] = 0x01; // request
        System.arraycopy(source, 0, f, 22, 6);
        // Sender protocol address 0.0.0.0, target protocol address 192.0.2.1.
        f[28] = 0; f[29] = 0; f[30] = 0; f[31] = 0;
        f[38] = (byte)192; f[39] = 0; f[40] = 2; f[41] = 1;
        return f;
    }

    private byte[] parseMac(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{12}")) return null;
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private void startRx() {
        if (!configured) { log("RX blocked: CDC bind not complete."); return; }
        if (!rxRunning.compareAndSet(false, true)) { log("RX queue already running."); return; }
        if (rxQueue.isEmpty()) log("RX software delivery queue empty; starting receive refill.");
        rxSubmitted = rxCompleted = rxErrors = 0;
        rxExecutor.execute(() -> {
            log("RX queue started: Android synchronous emulation target-depth=" + USBNET_DEFAULT_RX_QLEN +
                    " buffer=" + RX_URB_SIZE + " bytes (USB bulk IN endpoint 0x" + hex(bulkIn.getAddress()) + ")");
            while (rxRunning.get()) {
                UsbDeviceConnection c = connection;
                UsbEndpoint endpoint = bulkIn;
                if (!configured || c == null || endpoint == null) break;
                byte[] transfer = new byte[RX_URB_SIZE];
                int result;
                try {
                    // Android exposes synchronous bulkTransfer, so a dedicated worker
                    // maintains a bounded set of posted receive buffers by pipelining
                    // at the application layer; true concurrent URBs are not exposed.
                    int requestLength = RX_URB_SIZE - (RX_URB_SIZE % Math.max(1, maxPacketIn));
                    result = c.bulkTransfer(endpoint, transfer, requestLength, 5000);
                    rxSubmitted++;
                    if (result > 0) {
                        rxCompleted++;
                        if (result < ETH_HLEN) {
                            rxErrors++;
                            log("RX discarded undersized transfer len=" + result);
                        } else {
                            byte[] frame = Arrays.copyOf(transfer, result);
                            if (!rxQueue.offer(frame)) {
                                rxErrors++;
                                log("RX queue full: drop frame len=" + result);
                            } else {
                                log("RX completed len=" + result + " ethertype=0x" +
                                        hex(((frame[12] & 255) << 8) | (frame[13] & 255)) +
                                        " queue=" + rxQueue.size());
                            }
                        }
                    } else if (result == 0) {
                        // zero-length completion is not an Ethernet frame
                    } else {
                        rxErrors++;
                        log("RX bulk completion result=" + result + " (timeout or USB error; public API hides errno)");
                    }
                } catch (Exception e) {
                    rxErrors++;
                    log("RX exception: " + e);
                    break;
                }
            }
            rxRunning.set(false);
            log("RX queue stopped submitted=" + rxSubmitted + " completed=" + rxCompleted + " errors=" + rxErrors);
        });
    }

    private void showNextRx() {
        byte[] frame = rxQueue.poll();
        if (frame == null) { log("RX queue empty."); return; }
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < Math.min(frame.length, 96); i++)
            bytes.append(String.format(Locale.US, "%02X ", frame[i] & 255));
        log("RX FRAME len=" + frame.length + " dst=" + mac(frame, 0) +
                " src=" + mac(frame, 6) + " EtherType=0x" +
                hex(((frame[12] & 255) << 8) | (frame[13] & 255)) +
                " bytes[0..]=" + bytes);
    }

    private String mac(byte[] frame, int offset) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i != 0) s.append(':');
            s.append(hex(frame[offset + i] & 255));
        }
        return s.toString();
    }

    private void stopQueues() {
        rxRunning.set(false);
        txRunning = false;
        txQueue.clear();
        log("Queue stop requested. TX worker will exit after any active bulk transfer returns.");
    }

    private void closeDevice() {
        configured = false;
        rxRunning.set(false);
        txRunning = false;
        txQueue.clear();
        UsbDeviceConnection c;
        synchronized (connectionLock) { c = connection; connection = null; }
        if (binder != null) {
            try { binder.close(); } catch (Exception e) { log("CDC binder close exception=" + e); }
            binder = null;
        }
        if (c != null) {
            try { c.close(); } catch (Exception e) { log("USB close exception=" + e); }
        }
        controlInterface = null; dataInterface = null; bulkIn = null; bulkOut = null;
        maxPacket = 0; maxPacketIn = 0; macAddress = null;
        log("USB closed; kernel may rebind cdc_ether after usbfs releases the interface/device.");
    }

    private void log(String message) {
        runOnUiThread(() -> {
            if (output != null) output.append(message + "\n");
            android.util.Log.i("CdcEtherUserland", message);
        });
    }

    private static int u8(byte[] bytes, int offset) { return bytes[offset] & 0xFF; }
    private static String hex(int value) { return String.format(Locale.US, "%02X", value & 0xFFFF); }

    @Override protected void onDestroy() {
        rxRunning.set(false);
        txRunning = false;
        controlExecutor.execute(this::closeDevice);
        controlExecutor.shutdown();
        txExecutor.shutdownNow();
        rxExecutor.shutdownNow();
        if (receiverRegistered) unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }
}
