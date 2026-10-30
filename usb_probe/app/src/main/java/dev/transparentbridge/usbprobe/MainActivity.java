package dev.transparentbridge.usbprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
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
    private Button releaseButton;
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
        releaseButton = addButton(root, "Release / close", v -> closeConnection("manual release"));
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
                + " interfaces=" + device.getInterfaceCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            log(" IF index=" + i + " id=" + intf.getId()
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
        if (candidate.getAlternateSetting() != 0) {
            boolean set = connection.setInterface(candidate);
            log("SET_INTERFACE alt=" + candidate.getAlternateSetting() + " result=" + set);
        } else {
            log("SET_INTERFACE skipped: selected alternate setting is 0");
        }
        log("CLAIM NOTE: success proves interface claim only; no USB payload transfer was attempted");
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
        if (releaseButton != null) releaseButton.setEnabled(connection != null);
    }

    @Override protected void onDestroy() {
        closeConnection("activity destroyed");
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
