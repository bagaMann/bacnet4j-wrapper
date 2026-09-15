/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.api;

import com.serotonin.bacnet4j.type.Encodable;

/**
 * Listener for BACnet Change of Value notifications.
 */
@FunctionalInterface
public interface CovListener {

    /**
     * Called when a COV notification is received for the subscribed object.
     *
     * @param object monitored BACnet object
     * @param presentValue current Present_Value, or {@code null} when it is not part of the notification
     * @param timeRemaining subscription lifetime remaining as reported by the server, in seconds
     */
    void onCovNotification(BacNetObject object, Encodable presentValue, long timeRemaining);
}
