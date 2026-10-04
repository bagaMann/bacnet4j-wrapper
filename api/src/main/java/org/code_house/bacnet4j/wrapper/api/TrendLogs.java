/*
 * Copyright (C) 2026 ConnectorIO contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.code_house.bacnet4j.wrapper.api;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.acknowledgement.ReadPropertyAck;
import com.serotonin.bacnet4j.service.acknowledgement.ReadRangeAck;
import com.serotonin.bacnet4j.service.confirmed.ReadPropertyRequest;
import com.serotonin.bacnet4j.service.confirmed.ReadRangeRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.enumerated.Segmentation;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

/** Read-only, explicitly invoked Trend Log diagnostics. No polling or property writes. */
public final class TrendLogs {

    private TrendLogs() {
    }

    /**
     * Read one small page by its current buffer position (base 1).
     * Positions can move when a circular buffer rolls over; they are not synchronization cursors.
     * The response preserves BACnet timestamps and log-status/time-change records unchanged.
     */
    public static ReadRangeAck readByPosition(BacNetClient client, BacNetObject object, int position, int count) {
        if (object == null || object.getType() != Type.TREND_LOG) {
            throw new IllegalArgumentException("A Trend Log object is required");
        }
        if (position < 1 || count < 1 || count > 10) {
            throw new IllegalArgumentException("Position must be >= 1 and count must be between 1 and 10");
        }
        if (!(client instanceof BacNetClientBase)) {
            throw new IllegalArgumentException("This client does not support Trend Log diagnostics");
        }
        LocalDevice local = ((BacNetClientBase) client).localDevice;
        Device device = object.getDevice();
        ObjectIdentifier deviceId = new ObjectIdentifier(ObjectType.device, device.getInstanceNumber());
        try {
            // Use capabilities from the configured device without changing the discovery cache or transport.
            ReadPropertyAck apduAck = local.send(device.getBacNet4jAddress(),
                new ReadPropertyRequest(deviceId, PropertyIdentifier.maxApduLengthAccepted)).get();
            ReadPropertyAck segmentationAck = local.send(device.getBacNet4jAddress(),
                new ReadPropertyRequest(deviceId, PropertyIdentifier.segmentationSupported)).get();
            Encodable apdu = apduAck.getValue();
            Encodable segmentation = segmentationAck.getValue();
            if (!(apdu instanceof UnsignedInteger) || ((UnsignedInteger) apdu).intValue() <= 0
                    || !(segmentation instanceof Segmentation)) {
                throw new IllegalArgumentException("Invalid BACnet APDU capabilities for " + device);
            }
            RemoteDevice remote = new RemoteDevice(local, device.getInstanceNumber(), device.getBacNet4jAddress());
            remote.setDeviceProperty(PropertyIdentifier.maxApduLengthAccepted, apdu);
            remote.setDeviceProperty(PropertyIdentifier.segmentationSupported, segmentation);
            ReadRangeAck ack = local.send(remote, new ReadRangeRequest(object.getBacNet4jIdentifier(),
                PropertyIdentifier.logBuffer, null, new ReadRangeRequest.ByPosition(position, count))).get();
            if (!object.getBacNet4jIdentifier().equals(ack.getObjectIdentifier())
                    || !PropertyIdentifier.logBuffer.equals(ack.getPropertyIdentifier())
                    || ack.getPropertyArrayIndex() != null
                    || ack.getItemCount().intValue() != ack.getItemData().getCount()
                    || ack.getItemData().getCount() > count) {
                throw new IllegalArgumentException("Unexpected Trend Log range response");
            }
            return ack;
        } catch (BACnetException e) {
            throw new BacNetClientException("Could not read Trend Log range for " + object, e);
        }
    }
}
