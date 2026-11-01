package dev.transparentbridge.usbprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String TAG = "UsbProbe";
    private static final String ACTION_USB_PERMISSION =
            "dev.transparentbridge.usbprobe.USB_PERMISSION";

    // CDC Ethernet class requests (USB CDC ECM, USB CDC 1.1).
    private static final int USB_TYPE_CLASS = 0x20;
    private static final int RECIP_INTERFACE = 0x01;
    private static final int SET_ETHERNET_PACKET_FILTER = 0x43;
    private static final int PACKET_TYPE_PROMISCUOUS = 0x0001;
    private static final int PACKET_TYPE_ALL_MULTICAST = 0x0002;
    private static final int PACKET_TYPE_DIRECTED = 0x0004;
    private static final int PACKET_TYPE_BROADCAST = 0x0008;
    private static final int PACKET_TYPE_MULTICAST = 0x0010;

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbDevice activeDevice;
    private UsbInterface claimedInterface;
    private UsbInterface cdcControlInterface;
    private UsbInterface cdcDataInterface;
    private CdcDescriptors cdc;
    private boolean cdcFilterConfigured = false;

    private Spinner deviceSpinner;
    private Spinner interfaceSpinner;
    private final List<UsbInterface> interfaces = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private final List<UsbDevice> devices = new ArrayList<>();
    private TextView output;

    private Button claimButton;
    private Button setInterfaceButton;
    private Button cdcFilterButton;
    private Button releaseButton;
    private Button bulkInButton;
    private Button bulkOutButton;
    private final AtomicBoolean transferBusy = new AtomicBoolean(false);
    private boolean receiverRegistered = false;

    private static final class CdcDescriptors {
        int controlInterface = -1;
        int dataInterface = -1;
        int ethernetControlInterface = -1;
        int macStringIndex = -1;
        int maxSegmentSize = -1;
        long multicastFilterCount = -1;
        int capabilities = -1;
        boolean header;
        boolean union;
        boolean ethernet;
        boolean ecm;
        String mac = "";
        String summary() {
            return "CDC header=" + header + " union=" + union
                    + " ethernet=" + ethernet + " ecm=" + ecm
                    + " controlIf=" + controlInterface + " dataIf=" + dataInterface
                    + " ethernetControlIf=" + ethernetControlInterface
                    + " macIndex=" + macStringIndex + " maxSegment=" + maxSegmentSize
                    + " multicastFilters=" + multicastFilterCount
                    + " capabilities=0x" + hexStatic(capabilities)
                    + (mac.isEmpty() ? "" : " mac=" + mac);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("PERMISSION result=" + granted + " device=" + describe(device));
                if (granted && device != null) openDevice(device);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB ATTACHED " + describe(getUsbDevice(intent)));
                refreshDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                log("USB DETACHED " + describe(device));
                if (activeDevice != null && device != null
                        && activeDevice.getDeviceId() == device.getDeviceId()) {
                    closeConnection("device detached");
                }
                refreshDevices();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;

        log("START sdk=" + Build.VERSION.SDK_INT + " manufacturer="
                + Build.MANUFACTURER + " model=" + Build.MODEL
                + " build=" + Build.DISPLAY);
        refreshDevices();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);

        TextView title = new TextView(this);
        title.setText("USB Probe · USB Host / CDC Ethernet");
        title.setTextSize(20);
        root.addView(title);

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner);

        addButton(root, "Refresh USB", v -> refreshDevices());
        addButton(root, "Open selected / request permission", v -> requestSelectedDevice());

        TextView interfaceLabel = new TextView(this);
        interfaceLabel.setText("Interface descriptor (index / ID / alternate setting)");
        root.addView(interfaceLabel);

        interfaceSpinner = new Spinner(this);
        interfaceSpinner.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<>()));
        root.addView(interfaceSpinner);

        claimButton = addButton(root,
                "CLAIM selected interface (force=true)", v -> claimSelectedInterface());
        setInterfaceButton = addButton(root,
                "SET_INTERFACE on claimed descriptor (manual)", v -> setClaimedAlternateSetting());
        cdcFilterButton = addButton(root,
                "CDC: SET_ETHERNET_PACKET_FILTER (manual)", v -> sendCdcPacketFilter());
        releaseButton = addButton(root, "Release / close", v -> closeConnection("manual release"));
        bulkInButton = addButton(root, "Bulk IN: read once (1500 ms)", v -> runBulkInOnce());
        bulkOutButton = addButton(root, "Bulk OUT: send lab frame (0x88B5)",
                v -> confirmBulkOut());
        addButton(root, "Clear log", v -> output.setText(""));

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        updateButtons();
    }

    private Button addButton(LinearLayout parent, String label,
                             View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button);
        return button;
    }

    private void refreshDevices() {
        Map<String, UsbDevice> found = usbManager.getDeviceList();
        devices.clear();
        deviceAdapter.clear();
        for (UsbDevice device : found.values()) {
            devices.add(device);
            deviceAdapter.add(String.format("%04X:%04X deviceId=%d %s",
                    device.getVendorId(), device.getProductId(),
                    device.getDeviceId(), device.getDeviceName()));
            log("DISCOVER " + describe(device));
            dumpDescriptors(device);
        }
        deviceAdapter.notifyDataSetChanged();
        log("USB device count=" + devices.size());
    }

    private void requestSelectedDevice() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            log("OPEN skipped: no USB device selected");
            return;
        }
        UsbDevice device = devices.get(pos);
        if (usbManager.hasPermission(device)) {
            log("USB permission already granted: " + describe(device));
            openDevice(device);
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(
                this, device.getDeviceId(), intent, flags);
        log("USB permission request: " + describe(device));
        usbManager.requestPermission(device, pending);
    }

    private void openDevice(UsbDevice device) {
        closeConnection("switch device");
        UsbDeviceConnection opened = usbManager.openDevice(device);
        if (opened == null) {
            log("OPEN FAILED: UsbManager.openDevice returned null");
            return;
        }
        connection = opened;
        activeDevice = device;
        log("OPEN OK fd=" + opened.getFileDescriptor() + " " + describe(device));
        dumpDescriptors(device);
        populateInterfaceChoices(device);
        updateButtons();
    }

    private void populateInterfaceChoices(UsbDevice device) {
        interfaces.clear();
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> adapter =
                (ArrayAdapter<String>) interfaceSpinner.getAdapter();
        adapter.clear();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            interfaces.add(intf);
            adapter.add(String.format(
                    "index=%d id=%d alt=%d class=0x%s sub=0x%s proto=0x%s eps=%d",
                    i, intf.getId(), intf.getAlternateSetting(),
                    hex(intf.getInterfaceClass()), hex(intf.getInterfaceSubclass()),
                    hex(intf.getInterfaceProtocol()), intf.getEndpointCount()));
        }
        adapter.notifyDataSetChanged();
        if (!interfaces.isEmpty()) interfaceSpinner.setSelection(0);
        log("INTERFACE_CHOICES=" + interfaces.size()
                + "; choose explicitly; no interface is claimed automatically");
    }

    private void dumpDescriptors(UsbDevice device) {
        log("DEVICE " + describe(device) + " configurations="
                + device.getConfigurationCount() + " apiInterfaces="
                + device.getInterfaceCount());
        log("CONFIG: enumeration only; setConfiguration() is not called");
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration config = device.getConfiguration(c);
            log(" CONFIG index=" + c + " id=" + config.getId()
                    + " interfaces=" + config.getInterfaceCount()
                    + " maxPowerMilliAmps=" + config.getMaxPower());
            for (int i = 0; i < config.getInterfaceCount(); i++) {
                logInterfaceDescriptor("CONFIG[" + c + "].IF[" + i + "]",
                        config.getInterface(i));
            }
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            logInterfaceDescriptor("API_FLAT[" + i + "]", device.getInterface(i));
        }
    }

    private void logInterfaceDescriptor(String prefix, UsbInterface intf) {
        log(" " + prefix + " id=" + intf.getId() + " alt="
                + intf.getAlternateSetting() + " class=0x"
                + hex(intf.getInterfaceClass()) + " subclass=0x"
                + hex(intf.getInterfaceSubclass()) + " protocol=0x"
                + hex(intf.getInterfaceProtocol()) + " endpoints="
                + intf.getEndpointCount());
        for (int j = 0; j < intf.getEndpointCount(); j++) {
            UsbEndpoint ep = intf.getEndpoint(j);
            log("  EP address=0x" + hex(ep.getAddress())
                    + " dir=" + (ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                    + " type=" + transferType(ep.getType())
                    + " maxPacket=" + ep.getMaxPacketSize());
        }
    }

    private void claimSelectedInterface() {
        if (connection == null || activeDevice == null) {
            log("CLAIM skipped: open USB device first");
            return;
        }
        if (claimedInterface != null) {
            log("CLAIM skipped: already holding id=" + claimedInterface.getId()
                    + "/alt" + claimedInterface.getAlternateSetting()
                    + "; use Release / close first");
            return;
        }
        int pos = interfaceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= interfaces.size()) {
            log("CLAIM skipped: choose an interface descriptor");
            return;
        }

        UsbInterface candidate = interfaces.get(pos);
        log("CLAIM attempt index=" + pos + " id=" + candidate.getId()
                + " alt=" + candidate.getAlternateSetting()
                + " class=0x" + hex(candidate.getInterfaceClass())
                + " force=true");
        boolean ok = connection.claimInterface(candidate, true);
        log("CLAIM result=" + ok);
        if (!ok) return;

        claimedInterface = candidate;
        log("CLAIM STATE: claimed descriptor retained. No SET_INTERFACE, CDC request,"
                + " BULK transfer or Android network operation was chained.");
        log("KERNEL DRIVER DETACH: force=true asks Android USB host stack to detach"
                + " a kernel driver where supported. It does NOT prove cdc_ether/eth0"
                + " disappeared; verify independently in kernel/system logs.");
        updateButtons();
    }

    private void setClaimedAlternateSetting() {
        if (connection == null || claimedInterface == null) {
            log("SET_INTERFACE skipped: claim an interface first");
            return;
        }
        int alt = claimedInterface.getAlternateSetting();
        log("SET_INTERFACE attempt id=" + claimedInterface.getId() + " alt=" + alt);
        if (alt == 0) {
            log("SET_INTERFACE skipped: selected descriptor is alt=0");
            return;
        }
        boolean ok = connection.setInterface(claimedInterface);
        log("SET_INTERFACE result=" + ok + " id=" + claimedInterface.getId()
                + " alt=" + alt);
        log("SET_INTERFACE was user-triggered; it was not called by CLAIM or CDC setup");
        updateButtons();
    }

    /*
     * Reads the active configuration's raw descriptor stream via standard GET_DESCRIPTOR.
     * This is deliberately done only after interface ownership was explicitly claimed.
     * Parsing is bounded by wTotalLength and descriptor bLength values.
     */
    private CdcDescriptors parseCdcDescriptors() {
        if (connection == null || activeDevice == null) return null;
        byte[] header = new byte[9];
        int n = connection.controlTransfer(0x80, 0x06, 0x0200,
                0, header, header.length, 1000);
        if (n < 9 || (header[1] & 0xff) != 2) {
            log("CDC DESC: GET_DESCRIPTOR(CONFIG header) failed result=" + n);
            return null;
        }
        int total = u16(header, 2);
        if (total < 9 || total > 4096) {
            log("CDC DESC: unreasonable wTotalLength=" + total);
            return null;
        }
        byte[] raw = new byte[total];
        int got = connection.controlTransfer(0x80, 0x06, 0x0200,
                0, raw, raw.length, 1500);
        if (got < 9) {
            log("CDC DESC: full config read failed result=" + got);
            return null;
        }
        CdcDescriptors result = new CdcDescriptors();
        int limit = Math.min(got, total);
        int currentInterface = -1;
        for (int off = 0; off + 2 <= limit;) {
            int len = raw[off] & 0xff;
            int type = raw[off + 1] & 0xff;
            if (len < 2 || off + len > limit) {
                log("CDC DESC: malformed descriptor offset=" + off + " len=" + len);
                return null;
            }
            if (type == 4 && len >= 9) {
                currentInterface = raw[off + 2] & 0xff;
            } else if (type == 0x24 && len >= 3) {
                int subtype = raw[off + 2] & 0xff;
                if (subtype == 0x00 && len >= 5) {
                    result.header = true;
                    log("CDC DESC HEADER bcdCDC=0x"
                            + hex(u16(raw, off + 3)));
                } else if (subtype == 0x06 && len >= 5) {
                    result.union = true;
                    result.controlInterface = raw[off + 3] & 0xff;
                    result.dataInterface = raw[off + 4] & 0xff;
                    log("CDC DESC UNION master=" + result.controlInterface
                            + " slave=" + result.dataInterface);
                } else if (subtype == 0x0F && len >= 13) {
                    result.ethernet = true;
                    result.ethernetControlInterface = currentInterface;
                    result.macStringIndex = raw[off + 3] & 0xff;
                    result.macStringIndex = result.macStringIndex == 0 ? -1 : result.macStringIndex;
                    result.macStringIndex = result.macStringIndex < 0 ? 0 : result.macStringIndex;
                    result.maxSegmentSize = u16(raw, off + 8);
                    result.multicastFilterCount = u16(raw, off + 10);
                    result.capabilities = raw[off + 12] & 0xff;
                    log("CDC DESC ETHERNET if=" + currentInterface
                            + " macIndex=" + result.macStringIndex
                            + " maxSegment=" + result.maxSegmentSize
                            + " multicastFilters=" + result.multicastFilterCount
                            + " capabilities=0x" + hex(result.capabilities));
                } else if (subtype == 0x0F) {
                    log("CDC DESC ETHERNET malformed len=" + len);
                }
            } else if (type == 0x0A && len >= 3) {
                int subclass = raw[off + 2] & 0xff;
                if (subclass == 0x06) result.ecm = true;
            }
            off += len;
        }
        if (!result.header || !result.union || !result.ethernet) {
            log("CDC DESC incomplete: " + result.summary());
            return result;
        }
        log("CDC DESC parsed: " + result.summary());
        return result;
    }

    private void sendCdcPacketFilter() {
        if (connection == null || activeDevice == null || claimedInterface == null) {
            log("CDC FILTER skipped: open device and CLAIM an interface first");
            return;
        }
        if (transferBusy.get()) {
            log("CDC FILTER skipped: USB transfer is active");
            return;
        }
        if (cdcFilterConfigured) {
            log("CDC FILTER skipped: request already sent in this connection; close/reopen to repeat");
            return;
        }

        // The CDC Union descriptor determines the control interface number.
        // The data interface is not the recipient of SET_ETHERNET_PACKET_FILTER.
        CdcDescriptors parsed = parseCdcDescriptors();
        cdc = parsed;
        if (parsed == null || !parsed.header || !parsed.union || !parsed.ethernet) {
            log("CDC FILTER aborted: required CDC Header/Union/Ethernet descriptors not verified");
            return;
        }
        int controlIf = parsed.ethernetControlInterface >= 0
                ? parsed.ethernetControlInterface : parsed.controlInterface;
        if (controlIf < 0 || controlIf > 255) {
            log("CDC FILTER aborted: invalid CDC control interface=" + controlIf);
            return;
        }
        int value = PACKET_TYPE_DIRECTED | PACKET_TYPE_BROADCAST;
        log("CDC FILTER SET_ETHERNET_PACKET_FILTER bmRequestType=0x21"
                + " bRequest=0x43 wValue=0x" + hex(value)
                + " wIndex=" + controlIf + " wLength=0");
        int result = connection.controlTransfer(
                USB_TYPE_CLASS | RECIP_INTERFACE, SET_ETHERNET_PACKET_FILTER,
                value, controlIf, new byte[0], 0, 1500);
        log("CDC FILTER result=" + result
                + (result >= 0 ? " note=control_request_accepted_by_USB_stack_not_proof_of_ECM_data_path"
                : " note=control_request_failed_or_timed_out"));
        if (result >= 0) cdcFilterConfigured = true;
        updateButtons();
    }

    private void closeConnection(String reason) {
        if (connection != null) {
            log("CLOSE begin reason=" + reason + " claimed="
                    + (claimedInterface == null ? "none" : claimedInterface.getId()
                    + "/alt" + claimedInterface.getAlternateSetting()));
            if (claimedInterface != null) {
                try {
                    boolean released = connection.releaseInterface(claimedInterface);
                    log("RELEASE result=" + released);
                } catch (Exception e) {
                    log("RELEASE exception=" + e);
                }
            }
            try { connection.close(); }
            catch (Exception e) { log("CLOSE exception=" + e); }
        }
        connection = null;
        activeDevice = null;
        claimedInterface = null;
        cdc = null;
        cdcFilterConfigured = false;
        interfaces.clear();
        if (interfaceSpinner != null && interfaceSpinner.getAdapter() != null) {
            ((ArrayAdapter<?>) interfaceSpinner.getAdapter()).clear();
        }
        updateButtons();
    }

    private UsbEndpoint findBulkEndpoint(UsbInterface intf, int direction) {
        if (intf == null) return null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == direction) return ep;
        }
        return null;
    }

    private void runBulkInOnce() {
        if (connection == null || claimedInterface == null) {
            log("BULK_IN skipped: open and claim intended interface first");
            return;
        }
        final UsbDeviceConnection conn = connection;
        final UsbEndpoint ep = findBulkEndpoint(
                claimedInterface, UsbConstants.USB_DIR_IN);
        if (ep == null) {
            log("BULK_IN skipped: no bulk IN endpoint on claimed descriptor");
            return;
        }
        if (!transferBusy.compareAndSet(false, true)) {
            log("TRANSFER skipped: another USB operation is active");
            return;
        }
        updateButtons();
        log("BULK_IN begin ep=0x" + hex(ep.getAddress()) + " bytes=4096 timeoutMs=1500");
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            long start = SystemClock.elapsedRealtime();
            try {
                int result = conn.bulkTransfer(ep, buffer, buffer.length, 1500);
                long elapsed = SystemClock.elapsedRealtime() - start;
                StringBuilder sample = new StringBuilder();
                int shown = Math.min(Math.max(result, 0), 64);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sample.append(' ');
                    sample.append(hex(buffer[i] & 0xff));
                }
                String detail = "BULK_IN result=" + result + " elapsedMs=" + elapsed
                        + (result > 0 ? " firstBytes[" + shown + "]=" + sample : "")
                        + (result < 0 ? " note=timeout_or_USB_error_errno_unavailable" : "");
                runOnUiThread(() -> log(detail));
            } catch (Exception e) {
                runOnUiThread(() -> log("BULK_IN exception=" + e));
            } finally {
                transferBusy.set(false);
                runOnUiThread(this::updateButtons);
            }
        }, "usb-probe-bulk-in").start();
    }

    private void confirmBulkOut() {
        if (connection == null || claimedInterface == null) {
            log("BULK_OUT skipped: claim interface first");
            return;
        }
        if (findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_OUT) == null) {
            log("BULK_OUT skipped: no bulk OUT endpoint");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Transmit one diagnostic Ethernet frame?")
                .setMessage("One 60-byte broadcast frame, EtherType 0x88B5. "
                        + "No IP/ARP/DHCP. Use only on an isolated lab segment.")
                .setNegativeButton("Cancel", (d, w) -> log("BULK_OUT cancelled"))
                .setPositiveButton("Send", (d, w) -> runBulkOutFrame())
                .show();
    }

    private void runBulkOutFrame() {
        if (connection == null || claimedInterface == null) {
            log("BULK_OUT skipped: USB connection/interface unavailable");
            return;
        }
        final UsbDeviceConnection conn = connection;
        final UsbEndpoint ep = findBulkEndpoint(
                claimedInterface, UsbConstants.USB_DIR_OUT);
        if (ep == null) return;
        if (!transferBusy.compareAndSet(false, true)) {
            log("TRANSFER skipped: another USB operation is active");
            return;
        }
        updateButtons();
        final byte[] frame = makeDiagnosticEthernetFrame();
        log("BULK_OUT begin ep=0x" + hex(ep.getAddress())
                + " bytes=" + frame.length + " ethertype=0x88B5");
        new Thread(() -> {
            long start = SystemClock.elapsedRealtime();
            try {
                int result = conn.bulkTransfer(ep, frame, frame.length, 1500);
                long elapsed = SystemClock.elapsedRealtime() - start;
                runOnUiThread(() -> log("BULK_OUT result=" + result + "/"
                        + frame.length + " elapsedMs=" + elapsed
                        + (result == frame.length
                        ? " note=host_API_accepted_buffer_not_remote_delivery" : "")));
            } catch (Exception e) {
                runOnUiThread(() -> log("BULK_OUT exception=" + e));
            } finally {
                transferBusy.set(false);
                runOnUiThread(this::updateButtons);
            }
        }, "usb-probe-bulk-out").start();
    }

    private byte[] makeDiagnosticEthernetFrame() {
        byte[] frame = new byte[60];
        for (int i = 0; i < 6; i++) frame[i] = (byte) 0xff;
        byte[] source = new byte[]{0x02, (byte) 0xce, 0x5a, 0, 0, 1};
        System.arraycopy(source, 0, frame, 6, source.length);
        frame[12] = (byte) 0x88;
        frame[13] = (byte) 0xb5;
        byte[] tag = "USBPROBE-TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, frame, 14, tag.length);
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) frame[27 + i] = (byte) (stamp >>> (56 - 8 * i));
        return frame;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static String hexStatic(int v) {
        return v < 0 ? "?" : String.format("%02X", v);
    }

    private String hex(int value) {
        return String.format("%02X", value);
    }

    private String transferType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISO";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INT";
            default: return "UNKNOWN(" + type + ")";
        }
    }

    private String describe(UsbDevice d) {
        if (d == null) return "null";
        return String.format("vid=0x%04X pid=0x%04X deviceId=%d name=%s",
                d.getVendorId(), d.getProductId(), d.getDeviceId(), d.getDeviceName());
    }

    private void log(String message) {
        String line = String.format("%tT.%<tL  %s", System.currentTimeMillis(), message);
        Log.i(TAG, line);
        if (output != null) output.append(line + "\n");
    }

    private void updateButtons() {
        boolean open = connection != null;
        boolean claimed = claimedInterface != null;
        if (claimButton != null) {
            claimButton.setEnabled(open && !claimed && !transferBusy.get()
                    && !interfaces.isEmpty());
        }
        if (setInterfaceButton != null) {
            setInterfaceButton.setEnabled(open && claimed && !transferBusy.get()
                    && claimedInterface.getAlternateSetting() != 0);
        }
        if (cdcFilterButton != null) {
            cdcFilterButton.setEnabled(open && claimed && !transferBusy.get()
                    && !cdcFilterConfigured);
        }
        if (releaseButton != null) {
            releaseButton.setEnabled(open && !transferBusy.get());
        }
        boolean canTransfer = open && claimed && !transferBusy.get();
        if (bulkInButton != null) {
            bulkInButton.setEnabled(canTransfer
                    && findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_IN) != null);
        }
        if (bulkOutButton != null) {
            bulkOutButton.setEnabled(canTransfer
                    && findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_OUT) != null);
        }
    }

    @Override protected void onDestroy() {
        closeConnection("activity destroyed");
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); }
            catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
