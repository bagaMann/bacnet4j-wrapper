/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.api;

/**
 * Handle representing an active BACnet COV subscription.
 */
public interface CovSubscription extends AutoCloseable {

    BacNetObject getObject();

    int getSubscriberProcessIdentifier();

    int getLifetime();

    boolean isConfirmed();

    /**
     * Cancel the remote COV subscription and detach its local listener.
     */
    @Override
    void close();
}
