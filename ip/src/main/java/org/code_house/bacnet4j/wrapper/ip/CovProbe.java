/*
 * Copyright (c) 2018-2026 Code-House
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.ip;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.CovSubscription;
import org.code_house.bacnet4j.wrapper.api.Device;

/**
 * Minimal command-line probe used to validate BACnet/IP COV against a real device.
 *
 * Arguments: localDeviceId targetDeviceId objectType objectInstance [lifetimeSeconds] [waitSeconds]
 */
public final class CovProbe {
    private CovProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            System.err.println("Usage: CovProbe <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [waitSeconds]");
            System.exit(2);
        }

        int localDeviceId = Integer.parseInt(args[0]);
        int targetDeviceId = Integer.parseInt(args[1]);
        String objectType = args[2];
        int objectInstance = Integer.parseInt(args[3]);
        int lifetime = args.length >= 5 ? Integer.parseInt(args[4]) : 120;
        int waitSeconds = args.length >= 6 ? Integer.parseInt(args[5]) : 60;

        BacNetClient client = new BacNetIpClient(localDeviceId);
        client.start();
        try {
            System.out.println("BACnet/IP client started as device " + localDeviceId);
            System.out.println("Discovering target device " + targetDeviceId + "...");

            List<Device> devices = client.discoverDevices(5, TimeUnit.SECONDS);
            Device target = devices.stream()
                .filter(device -> device.getInstanceNumber() == targetDeviceId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Target device " + targetDeviceId + " was not discovered"));

            System.out.println("Discovered: " + target);
            List<BacNetObject> objects = client.getDeviceObjects(target);
            BacNetObject object = objects.stream()
                .filter(candidate -> candidate.getType().toString().equalsIgnoreCase(objectType))
                .filter(candidate -> candidate.getInstanceNumber() == objectInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Object " + objectType + ":" + objectInstance + " was not found"));

            System.out.println("Monitoring: " + object);
            System.out.println("Initial Present_Value: " + client.getPresentValue(object));

            CountDownLatch notification = new CountDownLatch(1);
            try (CovSubscription subscription = client.subscribeCov(object, lifetime, false,
                    (changedObject, presentValue, timeRemaining) -> {
                        System.out.println("COV: " + changedObject + " Present_Value=" + presentValue
                            + " timeRemaining=" + timeRemaining);
                        notification.countDown();
                    })) {
                System.out.println("COV subscription active: processId=" + subscription.getSubscriberProcessIdentifier()
                    + " lifetime=" + subscription.getLifetime() + " confirmed=" + subscription.isConfirmed());
                boolean received = notification.await(waitSeconds, TimeUnit.SECONDS);
                System.out.println(received ? "COV notification received." : "No COV notification received within wait window.");
            }

            System.out.println("COV subscription closed.");
        } finally {
            client.stop();
        }
    }
}
