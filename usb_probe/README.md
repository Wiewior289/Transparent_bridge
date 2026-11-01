# CDC Ethernet Userland Replay

Experimental standalone Android USB-host test application for CDC adapter
1A86:5394. Isolated from the production bridge.

## CDC bind sequence replayed from cdc_ether.c

1. Enumerate raw configuration descriptors using standard GET_DESCRIPTOR.
2. Parse CDC HEADER, UNION and ETHERNET functional descriptors.
3. Resolve Union master/control and slave/data interface numbers.
4. Read iMACAddress, wMaxSegmentSize and Ethernet functional descriptor fields.
5. Select the data alternate setting containing non-zero bulk IN and bulk OUT.
6. Claim control and data with Android claimInterface(..., true). This
   deliberately force-detaches cdc_ether. The supplied device_usb.txt captured
   the resulting usbfs ownership link and disappearance of eth0.
7. Set the selected data alternate setting.
8. Send CDC SET_ETHERNET_PACKET_FILTER with DIRECTED | BROADCAST, matching
   usbnet_cdc_update_filter() initial filter behavior.
9. No CH9200 vendor commands are emitted; matching is descriptor-driven.

## TX path modeled on usbnet_start_xmit()

TX queues a valid Ethernet ARP who-has request, not an arbitrary EtherType
payload: broadcast destination, descriptor-derived source MAC, EtherType ARP,
Ethernet/IPv4 address formats, opcode request, target 192.0.2.1. TX is blocked
if iMACAddress cannot be read and validated rather than inventing a MAC.

The worker serializes queued packets and applies usbnet's maxpacket termination
rule: if transfer length is an exact multiple of maxpacket, Linux adds one pad
byte unless FLAG_SEND_ZLP is set, in which case it sets URB_ZERO_PACKET. The
observed 60-byte test frame with a 512-byte maxpacket does not hit this case.
The device-specific driver_info flag still needs confirmation before treating
that boundary behavior as proven for this adapter.

## RX path modeled on usbnet RX queue/refill

A dedicated worker repeatedly reads bulk IN, validates ETH_HLEN, queues received
frames, extracts EtherType and rearms the next read. The RX button displays the
next queued Ethernet frame. This reproduces the persistent receive-loop intent.

Important limitation: Linux usbnet keeps multiple concurrently submitted URBs
and uses completion callbacks, an RX queue and bottom-half refill. Android's
public UsbDeviceConnection.bulkTransfer() is synchronous and exposes neither
URBs nor completion callbacks, anchors, tasklets, unlink/drain or kernel PM.
This Android implementation therefore blocks on one IN transfer at a time. It
is not an exact concurrent URB port.

## Build

From repository root:

    gradle :usb_probe:assembleDebug --no-configuration-cache

Use the repository's configured Android Gradle Plugin and JDK 17.

APK:

    usb_probe/build/outputs/apk/debug/usb_probe-debug.apk

Install/log:

    adb install -r usb_probe/build/outputs/apk/debug/usb_probe-debug.apk
    adb logcat -s CdcEtherUserland

## Lab test order

1. Press Scan / acquire / replay CDC bind and grant USB permission.
2. Verify HEADER + UNION + ETHERNET, Union mapping, MAC string, max segment,
   endpoint addresses and maxpacket.
3. Verify forced claims, SET_INTERFACE and packet-filter result.
4. Independently verify sysfs driver symlink is usbfs and eth0 is absent.
5. Start RX queue and generate traffic from a known peer. Idle timeout alone
   is not a framing failure.
6. Press TX and capture on the peer (for example tcpdump). API return is not
   proof of remote receipt.
7. Press RX to display a queued frame.
8. Stop queues and close USB; cdc_ether may bind again.

## Gaps

- No Gradle build or physical-device execution has been performed in this
  environment.
- Public Android API hides the underlying bulkTransfer errno.
- Concurrent URBs, URB completion callbacks, tasklet refill, anchors,
  unlink/drain, runtime-PM references, high-error throttling, endpoint halt
  recovery, interrupt-status URB, TX completion accounting and netdev delivery
  are not implemented.
- The device-specific FLAG_SEND_ZLP choice remains to be checked in the exact
  matching kernel driver_info.
- Lab experiment only; not a production driver.
