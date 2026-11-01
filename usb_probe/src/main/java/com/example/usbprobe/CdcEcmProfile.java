package com.example.usbprobe;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

/**
 * Immutable result of CDC-ECM descriptor discovery.
 * Interface numbers are meaningful only inside the selected configuration.
 */
final class CdcEcmProfile {
    final int configurationValue;
    final int bcdCdc;
    final int controlInterfaceNumber;
    final int dataInterfaceNumber;
    final int macStringIndex;
    final int maxSegmentSize;
    final UsbConfiguration configuration;
    final UsbInterface controlInterface;
    final UsbInterface dataInterface;
    final UsbEndpoint bulkIn;
    final UsbEndpoint bulkOut;

    CdcEcmProfile(
            int configurationValue,
            int bcdCdc,
            int controlInterfaceNumber,
            int dataInterfaceNumber,
            int macStringIndex,
            int maxSegmentSize,
            UsbConfiguration configuration,
            UsbInterface controlInterface,
            UsbInterface dataInterface,
            UsbEndpoint bulkIn,
            UsbEndpoint bulkOut) {
        this.configurationValue = configurationValue;
        this.bcdCdc = bcdCdc;
        this.controlInterfaceNumber = controlInterfaceNumber;
        this.dataInterfaceNumber = dataInterfaceNumber;
        this.macStringIndex = macStringIndex;
        this.maxSegmentSize = maxSegmentSize;
        this.configuration = configuration;
        this.controlInterface = controlInterface;
        this.dataInterface = dataInterface;
        this.bulkIn = bulkIn;
        this.bulkOut = bulkOut;
    }
}
