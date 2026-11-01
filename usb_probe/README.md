# CDC Ethernet Userland Replay

Experimental standalone Android APK for the CDC Ethernet adapter 1A86:5394.
This module remains isolated from the production bridge and the original
`experiment/generic-cdc-ether-usb-probe` branch.

## What is replayed from Linux cdc_ether + usbnet

### CDC bind path

1. Read every raw configuration descriptor.
2. Parse CDC HEADER, UNION and ETHERNET functional descriptors.
3. Resolve Union master/control and slave/data interface numbers.
4. Read the CDC Ethernet functional descriptor fields:
   - `iMACAddress`
   - `wMaxSegmentSize`
   - multicast filter count and power-filter count
5. Select the data alternate setting containing non-zero bulk IN and bulk OUT endpoints.
6. Claim control and data interfaces with Android `claimInterface(..., true)`.
   This is the tested ownership handover: it detaches the kernel `cdc_ether`
   binding and puts the interface under userspace `usbfs`.
7. Issue SET_INTERFACE for the selected data alternate setting.
8. Send `CDC SET_ETHERNET_PACKET_FILTER` with DIRECTED | BROADCAST, matching
   `usbnet_cdc_update_filter()` initial filter behavior.

No vendor-specific CH9200 request is emitted based on VID/PID.

### TX path

The TX button queues a protocol-valid Ethernet ARP who-has frame rather than a
made-up EtherType payload. Source MAC comes from the CDC Ethernet descriptor's
MAC string (fallback is locally administered only when unavailable), destination
is broadcast, EtherType is ARP, hardware/protocol types and lengths are
Ethernet/IPv4, and opcode is request. Test target is documentation prefix
192.0.2.1.

The TX worker serializes frames and applies the usbnet core's full-maxpacket
termination policy: for a transfer whose length is an exact multiple of the
bulk OUT maxpacket, add one pad byte unless the selected driver profile is
known to use `FLAG_SEND_ZLP`. In this first userland profile the pad behavior
is the generic usbnet-core default. A 60-byte Ethernet frame on a 512-byte
maxpacket endpoint is not padded.

### RX path

RX has a persistent dedicated worker, bounded frame queue, frame-length check,
EtherType extraction, and repeated bulk IN submits rather than a single manual
read. It models the usbnet receive loop and re-arms the next receive after each
completion. RX button displays the next queued Ethernet frame.

The selected Linux usbnet implementation normally maintains multiple concurrent
URBs (RX queue) and completion callbacks. Android's public
`UsbDeviceConnection.bulkTransfer()` API is synchronous and does not expose
URBs, anchors, tasklets or callback resubmission. Therefore this module uses a
single synchronous RX worker and explicitly does NOT claim to reproduce
concurrent kernel URB scheduling. It is a userland behavioral analogue with the
same queue/re-arm intent, not a source-level port of usbnet.

## Build

From repository root:

    gradle :usb_probe:assembleDebug --no-configuration-cache

Use the repository's configured Android Gradle Plugin and JDK 17.

APK:

    usb_probe/build/outputs/apk/debug/usb_probe-debug.apk

Install and collect logs:

    adb install -r usb_probe/build/outputs/apk/debug/usb_probe-debug.apk
    adb logcat -s CdcEtherUserland

## Test procedure

1. Connect the adapter, open the app and tap **Scan / acquire / replay CDC bind**.
2. Grant Android USB permission.
3. Verify raw descriptor output, Union mapping, MAC string, max segment size,
   endpoint addresses/maxpacket, both successful claims, SET_INTERFACE and
   CDC packet-filter success.
4. Confirm independently on the rooted phone that the interface driver is
   `usbfs`, and that `eth0` has disappeared while userspace owns it.
5. Start RX queue. An idle-link timeout is not a framing failure; generate
   traffic from a known peer for a meaningful RX test.
6. Tap TX to send the ARP request, then capture on the peer (for example with
   tcpdump) to prove remote reception. API return alone is not proof.
7. Tap RX to inspect received frames.
8. Stop queues and release USB. Kernel may bind `cdc_ether` again.

## Known gaps / not yet verified

- No local build or device execution has been performed in this environment.
- Android USB Host API cannot expose Linux URB completion status errno; timeout
  and other errors may both appear as negative bulkTransfer results.
- The adapter-specific Linux driver flags must be checked before treating the
  ZLP/pad policy as proven for this exact binding. A 60-byte TX does not hit
  that branch.
- Kernel runtime-PM reference counting, anchors, unlink/drain semantics,
  error-rate RX throttling, halt recovery, interrupt-status URB, and netdev
  integration are not implemented yet.
- No claim is made that a successful Android bulkTransfer proves on-wire
  Ethernet delivery.
- Test only on an isolated/lab LAN. The test ARP has sender IP 0.0.0.0 and
  target 192.0.2.1; it is diagnostic traffic, not production network setup.
