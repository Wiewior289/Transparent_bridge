package com.example.usbprobe;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers CDC-ECM from USB descriptors. No VID/PID or vendor-command assumptions.
 *
 * The CDC Header, Union and Ethernet functional descriptors must belong to the
 * Union master/control interface. The Union slave must resolve to a CDC Data
 * interface in the same configuration, with a bulk IN/OUT alternate setting.
 */
final class CdcDescriptorScanner {
    interface Logger {
        void log(String message);
    }

    private static final int USB_DT_CONFIG = 0x02;
    private static final int USB_DT_INTERFACE = 0x04;
    private static final int USB_DT_CS_INTERFACE = 0x24;
    private static final int CDC_SUBTYPE_HEADER = 0x00;
    private static final int CDC_SUBTYPE_UNION = 0x06;
    private static final int CDC_SUBTYPE_ETHERNET = 0x0F;
    private static final int USB_CLASS_COMM = 0x02;
    private static final int USB_SUBCLASS_ECM = 0x06;
    private static final int USB_PROTO_NONE = 0x00;
    private static final int USB_CLASS_CDC_DATA = 0x0A;

    private final Logger logger;

    CdcDescriptorScanner(Logger logger) {
        this.logger = logger;
    }

    CdcEcmProfile scan(UsbDeviceConnection connection, UsbDevice device) {
        List<CdcEcmProfile> candidates = new ArrayList<>();
        for (int configIndex = 0; configIndex < device.getConfigurationCount(); configIndex++) {
            byte[] bytes = new byte[4096];
            int count = connection.controlTransfer(0x80, 0x06, 0x0200 | configIndex,
                    0, bytes, bytes.length, 2000);
            logger.log("GET_DESCRIPTOR CONFIG index=" + configIndex + " result=" + count);
            if (count < 9) continue;

            int configLength = u8(bytes, 0);
            int descriptorType = u8(bytes, 1);
            int totalLength = u16le(bytes, 2);
            int configValue = u8(bytes, 5);
            if (configLength < 9 || descriptorType != USB_DT_CONFIG ||
                    totalLength < configLength || totalLength > count) {
                logger.log("Reject CONFIG index=" + configIndex + " malformed header length=" +
                        configLength + " type=0x" + hex(descriptorType) +
                        " totalLength=" + totalLength + " received=" + count);
                continue;
            }
            logger.log("CONFIG descriptor index=" + configIndex + " bConfigurationValue=" +
                    configValue + " totalLength=" + totalLength +
                    " bNumInterfaces=" + u8(bytes, 4));

            Map<Integer, Integer> classByInterface = new HashMap<>();
            Map<Integer, Integer> subclassByInterface = new HashMap<>();
            Map<Integer, Integer> protocolByInterface = new HashMap<>();
            Map<Integer, Integer> headerVersionByInterface = new HashMap<>();
            Map<Integer, EthernetDescriptor> ethernetByInterface = new HashMap<>();
            List<UnionDescriptor> unions = new ArrayList<>();
            int activeInterface = -1;
            boolean malformed = false;

            for (int p = 0; p < totalLength;) {
                if (p + 2 > totalLength) {
                    malformed = true;
                    break;
                }
                int length = u8(bytes, p);
                int type = u8(bytes, p + 1);
                if (length < 2 || p + length > totalLength) {
                    logger.log("Malformed descriptor at offset=" + p + " len=" + length +
                            " totalLength=" + totalLength + ". Reject this configuration.");
                    malformed = true;
                    break;
                }

                if (type == USB_DT_INTERFACE) {
                    if (length < 9) {
                        logger.log("Malformed interface descriptor at offset=" + p);
                        malformed = true;
                        break;
                    }
                    activeInterface = u8(bytes, p + 2);
                    int alt = u8(bytes, p + 3);
                    classByInterface.put(interfaceKey(activeInterface, alt), u8(bytes, p + 5));
                    subclassByInterface.put(interfaceKey(activeInterface, alt), u8(bytes, p + 6));
                    protocolByInterface.put(interfaceKey(activeInterface, alt), u8(bytes, p + 7));
                    logger.log("  IF num=" + activeInterface + " alt=" + alt +
                            " class=" + hex(u8(bytes, p + 5)) +
                            " sub=" + hex(u8(bytes, p + 6)) +
                            " proto=" + hex(u8(bytes, p + 7)) +
                            " ep=" + u8(bytes, p + 4));
                } else if (type == USB_DT_CS_INTERFACE && length >= 3 && activeInterface >= 0) {
                    int subtype = u8(bytes, p + 2);
                    if (subtype == CDC_SUBTYPE_HEADER) {
                        if (length < 5) {
                            logger.log("Malformed CDC Header descriptor at offset=" + p);
                            malformed = true;
                            break;
                        }
                        int version = u16le(bytes, p + 3);
                        headerVersionByInterface.put(activeInterface, version);
                        logger.log("  CDC HEADER interface=" + activeInterface +
                                " bcdCDC=" + hex(version));
                    } else if (subtype == CDC_SUBTYPE_UNION) {
                        if (length < 5) {
                            logger.log("Malformed CDC Union descriptor at offset=" + p);
                            malformed = true;
                            break;
                        }
                        UnionDescriptor union = new UnionDescriptor(
                                activeInterface, u8(bytes, p + 3), u8(bytes, p + 4));
                        unions.add(union);
                        logger.log("  CDC UNION descriptorInterface=" + activeInterface +
                                " master=" + union.master + " slave=" + union.slave);
                    } else if (subtype == CDC_SUBTYPE_ETHERNET) {
                        if (length < 13) {
                            logger.log("Malformed CDC Ethernet descriptor at offset=" + p);
                            malformed = true;
                            break;
                        }
                        EthernetDescriptor ethernet = new EthernetDescriptor(
                                u8(bytes, p + 3), u16le(bytes, p + 8));
                        ethernetByInterface.put(activeInterface, ethernet);
                        logger.log("  CDC ETHERNET interface=" + activeInterface +
                                " iMAC=" + ethernet.macStringIndex +
                                " maxSegment=" + ethernet.maxSegmentSize);
                    }
                }
                p += length;
            }
            if (malformed) continue;

            UsbConfiguration config = findConfiguration(device, configValue);
            if (config == null) {
                logger.log("No Android UsbConfiguration maps to bConfigurationValue=" + configValue);
                continue;
            }

            for (UnionDescriptor union : unions) {
                if (union.descriptorInterface != union.master) {
                    logger.log("Reject CDC Union: descriptor is on interface " +
                            union.descriptorInterface + " but master is " + union.master);
                    continue;
                }
                Integer bcdCdc = headerVersionByInterface.get(union.master);
                EthernetDescriptor ethernet = ethernetByInterface.get(union.master);
                if (bcdCdc == null || ethernet == null) {
                    logger.log("Reject CDC function: Header/Union/Ethernet descriptors are not all " +
                            "attached to control interface " + union.master);
                    continue;
                }

                UsbInterface control = findInterface(config, union.master, 0);
                int controlKey = interfaceKey(union.master, 0);
                if (control == null ||
                        !Integer.valueOf(USB_CLASS_COMM).equals(classByInterface.get(controlKey)) ||
                        !Integer.valueOf(USB_SUBCLASS_ECM).equals(subclassByInterface.get(controlKey)) ||
                        !Integer.valueOf(USB_PROTO_NONE).equals(protocolByInterface.get(controlKey)) ||
                        control.getInterfaceClass() != USB_CLASS_COMM ||
                        control.getInterfaceSubclass() != USB_SUBCLASS_ECM ||
                        control.getInterfaceProtocol() != USB_PROTO_NONE) {
                    logger.log("Reject CDC control role: Union master=" + union.master +
                            " must resolve to class 02/subclass 06/protocol 00 alt 0.");
                    continue;
                }

                UsbInterface data = null;
                UsbEndpoint in = null;
                UsbEndpoint out = null;
                for (int i = 0; i < config.getInterfaceCount(); i++) {
                    UsbInterface candidate = config.getInterface(i);
                    int dataKey = interfaceKey(candidate.getId(), candidate.getAlternateSetting());
                    if (candidate.getId() != union.slave ||
                            candidate.getInterfaceClass() != USB_CLASS_CDC_DATA ||
                            candidate.getInterfaceSubclass() != 0 ||
                            candidate.getInterfaceProtocol() != 0 ||
                            candidate.getEndpointCount() != 2 ||
                            !Integer.valueOf(USB_CLASS_CDC_DATA).equals(classByInterface.get(dataKey)) ||
                            !Integer.valueOf(0).equals(subclassByInterface.get(dataKey)) ||
                            !Integer.valueOf(0).equals(protocolByInterface.get(dataKey))) continue;
                    UsbEndpoint candidateIn = null;
                    UsbEndpoint candidateOut = null;
                    boolean duplicateIn = false;
                    boolean duplicateOut = false;
                    for (int e = 0; e < candidate.getEndpointCount(); e++) {
                        UsbEndpoint endpoint = candidate.getEndpoint(e);
                        if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK ||
                                endpoint.getMaxPacketSize() <= 0) continue;
                        if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                            if (candidateIn != null) duplicateIn = true;
                            candidateIn = endpoint;
                        } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                            if (candidateOut != null) duplicateOut = true;
                            candidateOut = endpoint;
                        }
                    }
                    if (candidateIn != null && candidateOut != null && !duplicateIn && !duplicateOut) {
                        data = candidate;
                        in = candidateIn;
                        out = candidateOut;
                        break;
                    }
                }
                if (data == null) {
                    logger.log("Reject CDC data role: Union slave=" + union.slave +
                            " has no alternate setting with one bulk IN and one bulk OUT endpoint.");
                    continue;
                }

                logger.log("CDC descriptor set accepted: HEADER + UNION + ETHERNET; " +
                        "configuration and interface roles validated.");
                candidates.add(new CdcEcmProfile(configValue, bcdCdc, union.master,
                        union.slave, ethernet.macStringIndex, ethernet.maxSegmentSize,
                        config, control, data, in, out));
            }
        }

        if (candidates.isEmpty()) return null;
        if (candidates.size() > 1) {
            logger.log("Ambiguous CDC ECM discovery: " + candidates.size() +
                    " complete functions found. Refusing implicit selection.");
            return null;
        }
        return candidates.get(0);
    }

    private UsbConfiguration findConfiguration(UsbDevice device, int configurationValue) {
        for (int i = 0; i < device.getConfigurationCount(); i++) {
            UsbConfiguration config = device.getConfiguration(i);
            logger.log("CONFIG API index=" + i + " value=" + config.getId() +
                    " interfaces=" + config.getInterfaceCount());
            if (config.getId() == configurationValue) return config;
        }
        return null;
    }

    private UsbInterface findInterface(UsbConfiguration config, int id, int alt) {
        for (int i = 0; i < config.getInterfaceCount(); i++) {
            UsbInterface candidate = config.getInterface(i);
            if (candidate.getId() == id && candidate.getAlternateSetting() == alt) return candidate;
        }
        return null;
    }

    private static int interfaceKey(int id, int alt) {
        return ((id & 0xFFFF) << 16) | (alt & 0xFFFF);
    }

    private static int u8(byte[] bytes, int offset) { return bytes[offset] & 0xFF; }
    private static int u16le(byte[] bytes, int offset) {
        return u8(bytes, offset) | (u8(bytes, offset + 1) << 8);
    }
    private static String hex(int value) { return String.format(java.util.Locale.US, "%04X", value & 0xFFFF); }

    private static final class UnionDescriptor {
        final int descriptorInterface, master, slave;
        UnionDescriptor(int descriptorInterface, int master, int slave) {
            this.descriptorInterface = descriptorInterface;
            this.master = master;
            this.slave = slave;
        }
    }

    private static final class EthernetDescriptor {
        final int macStringIndex, maxSegmentSize;
        EthernetDescriptor(int macStringIndex, int maxSegmentSize) {
            this.macStringIndex = macStringIndex;
            this.maxSegmentSize = maxSegmentSize;
        }
    }
}
