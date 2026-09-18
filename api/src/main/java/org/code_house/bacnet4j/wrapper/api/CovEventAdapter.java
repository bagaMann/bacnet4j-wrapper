/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.api;

import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

/**
 * Filters BACnet COV events for one subscription and forwards COV values.
 */
final class CovEventAdapter extends DeviceEventAdapter {

    private final int subscriberProcessIdentifier;
    private final BacNetObject object;
    private final CovListener listener;

    CovEventAdapter(int subscriberProcessIdentifier, BacNetObject object, CovListener listener) {
        this.subscriberProcessIdentifier = subscriberProcessIdentifier;
        this.object = object;
        this.listener = listener;
    }

    @Override
    public void covNotificationReceived(UnsignedInteger processIdentifier,
            ObjectIdentifier initiatingDeviceIdentifier, ObjectIdentifier monitoredObjectIdentifier,
            UnsignedInteger timeRemaining, SequenceOf<PropertyValue> listOfValues) {
        if (processIdentifier.intValue() != subscriberProcessIdentifier
                || !object.getBacNet4jIdentifier().equals(monitoredObjectIdentifier)) {
            return;
        }

        Encodable presentValue = null;
        Encodable statusFlags = null;
        Encodable eventState = null;
        Encodable outOfService = null;
        for (PropertyValue propertyValue : listOfValues) {
            if (PropertyIdentifier.presentValue.equals(propertyValue.getPropertyIdentifier())) {
                presentValue = propertyValue.getValue();
            } else if (PropertyIdentifier.statusFlags.equals(propertyValue.getPropertyIdentifier())) {
                statusFlags = propertyValue.getValue();
            } else if (PropertyIdentifier.eventState.equals(propertyValue.getPropertyIdentifier())) {
                eventState = propertyValue.getValue();
            } else if (PropertyIdentifier.outOfService.equals(propertyValue.getPropertyIdentifier())) {
                outOfService = propertyValue.getValue();
            }
        }

        long remaining = timeRemaining.longValue();
        if (presentValue != null) {
            listener.onCovNotification(object, presentValue, remaining);
        }
        if (statusFlags != null) {
            listener.onCovStatusFlags(object, statusFlags, remaining);
        }
        if (eventState != null) {
            listener.onCovEventState(object, eventState, remaining);
        }
        if (outOfService != null) {
            listener.onCovOutOfService(object, outOfService, remaining);
        }
    }
}
