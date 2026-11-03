package com.example.usbecm;

import android.hardware.usb.UsbDeviceConnection;

/**
 * Performs the stateful CDC-ECM bind sequence after descriptor discovery.
 * A failed step rolls back every interface claim made by this binder.
 */
public final class CdcEcmBinder {
    public interface Logger {
        void log(String message);
    }

    private static final int CDC_SET_ETHERNET_PACKET_FILTER = 0x43;
    private static final int CDC_PACKET_TYPE_DIRECTED = 0x0004;
    private static final int CDC_PACKET_TYPE_BROADCAST = 0x0008;
    private static final int USB_CTRL_SET_TIMEOUT = 5000;

    private final UsbDeviceConnection connection;
    private final CdcEcmProfile profile;
    private final Logger logger;
    private boolean controlClaimed;
    private boolean dataClaimed;
    private boolean bound;

    public CdcEcmBinder(UsbDeviceConnection connection, CdcEcmProfile profile, Logger logger) {
        this.connection = connection;
        this.profile = profile;
        this.logger = logger;
    }

    public boolean bind() {
        if (bound) return true;
        controlClaimed = connection.claimInterface(profile.controlInterface, true);
        logger.log("CLAIM control force=true -> " + controlClaimed);
        if (!controlClaimed) return fail("control interface claim failed");

        dataClaimed = connection.claimInterface(profile.dataInterface, true);
        logger.log("CLAIM data force=true -> " + dataClaimed);
        if (!dataClaimed) return fail("data interface claim failed");

        boolean altSet = connection.setInterface(profile.dataInterface);
        logger.log("SET_INTERFACE data alt=" + profile.dataInterface.getAlternateSetting() +
                " -> " + altSet);
        if (!altSet) return fail("data alternate-setting selection failed");

        int filter = CDC_PACKET_TYPE_DIRECTED | CDC_PACKET_TYPE_BROADCAST;
        int result = connection.controlTransfer(0x21, CDC_SET_ETHERNET_PACKET_FILTER,
                filter, profile.controlInterface.getId(), null, 0, USB_CTRL_SET_TIMEOUT);
        logger.log("CDC SET_ETHERNET_PACKET_FILTER value=0x" +
                String.format(java.util.Locale.US, "%04X", filter) +
                " wIndex=" + profile.controlInterface.getId() + " result=" + result);
        if (result < 0) return fail("CDC packet-filter request failed");

        bound = true;
        logger.log("CDC bind sequence complete; Ethernet frame I/O may now be tested.");
        return true;
    }

    private boolean fail(String reason) {
        logger.log("CDC bind failed: " + reason + "; rolling back claims.");
        releaseClaims();
        return false;
    }

    public void close() {
        bound = false;
        releaseClaims();
    }

    private void releaseClaims() {
        if (dataClaimed) {
            try {
                logger.log("RELEASE data -> " + connection.releaseInterface(profile.dataInterface));
            } catch (Exception e) {
                logger.log("RELEASE data exception=" + e);
            }
            dataClaimed = false;
        }
        if (controlClaimed) {
            try {
                logger.log("RELEASE control -> " + connection.releaseInterface(profile.controlInterface));
            } catch (Exception e) {
                logger.log("RELEASE control exception=" + e);
            }
            controlClaimed = false;
        }
    }
}
