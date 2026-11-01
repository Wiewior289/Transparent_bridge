# Reference architecture: Halone USB ECM and in-process network stack

Status: reference notes only. This is not production approval and does not copy Halone implementation code into Transparent_bridge.

## Source references

- Halone `UsbEcmLink.kt` (pinned source): https://github.com/HaloneXR/halone/blob/c740605d84cbedeb74c4b6b7df930b7d50ba1780/android/core-glasses/src/main/kotlin/dev/halone/glasses/transport/usbnet/UsbEcmLink.kt
- Linux CDC ECM: https://github.com/torvalds/linux/blob/master/drivers/net/usb/cdc_ether.c
- Linux usbnet core: https://github.com/torvalds/linux/blob/master/drivers/net/usb/usbnet.c
- Linux CDC descriptor and request definitions: https://github.com/torvalds/linux/blob/master/include/uapi/linux/usb/cdc.h

## Layer separation to preserve

1. **CDC descriptor discovery and binding**
   - Parse configuration descriptors and associate Header, Union and Ethernet functional descriptors with the Union master/control interface.
   - Resolve the Union slave/data interface inside the same configuration.
   - Validate control class/subclass/protocol, data class, active alternate setting, and a unique bulk IN/OUT pair.
   - Claim interfaces, select the data alternate setting, and check the result of `SET_ETHERNET_PACKET_FILTER`.
   - Do not weaken validation to an interface-number adjacency heuristic such as `data.id == control.id + 1`.

2. **USB Ethernet transport**
   - Move complete Ethernet frames over bulk IN/OUT.
   - Own transfer timeouts, bounded TX/RX queues, drop/error counters, disconnect handling and orderly release.
   - Android public `bulkTransfer` is synchronous; a single blocking receive loop is not equivalent to Linux's multiple in-flight URBs. Label it honestly as a synchronous userland transport, not as a true URB queue.

3. **Ethernet frame contract**
   - Expose complete Ethernet frames to the upper network layer without mixing USB descriptor state into IP/TCP logic.
   - Keep packet capture/diagnostic callbacks at this boundary if needed.

4. **In-process network stack (Halone reference)**
   - `NetStack.kt` contains a DHCP client with a fallback address, ARP resolution/cache, IPv4 dispatch, UDP/DHCP handling, and TCP connection handling.
   - `Packets.kt` builds/parses Ethernet, ARP, IPv4, UDP and TCP headers and checksums.
   - `TcpConnection.kt` has a receive ring buffer, send segmentation, sequence/ACK tracking, retransmission state and a bounded advertised receive window.
   - These components can be an application-local networking layer above the Ethernet frame contract. They are not part of CDC binding and must not be merged into the USB driver module.

5. **Tailscale / bridge integration (not established by Halone alone)**
   - The existence of an in-process DHCP/TCP stack does not automatically create a Tailscale interface or route.
   - A real integration needs an explicit adapter between the USB-side network model and the app's actual tailnet path, for example a supported TUN/forwarding endpoint or a deliberate TCP proxy. The choice depends on the existing bridge and tsnet/Tailscale ownership model.
   - Do not claim layer-2 Ethernet frames are carried transparently over Tailscale unless the implementation actually transports L2. Ordinary IP routing and a TCP proxy are different contracts.

## Linux behavior to use as a contract, not copied blindly

- `cdc_ether.c` matches generic CDC Communication / Ethernet subclass / protocol-none devices and invokes `usbnet_generic_cdc_bind()`.
- The CDC Union descriptor, not interface adjacency, identifies master/control and slave/data interface numbers.
- Linux's CDC Ethernet packet-filter constants are bit values: directed `0x0004`, broadcast `0x0008`; combined directed+broadcast is `0x000C`. Halone uses `0x000E`, which also enables all-multicast.
- `usbnet.c` owns the kernel net_device, SKB queues, URBs, completion callbacks, queue recovery, TX framing policy and disconnect cleanup. Android USB Host APIs do not expose all of those kernel primitives.
- Linux files are GPL-licensed; use them to understand behavior and the protocol contract. Do not copy their implementation into this app without reviewing license compatibility.

## Current refactor boundary

- `CdcDescriptorScanner`: raw configuration-descriptor parsing and strict function matching.
- `CdcEcmProfile`: immutable selected configuration/interface/endpoint profile.
- `CdcEcmBinder`: claim, alternate setting, packet-filter request, rollback/release.
- `MainActivity`: still owns the diagnostic UI and current synchronous RX/TX loops. The next transport extraction should move those loops/queues behind a dedicated transport and expose only complete frames.
