/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.api;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.SubscribeCOVRequest;
import com.serotonin.bacnet4j.type.primitive.Boolean;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.code_house.bacnet4j.wrapper.api.util.ForwardingAdapter;

/**
 * Internal lifecycle support for BACnet COV subscriptions.
 */
final class CovSubscriptions {

    private static final AtomicInteger PROCESS_IDS = new AtomicInteger(1);

    private CovSubscriptions() {
    }

    static CovSubscription subscribe(BacNetClientBase client, BacNetObject object, int lifetime,
            boolean confirmed, CovListener listener) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(listener, "listener");
        if (lifetime <= 0) {
            throw new IllegalArgumentException("COV lifetime must be greater than zero");
        }

        int processId = nextProcessId();
        UnsignedInteger bacnetProcessId = new UnsignedInteger(processId);
        CovEventAdapter covAdapter = new CovEventAdapter(processId, object, listener);
        ForwardingAdapter forwardingAdapter = new ForwardingAdapter(client.executor, covAdapter);
        SubscribeCOVRequest request = new SubscribeCOVRequest(
            bacnetProcessId,
            object.getBacNet4jIdentifier(),
            Boolean.valueOf(confirmed),
            new UnsignedInteger(lifetime)
        );

        client.localDevice.getEventHandler().addListener(forwardingAdapter);
        try {
            client.localDevice.send(object.getDevice().getBacNet4jAddress(), request).get();
        } catch (BACnetException e) {
            client.localDevice.getEventHandler().removeListener(forwardingAdapter);
            throw new BacNetClientException("Unable to subscribe to COV for object " + object, e);
        }

        return new DefaultCovSubscription(client, object, processId, lifetime, confirmed, request, forwardingAdapter);
    }

    private static int nextProcessId() {
        return PROCESS_IDS.getAndUpdate(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }

    private static final class DefaultCovSubscription implements CovSubscription {
        private final BacNetClientBase client;
        private final BacNetObject object;
        private final int processId;
        private final int lifetime;
        private final boolean confirmed;
        private final SubscribeCOVRequest request;
        private final ForwardingAdapter listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DefaultCovSubscription(BacNetClientBase client, BacNetObject object, int processId, int lifetime,
                boolean confirmed, SubscribeCOVRequest request, ForwardingAdapter listener) {
            this.client = client;
            this.object = object;
            this.processId = processId;
            this.lifetime = lifetime;
            this.confirmed = confirmed;
            this.request = request;
            this.listener = listener;
        }

        @Override
        public BacNetObject getObject() {
            return object;
        }

        @Override
        public int getSubscriberProcessIdentifier() {
            return processId;
        }

        @Override
        public int getLifetime() {
            return lifetime;
        }

        @Override
        public boolean isConfirmed() {
            return confirmed;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            try {
                // BACnet4J's copy constructor creates the cancellation form:
                // same process/object, with confirmed-notifications and lifetime omitted.
                client.localDevice.send(object.getDevice().getBacNet4jAddress(), new SubscribeCOVRequest(request)).get();
            } catch (BACnetException e) {
                throw new BacNetClientException("Unable to cancel COV subscription for object " + object, e);
            } finally {
                client.localDevice.getEventHandler().removeListener(listener);
            }
        }
    }
}
