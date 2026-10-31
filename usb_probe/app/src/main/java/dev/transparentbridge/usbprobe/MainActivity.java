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
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String TAG = "UsbProbe";
    private static final String ACTION_USB_PERMISSION = "dev.transparentbridge.usbprobe.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbDevice activeDevice;
    private UsbInterface claimedInterface;
    private Spinner deviceSpinner;
    private Spinner interfaceSpinner;
    private final List<UsbInterface> interfaces = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private final List<UsbDevice> devices = new ArrayList<>();
    private TextView output;
    private Button claimButton;
    private Button setInterfaceButton;
    private Button releaseButton;
    private Button bulkInButton;
    private Button bulkOutButton;
    private final AtomicBoolean transferBusy = new AtomicBoolean(false);
    private boolean receiverRegistered = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("PERMISSION result=" + granted + " device=" + describe(device));
                if (granted && device != null) openDevice(device);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB ATTACHED " + describe(getUsbDevice(intent)));
                refreshDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                log("USB DETACHED " + describe(device));
                if (activeDevice != null && device != null &&
                        activeDevice.getDeviceId() == device.getDeviceId()) {
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
        log("START sdk=" + Build.VERSION.SDK_INT + " manufacturer=" + Build.MANUFACTURER
                + " model=" + Build.MODEL + " build=" + Build.DISPLAY);
        refreshDevices();
        logNetworkInterfaces();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);

        TextView title = new TextView(this);
        title.setText("USB Probe · Android USB Host API");
        title.setTextSize(20);
        root.addView(title);

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>());
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

        claimButton = addButton(root, "Claim SELECTED interface (force=true)", v -> claimSelectedInterface());
        setInterfaceButton = addButton(root, "Set alternate setting on CLAIMED interface", v -> setClaimedAlternateSetting());
        releaseButton = addButton(root, "Release / close", v -> closeConnection("manual release"));
        bulkInButton = addButton(root, "Bulk IN: read once (1500 ms)", v -> runBulkInOnce());
        bulkOutButton = addButton(root, "Bulk OUT: send lab frame (0x88B5)", v -> confirmBulkOut());
        addButton(root, "Snapshot network interfaces", v -> logNetworkInterfaces());
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

    private Button addButton(LinearLayout parent, String label, View.OnClickListener listener) {
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
            String row = String.format("%04X:%04X deviceId=%d %s",
                    device.getVendorId(), device.getProductId(),
                    device.getDeviceId(), device.getDeviceName());
            deviceAdapter.add(row);
            log("DISCOVER " + describe(device));
            dumpDescriptors(device);
        }
        deviceAdapter.notifyDataSetChanged();
        log("USB device count=" + devices.size());
    }

    private void requestSelectedDevice() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            log("No device selected");
            return;
        }
        UsbDevice device = devices.get(pos);
        if (usbManager.hasPermission(device)) {
            log("Permission already granted: " + describe(device));
            openDevice(device);
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(this, device.getDeviceId(), intent, flags);
        log("Requesting USB permission: " + describe(device));
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
        log("OPEN OK fd=" + opened.getFileDescriptor() + " device=" + describe(device));
        dumpDescriptors(device);
        populateInterfaceChoices(device);
        logNetworkInterfaces();
        updateButtons();
    }

    private void populateInterfaceChoices(UsbDevice device) {
        interfaces.clear();
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) interfaceSpinner.getAdapter();
        adapter.clear();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            interfaces.add(intf);
            String row = String.format("index=%d id=%d alt=%d class=0x%s eps=%d", i,
                    intf.getId(), intf.getAlternateSetting(), hex(intf.getInterfaceClass()),
                    intf.getEndpointCount());
            adapter.add(row);
        }
        adapter.notifyDataSetChanged();
        if (!interfaces.isEmpty()) interfaceSpinner.setSelection(0);
        log("INTERFACE_CHOICES count=" + interfaces.size()
                + " (select exact descriptor before claim; no automatic guess)");
    }

    private void dumpDescriptors(UsbDevice device) {
        log("DEVICE " + describe(device) + " configurations=" + device.getConfigurationCount()
                + " apiInterfaces=" + device.getInterfaceCount());
        log("CONFIG NOTE: enumerate all configurations; this diagnostic does NOT call setConfiguration().");
        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration config = device.getConfiguration(c);
            log(" CONFIG index=" + c + " id=" + config.getId()
                    + " name=" + config.getName()
                    + " interfaces=" + config.getInterfaceCount()
                    + " maxPowerMilliAmps=" + config.getMaxPower()
                    + " selfPowered=" + config.isSelfPowered()
                    + " remoteWakeup=" + config.isRemoteWakeup());
            for (int i = 0; i < config.getInterfaceCount(); i++) {
                UsbInterface intf = config.getInterface(i);
                logInterfaceDescriptor("CONFIG[" + c + "].IF[" + i + "]", intf);
            }
        }

        // UsbDevice's flattened interface list is what the current selection UI uses.
        // Keep it separate from configuration-indexed enumeration to avoid implying that
        // public UsbDeviceConnection exposes which configuration is currently active.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            logInterfaceDescriptor("API_FLAT[" + i + "]", intf);
        }
        log("CONFIG LIMIT: active configuration is not queried or changed by this probe; compare config grouping with API_FLAT entries.");
    }

    private void logInterfaceDescriptor(String prefix, UsbInterface intf) {
        log(" " + prefix + " id=" + intf.getId()
                + " alt=" + intf.getAlternateSetting()
                + " class=0x" + hex(intf.getInterfaceClass())
                + " subclass=0x" + hex(intf.getInterfaceSubclass())
                + " protocol=0x" + hex(intf.getInterfaceProtocol())
                + " endpoints=" + intf.getEndpointCount());
        for (int j = 0; j < intf.getEndpointCount(); j++) {
            UsbEndpoint ep = intf.getEndpoint(j);
            log("  EP address=0x" + hex(ep.getAddress())
                    + " dir=" + (ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                    + " type=" + transferType(ep.getType())
                    + " maxPacket=" + ep.getMaxPacketSize()
                    + " interval=" + ep.getInterval());
        }
    }

    private void claimSelectedInterface() {
        if (connection == null || activeDevice == null) {
            log("CLAIM skipped: open a device first");
            return;
        }
        if (claimedInterface != null) {
            log("CLAIM skipped: interface already held id=" + claimedInterface.getId()
                    + "/alt" + claimedInterface.getAlternateSetting()
                    + "; release before trying another");
            return;
        }
        int pos = interfaceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= interfaces.size()) {
            log("CLAIM skipped: select an interface descriptor first");
            return;
        }

        UsbInterface candidate = interfaces.get(pos);
        StringBuilder eps = new StringBuilder();
        boolean bulkIn = false;
        boolean bulkOut = false;
        for (int j = 0; j < candidate.getEndpointCount(); j++) {
            UsbEndpoint ep = candidate.getEndpoint(j);
            if (eps.length() > 0) eps.append(",");
            eps.append("0x").append(hex(ep.getAddress())).append("/")
                    .append(transferType(ep.getType()));
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = true;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) bulkOut = true;
            }
        }
        log("CLAIM attempt descriptorIndex=" + pos + " id=" + candidate.getId()
                + " alt=" + candidate.getAlternateSetting()
                + " class=0x" + hex(candidate.getInterfaceClass())
                + " endpoints=[" + eps + "] bulkIn=" + bulkIn + " bulkOut=" + bulkOut
                + " force=true");
        boolean ok = connection.claimInterface(candidate, true);
        log("CLAIM result=" + ok);
        if (!ok) return;

        claimedInterface = candidate;
        log("CLAIM NOTE: setInterface is deliberately a separate manual step; no alternate-setting call was made by CLAIM.");
        log("CLAIM NOTE: success proves interface claim only; no USB payload transfer was attempted");
        logNetworkInterfaces();
        updateButtons();
    }

    private void setClaimedAlternateSetting() {
        if (connection == null || activeDevice == null || claimedInterface == null) {
            log("SET_INTERFACE skipped: open device and claim an interface first");
            return;
        }
        int alt = claimedInterface.getAlternateSetting();
        log("SET_INTERFACE attempt id=" + claimedInterface.getId() + " alt=" + alt
                + " class=0x" + hex(claimedInterface.getInterfaceClass()));
        if (alt == 0) {
            log("SET_INTERFACE skipped: selected claimed descriptor has alt=0; no implicit reset is attempted");
            return;
        }
        boolean ok = connection.setInterface(claimedInterface);
        log("SET_INTERFACE result=" + ok + " id=" + claimedInterface.getId() + " alt=" + alt);
        log("SET_INTERFACE NOTE: this result is separate from CLAIM; no USB payload transfer was attempted");
        logNetworkInterfaces();
        updateButtons();
    }

    private void closeConnection(String reason) {
        if (connection != null) {
            log("CLOSE begin reason=" + reason + " heldInterface="
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
            try { connection.close(); } catch (Exception e) { log("CLOSE exception=" + e); }
        }
        connection = null;
        activeDevice = null;
        claimedInterface = null;
        interfaces.clear();
        if (interfaceSpinner != null && interfaceSpinner.getAdapter() != null) {
            ((ArrayAdapter<?>) interfaceSpinner.getAdapter()).clear();
        }
        updateButtons();
        logNetworkInterfaces();
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
            log("BULK_IN skipped: open device and claim the intended interface first");
            return;
        }
        final UsbDeviceConnection conn = connection;
        final UsbInterface intf = claimedInterface;
        final UsbEndpoint ep = findBulkEndpoint(intf, UsbConstants.USB_DIR_IN);
        if (ep == null) {
            log("BULK_IN skipped: claimed descriptor has no bulk IN endpoint");
            return;
        }
        if (!transferBusy.compareAndSet(false, true)) {
            log("TRANSFER skipped: another transfer is in progress");
            return;
        }
        updateButtons();
        log("BULK_IN begin endpoint=0x" + hex(ep.getAddress())
                + " interface=" + intf.getId() + "/alt" + intf.getAlternateSetting()
                + " buffer=4096 timeoutMs=1500");
        new Thread(() -> {
            byte[] buffer = new byte[4096];
            long start = android.os.SystemClock.elapsedRealtime();
            int result;
            try {
                result = conn.bulkTransfer(ep, buffer, buffer.length, 1500);
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                StringBuilder sample = new StringBuilder();
                int shown = Math.min(Math.max(result, 0), 64);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sample.append(' ');
                    sample.append(hex(buffer[i] & 0xFF));
                }
                final String detail = "BULK_IN result=" + result + " elapsedMs=" + elapsed
                        + (result > 0 ? " firstBytes[" + shown + "]=" + sample : "")
                        + (result < 0 ? " note=timeout_or_USB_failure_errno_unavailable_in_public_API" : "");
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
            log("BULK_OUT skipped: open device and claim the intended interface first");
            return;
        }
        UsbEndpoint ep = findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_OUT);
        if (ep == null) {
            log("BULK_OUT skipped: claimed descriptor has no bulk OUT endpoint");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Transmit one diagnostic Ethernet frame?")
                .setMessage("Wyśle pojedynczą ramkę broadcast z EtherType 0x88B5 (testowy, niestandardowy EtherType), bez IP/ARP/DHCP. Rób to na odizolowanym segmencie testowym. Inne urządzenia na segmencie mogą ją odebrać.")
                .setNegativeButton("Cancel", (dialog, which) -> log("BULK_OUT cancelled by user"))
                .setPositiveButton("Send one frame", (dialog, which) -> runBulkOutFrame())
                .show();
    }

    private void runBulkOutFrame() {
        if (connection == null || claimedInterface == null) {
            log("BULK_OUT skipped: connection/interface no longer available");
            return;
        }
        final UsbDeviceConnection conn = connection;
        final UsbInterface intf = claimedInterface;
        final UsbEndpoint ep = findBulkEndpoint(intf, UsbConstants.USB_DIR_OUT);
        if (ep == null) {
            log("BULK_OUT skipped: no bulk OUT endpoint");
            return;
        }
        if (!transferBusy.compareAndSet(false, true)) {
            log("TRANSFER skipped: another transfer is in progress");
            return;
        }
        updateButtons();
        final byte[] frame = makeDiagnosticEthernetFrame();
        log("BULK_OUT begin endpoint=0x" + hex(ep.getAddress())
                + " interface=" + intf.getId() + "/alt" + intf.getAlternateSetting()
                + " bytes=" + frame.length + " ethertype=0x88B5");
        new Thread(() -> {
            long start = android.os.SystemClock.elapsedRealtime();
            try {
                int result = conn.bulkTransfer(ep, frame, frame.length, 1500);
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                runOnUiThread(() -> log("BULK_OUT result=" + result + "/" + frame.length
                        + " elapsedMs=" + elapsed
                        + (result == frame.length ? " note=host_USB_API_accepted_full_buffer_not_proof_of_remote_receipt" : "")));
            } catch (Exception e) {
                runOnUiThread(() -> log("BULK_OUT exception=" + e));
            } finally {
                transferBusy.set(false);
                runOnUiThread(this::updateButtons);
            }
        }, "usb-probe-bulk-out").start();
    }

    private byte[] makeDiagnosticEthernetFrame() {
        byte[] frame = new byte[60]; // Ethernet frame without FCS; minimum untagged frame size.
        for (int i = 0; i < 6; i++) frame[i] = (byte) 0xFF; // broadcast destination
        byte[] source = new byte[] {0x02, (byte) 0xCE, 0x5A, 0x00, 0x00, 0x01};
        System.arraycopy(source, 0, frame, 6, source.length);
        frame[12] = (byte) 0x88;
        frame[13] = (byte) 0xB5;
        byte[] tag = "USBPROBE-TEST".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, frame, 14, tag.length);
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) frame[27 + i] = (byte) (stamp >>> (56 - 8 * i));
        return frame;
    }

    private void logNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) {
                log("NETIFS none returned");
                return;
            }
            for (NetworkInterface ni : Collections.list(all)) {
                StringBuilder addresses = new StringBuilder();
                ni.getInterfaceAddresses().forEach(a -> {
                    if (addresses.length() > 0) addresses.append(",");
                    addresses.append(a.getAddress().getHostAddress()).append("/").append(a.getNetworkPrefixLength());
                });
                log("NETIF name=" + ni.getName() + " up=" + safeIsUp(ni)
                        + " loopback=" + ni.isLoopback() + " addresses=" + addresses);
            }
        } catch (Exception e) {
            log("NETIFS ERROR " + e);
        }
    }

    private boolean safeIsUp(NetworkInterface ni) {
        try { return ni.isUp(); } catch (Exception ignored) { return false; }
    }

    private String describe(UsbDevice d) {
        if (d == null) return "null";
        return String.format("vid=0x%04X pid=0x%04X deviceId=%d name=%s",
                d.getVendorId(), d.getProductId(), d.getDeviceId(), d.getDeviceName());
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

    private String hex(int value) { return String.format("%02X", value); }

    private void log(String message) {
        String line = String.format("%tT.%<tL  %s", System.currentTimeMillis(), message);
        Log.i(TAG, line);
        if (output != null) {
            output.append(line + "\n");
        }
    }

    private void updateButtons() {
        if (claimButton != null) claimButton.setEnabled(connection != null && claimedInterface == null && !interfaces.isEmpty());
        if (setInterfaceButton != null) setInterfaceButton.setEnabled(connection != null && claimedInterface != null && claimedInterface.getAlternateSetting() != 0);
        if (releaseButton != null) releaseButton.setEnabled(connection != null);
        boolean canTransfer = connection != null && claimedInterface != null && !transferBusy.get();
        if (bulkInButton != null) bulkInButton.setEnabled(canTransfer && findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_IN) != null);
        if (bulkOutButton != null) bulkOutButton.setEnabled(canTransfer && findBulkEndpoint(claimedInterface, UsbConstants.USB_DIR_OUT) != null);
    }

    @Override protected void onDestroy() {
        closeConnection("activity destroyed");
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
