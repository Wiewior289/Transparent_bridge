# CDC Ethernet Userland Probe

Experimental standalone Android USB-host diagnostic for the generic CDC-ECM
function exposed by WCH adapter 1A86:5394. This module is isolated from the
production bridge. Its purpose is to exercise the CDC bind path and prove
real Ethernet/IPv4 traffic over the adapter, not merely a successful USB API
return value.

## CDC bind path

The descriptor scanner follows the generic CDC Ethernet match and bind model
from Linux `cdc_ether.c` / `usbnet.c`:

1. Enumerate raw configuration descriptors with standard GET_DESCRIPTOR.
2. Match the control interface by class/subclass/protocol (02/06/00), not by
   a VID/PID allowlist.
3. Parse CDC HEADER, UNION and ETHERNET functional descriptors.
4. Resolve Union master/control and slave/data interface numbers.
5. Select the CDC Data alternate setting containing exactly one usable bulk
   IN and one bulk OUT endpoint.
6. Claim the control and data interfaces with Android
   `claimInterface(..., true)`. This force-detaches `cdc_ether` while the
   probe owns the USB interfaces.
7. Select the data alternate setting.
8. Send `SET_ETHERNET_PACKET_FILTER` with DIRECTED | BROADCAST
   (`0x000C`) through the control interface.

A completed bind means only that descriptor validation and CDC control
initialization succeeded. It does not mean packets have reached the peer.

## Ethernet and IP probe

The UI has two explicit IPv4 inputs:

- **Probe source IPv4**: a free, unused address on the PLC-facing Ethernet
  subnet. It is intentionally not guessed from the phone's Wi-Fi/mobile IP.
- **PLC target IPv4**: defaults to `192.168.1.12`, the PLC address used in the
  supplied direct Dell-to-PLC test capture. Change it when the attached PLC
  uses a different address.

Press **PING PLC: ARP -> IPv4 ICMP Echo**. The probe performs this state
sequence:

1. Start the bulk-IN receive loop.
2. Transmit an Ethernet broadcast ARP who-has using the configured source IP
   and PLC target IP.
3. Wait for an ARP reply whose Ethernet destination, ARP target MAC/IP,
   sender MAC and sender IP all match the current probe.
4. Cache the peer MAC from the validated ARP reply.
5. Build and transmit a normal Ethernet II / IPv4 / ICMP Echo Request directly
   to that peer MAC.
6. Accept success only when a matching ICMP Echo Reply is received with the
   expected IPv4 source/destination, ICMP identifier/sequence, and valid IPv4
   and ICMP checksums.

The ICMP test frame uses IPv4 protocol 1, TTL 64, DF, and a 32-byte payload.
There is no IP fragmentation or gateway/route lookup in this diagnostic.
The source and target must therefore be directly reachable on the same
Ethernet segment. The source IP must not already be in use.

A `PING PASS` log is evidence that the adapter carried a complete ARP
request/reply and IPv4 ICMP echo request/reply. A USB `bulkTransfer()`
return alone is not treated as proof of peer delivery.

## TX/RX transport model

TX frames are serialized through a bounded software queue. The code models the
usbnet maxpacket termination rule: when a transfer length is an exact multiple
of maxpacket, the generic CDC path without `FLAG_SEND_ZLP` uses the extra pad
byte. This adapter's normal ARP and ICMP frames do not hit the 512-byte
boundary case.

RX uses one blocking Android `UsbDeviceConnection.bulkTransfer()` call at a
time and copies complete Ethernet frames into a bounded software queue. It
also dispatches frames to the ARP/ICMP probe state machine.

This is deliberately **not** a concurrent-URB port. Android's public USB Host
API does not expose Linux URBs, completion callbacks, anchors, tasklets,
unlink/drain, runtime-PM references or netdev delivery. The current RX worker
does not reproduce usbnet's multi-URB concurrency or kernel network interface.

## Lab test

1. Connect the CDC-ECM adapter to the phone and connect its Ethernet side to
   the PLC network.
2. Press Scan / acquire / replay CDC bind. Confirm HEADER + UNION + ETHERNET,
   interface roles, bulk endpoints, MAC string, successful claims,
   SET_INTERFACE and packet-filter result.
3. Verify separately that the kernel driver is detached while the probe owns
   the interfaces. Closing USB should allow the kernel driver to bind again.
4. Enter an unused source IPv4 from the PLC subnet and the PLC's actual target
   IPv4.
5. Press PING. Expected sequence in the log:
   - `TX queued ARP who-has ...`
   - `RX completed ... ethertype=0x0806`
   - `ARP PASS ... is-at <PLC MAC>`
   - `TX queued IPv4/ICMP Echo Request ...`
   - `RX completed ... ethertype=0x0800`
   - `PING PASS ... rtt=... ms`
6. If ARP times out, first inspect the USB bulk OUT result, cable/link, source
   subnet and PLC IP. If ARP succeeds but ICMP times out, inspect the PLC's
   ICMP policy/firewall and confirm that it answers echo requests.
7. Stop queues and close USB after the test.

## Build and unit tests

From repository root (JDK 17):

```sh
gradle :usb_probe:testDebugUnitTest --no-configuration-cache
gradle :usb_probe:assembleDebug --no-configuration-cache
```

Install/log:

```sh
adb install -r usb_probe/build/outputs/apk/debug/usb_probe-debug.apk
adb logcat -s CdcEtherUserland
```

## Explicit limits

- This module does not create an Android/Linux Ethernet network interface.
- It does not route, bridge, NAT, proxy TCP/UDP, or interact with Tailscale.
- It does not implement DHCP, DNS, IPv6, TCP, UDP, fragmentation, VLAN tags,
  ARP cache aging, gateways or general-purpose IP forwarding.
- It does not implement concurrent URBs, interrupt-status notifications,
  tasklet refill, anchors, unlink/drain, runtime-PM, high-error throttling,
  endpoint halt recovery, TX completion accounting or kernel netdev delivery.
- Public Android USB Host APIs hide the underlying `bulkTransfer()` errno.
- Physical execution on the WCH adapter and PLC is still required to establish
  actual hardware interoperability. Unit tests only validate packet encoding,
  checksums and matching logic.
