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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.CovSubscription;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;

/**
 * Minimal command-line probe used to validate BACnet/IP COV against a real device.
 *
 * Arguments: localIp broadcast localDeviceId targetDeviceId objectType objectInstance
 *            [lifetimeSeconds] [waitSeconds]
 *
 * Example objectType values: ANALOG_INPUT, ANALOG_VALUE, BINARY_INPUT, BINARY_VALUE.
 */
public final class CovProbe {
    private CovProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 8) {
            System.err.println("Usage: CovProbe <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [waitSeconds]");
            System.exit(2);
        }

        String localIp = args[0];
        String broadcast = args[1];
        int localDeviceId = Integer.parseInt(args[2]);
        int targetDeviceId = Integer.parseInt(args[3]);
        Type objectType = Type.valueOf(args[4].toUpperCase());
        int objectInstance = Integer.parseInt(args[5]);
        int lifetime = args.length >= 7 ? Integer.parseInt(args[6]) : 120;
        int waitSeconds = args.length >= 8 ? Integer.parseInt(args[7]) : 60;

        BacNetClient client = new BacNetIpClient(localIp, broadcast, localDeviceId);
        client.start();
        try {
            System.out.println("BACnet/IP client started as device " + localDeviceId + " on " + localIp);
            System.out.println("Discovering target device " + targetDeviceId + "...");

            Set<Device> devices = client.discoverDevices(TimeUnit.SECONDS.toMillis(5));
            Device target = devices.stream()
                .filter(device -> device.getInstanceNumber() == targetDeviceId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Target device " + targetDeviceId + " was not discovered"));

            System.out.println("Discovered: " + target);
            List<BacNetObject> objects = client.getDeviceObjects(target);
            BacNetObject object = objects.stream()
                .filter(candidate -> candidate.getType() == objectType)
                .filter(candidate -> candidate.getId() == objectInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Object " + objectType + ":" + objectInstance + " was not found"));

            System.out.println("Monitoring: " + object + " name=\"" + object.getName() + "\"");
            Object initialValue = client.getPresentValue(object, encodable -> encodable);
            System.out.println("Initial Present_Value: " + initialValue);

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
