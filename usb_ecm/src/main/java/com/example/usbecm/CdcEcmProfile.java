package com.example.usbecm;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

/**
 * Immutable result of CDC-ECM descriptor discovery.
 * Interface numbers are meaningful only inside the selected configuration.
 */
public final class CdcEcmProfile {
    public final int configurationValue;
    public final int bcdCdc;
    public final int controlInterfaceNumber;
    public final int dataInterfaceNumber;
    public final int macStringIndex;
    public final int maxSegmentSize;
    public final UsbConfiguration configuration;
    public final UsbInterface controlInterface;
    public final UsbInterface dataInterface;
    public final UsbEndpoint bulkIn;
    public final UsbEndpoint bulkOut;

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
