# Generic CDC USB Probe

Standalone diagnostic APK for USB adapter 1A86:5394. This module is separate from the production bridge.

## Implementation

- Enumerates every exposed USB configuration, interface class/subclass/protocol, alternate setting, and endpoint.
- Reads the active configuration with standard GET_CONFIGURATION and logs each raw descriptor's `bConfigurationValue`.
- Refuses to claim interfaces if the active configuration cannot be confirmed or if the ECM configuration is not active; Android's public USB Host API has no `setConfiguration()` method.
- Requests the raw configuration descriptor with standard GET_DESCRIPTOR(CONFIGURATION).
- Parses CDC functional descriptors, especially Union (master/control and slave/data).
- Selects a CDC Ethernet control interface using class 0x02, subclass 0x06, protocol 0x00.
- Selects the data alternate setting containing bulk IN and bulk OUT.
- Claims only the selected control/data interfaces, selects the data alternate setting, and sends CDC SET_ETHERNET_PACKET_FILTER with directed+broadcast bits.
- Provides manual TX of a 60-byte broadcast Ethernet-shaped test frame (EtherType 0x88B5) and one-shot bulk IN.

No CH9200 vendor-register sequence, Realtek framing, routing, or NAT is included. Android USB Host API does not provide the kernel driver-binding mechanism or full usbnet URB queues.

## Build

From repository root:

    gradle :usb_probe:assembleDebug --no-configuration-cache

Use Gradle 9.3.1 and JDK 17. This repository branch currently does not track the `gradlew` launcher script; Android Studio can also import the root project and build the `usb_probe` module.

APK:

    usb_probe/build/outputs/apk/debug/usb_probe-debug.apk

Install and capture logs:

    adb install -r usb_probe/build/outputs/apk/debug/usb_probe-debug.apk
    adb logcat -s GenericCdcProbe

## Test order

1. Connect adapter and tap Scan + parse CDC descriptors.
2. Grant USB permission and save the full app log.
3. Compare `GET_CONFIGURATION activeValue` with `ECM candidate configValue`; these must match before the probe proceeds.
4. Confirm raw configuration descriptor parsing and the CDC Union master/slave values.
5. Confirm CLAIM control/data, SET_INTERFACE, and SET_ETHERNET_PACKET_FILTER result. Expected control-transfer return for a zero-length successful request is 0.
6. Only when ready to transmit on the connected LAN, tap Send isolated test Ethernet frame.
7. Test receive while a known peer is generating traffic. Idle bulk IN timeout is not proof of a bad protocol.
8. Close the connection before returning the adapter to kernel-managed use.

Claiming interfaces can detach cdc_ether and remove eth0 until the device/driver is reinitialized.

## Limitations

This is an experimental protocol-path probe, not a validated Ethernet driver. A positive bulk OUT result proves only host-side USB transfer acceptance. Do not use it for PLC firmware transfer or production traffic. The raw descriptor request or alternate-setting view can vary by Android implementation; compare logs against the supplied kernel source and descriptor dumps.
