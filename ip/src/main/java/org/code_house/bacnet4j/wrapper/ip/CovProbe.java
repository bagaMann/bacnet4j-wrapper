/*
 * Copyright (c) 2018-2026 Code-House
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.code_house.bacnet4j.wrapper.ip;

import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.CovListener;
import org.code_house.bacnet4j.wrapper.api.CovSubscription;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;

/** Command-line BACnet/IP probe for discovery, object inspection and COV validation. */
public final class CovProbe {
    private static final long DISCOVERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private CovProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            usage();
            System.exit(2);
        }

        String mode = args[0].toLowerCase();
        String localIp = args[1];
        String broadcast = args[2];
        int localDeviceId = Integer.parseInt(args[3]);

        // Bind the BACnet/IP socket to the wildcard address. Some BACnet devices, including
        // the IQ3 used by this probe, answer Who-Is with a directed-broadcast I-Am. A socket
        // bound only to the interface's unicast address does not receive that datagram on Linux.
        // The broadcast argument still selects the BACnet/IP network and /24 broadcast target.
        BacNetClient client = new BacNetIpClient(broadcast, localDeviceId);
        client.start();
        try {
            System.out.println("BACnet/IP client started as device " + localDeviceId
                + " (requested interface " + localIp + ", broadcast " + broadcast + ")");

            switch (mode) {
                case "discover":
                    requireArgs(args, 4);
                    discover(client);
                    break;
                case "services":
                    requireArgs(args, 5);
                    showServices(client, Integer.parseInt(args[4]));
                    break;
                case "objects":
                    requireArgs(args, 5);
                    listObjects(client, Integer.parseInt(args[4]));
                    break;
                case "cov-info":
                    requireArgs(args, 7);
                    showCovInfo(client,
                        Integer.parseInt(args[4]),
                        Type.valueOf(args[5].toUpperCase()),
                        Integer.parseInt(args[6]));
                    break;
                case "properties":
                    requireArgs(args, 7);
                    showProperties(client,
                        Integer.parseInt(args[4]),
                        Type.valueOf(args[5].toUpperCase()),
                        Integer.parseInt(args[6]));
                    break;
                case "cov":
                    if (args.length < 7 || args.length > 9) {
                        usage();
                        System.exit(2);
                    }
                    runCov(client,
                        Integer.parseInt(args[4]),
                        Type.valueOf(args[5].toUpperCase()),
                        Integer.parseInt(args[6]),
                        args.length >= 8 ? Integer.parseInt(args[7]) : 120,
                        args.length >= 9 ? Integer.parseInt(args[8]) : 60);
                    break;
                case "cov-renew":
                    if (args.length < 7 || args.length > 10) {
                        usage();
                        System.exit(2);
                    }
                    runCovRenew(client,
                        Integer.parseInt(args[4]),
                        Type.valueOf(args[5].toUpperCase()),
                        Integer.parseInt(args[6]),
                        args.length >= 8 ? Integer.parseInt(args[7]) : 30,
                        args.length >= 9 ? Integer.parseInt(args[8]) : 20,
                        args.length >= 10 ? Integer.parseInt(args[9]) : 20);
                    break;
                case "cov-restart":
                    if (args.length < 7 || args.length > 9) {
                        usage();
                        System.exit(2);
                    }
                    runCovRestart(client,
                        Integer.parseInt(args[4]),
                        Type.valueOf(args[5].toUpperCase()),
                        Integer.parseInt(args[6]),
                        args.length >= 8 ? Integer.parseInt(args[7]) : 300,
                        args.length >= 9 ? Integer.parseInt(args[8]) : 30);
                    break;
                default:
                    usage();
                    System.exit(2);
            }
        } finally {
            client.stop();
        }
    }

    private static void discover(BacNetClient client) {
        Set<Device> devices = client.discoverDevices(DISCOVERY_TIMEOUT_MS);
        if (devices.isEmpty()) {
            System.out.println("No BACnet devices discovered.");
            return;
        }
        devices.stream()
            .sorted(Comparator.comparingInt(Device::getInstanceNumber))
            .forEach(device -> System.out.println("DEVICE id=" + device.getInstanceNumber()
                + " name=\"" + device.getName() + "\" model=\"" + device.getModelName()
                + "\" vendor=\"" + device.getVendorName() + "\" network=" + device.getNetworkNumber()
                + " address=" + device));
    }

    private static void showServices(BacNetClient client, int targetDeviceId) {
        Device target = findDevice(client, targetDeviceId);
        BacNetObject deviceObject = new BacNetObject(target, targetDeviceId, Type.DEVICE);
        Object value = client.getObjectPropertyValue(deviceObject, "protocol-services-supported", encodable -> encodable);
        if (!(value instanceof ServicesSupported)) {
            throw new IllegalStateException("Unexpected protocol-services-supported value: " + value);
        }

        ServicesSupported services = (ServicesSupported) value;
        System.out.println("Device " + targetDeviceId + " protocol-services-supported:");
        printService("readProperty", services.isReadProperty());
        printService("readPropertyMultiple", services.isReadPropertyMultiple());
        printService("writeProperty", services.isWriteProperty());
        printService("writePropertyMultiple", services.isWritePropertyMultiple());
        printService("subscribeCOV", services.isSubscribeCov());
        printService("subscribeCOVProperty", services.isSubscribeCovProperty());
        printService("confirmedCOVNotification", services.isConfirmedCovNotification());
        printService("unconfirmedCOVNotification", services.isUnconfirmedCovNotification());
    }

    private static void printService(String name, boolean supported) {
        System.out.printf("  %-28s %s%n", name, supported ? "SUPPORTED" : "not supported");
    }

    private static void listObjects(BacNetClient client, int targetDeviceId) {
        Device target = findDevice(client, targetDeviceId);
        System.out.println("Discovered target: " + target + " name=\"" + target.getName()
            + "\" model=\"" + target.getModelName() + "\" vendor=\"" + target.getVendorName() + "\"");

        List<BacNetObject> objects = client.getDeviceObjects(target);
        objects.stream()
            .sorted(Comparator.comparing((BacNetObject object) -> object.getType().name())
                .thenComparingInt(BacNetObject::getId))
            .forEach(object -> System.out.println("OBJECT type=" + object.getType().name()
                + " id=" + object.getId() + " name=\"" + object.getName()
                + "\" description=\"" + object.getDescription() + "\" units=\"" + object.getUnits() + "\""));
        System.out.println("Objects: " + objects.size());
    }

    private static void showCovInfo(BacNetClient client, int targetDeviceId, Type objectType, int objectInstance) {
        BacNetObject object = findObject(client, targetDeviceId, objectType, objectInstance);
        Object presentValue = client.getPresentValue(object, encodable -> encodable);
        Object covIncrement = client.getObjectPropertyValue(object, "cov-increment", encodable -> encodable);

        System.out.println("COV object: " + object + " name=\"" + object.getName() + "\"");
        System.out.println("Present_Value: " + presentValue);
        System.out.println("COV_Increment: " + covIncrement);
        System.out.println("Units: " + object.getUnits());
    }

    private static void showProperties(BacNetClient client, int targetDeviceId, Type objectType, int objectInstance) {
        BacNetObject object = findObject(client, targetDeviceId, objectType, objectInstance);
        System.out.println("Properties for: " + object + " name="" + object.getName() + """);
        printProperty(client, object, "status-flags");
        printProperty(client, object, "event-state");
        printProperty(client, object, "out-of-service");
    }

    private static void printProperty(BacNetClient client, BacNetObject object, String property) {
        Object value = client.getObjectPropertyValue(object, property, encodable -> encodable);
        System.out.println("  " + property + ": " + value);
    }

    private static void runCov(BacNetClient client, int targetDeviceId, Type objectType,
            int objectInstance, int lifetime, int waitSeconds) throws InterruptedException {
        BacNetObject object = findObject(client, targetDeviceId, objectType, objectInstance);

        System.out.println("Monitoring: " + object + " name=\"" + object.getName() + "\"");
        Object initialValue = client.getPresentValue(object, encodable -> encodable);
        System.out.println("Initial Present_Value: " + initialValue);

        CountDownLatch notification = new CountDownLatch(1);
        try (CovSubscription subscription = client.subscribeCov(object, lifetime, false, new CovListener() {
                @Override
                public void onCovNotification(BacNetObject changedObject, com.serotonin.bacnet4j.type.Encodable presentValue,
                        long timeRemaining) {
                    System.out.println("COV: " + changedObject + " Present_Value=" + presentValue
                        + " timeRemaining=" + timeRemaining);
                    notification.countDown();
                }

                @Override
                public void onCovStatusFlags(BacNetObject changedObject,
                        com.serotonin.bacnet4j.type.Encodable statusFlags, long timeRemaining) {
                    System.out.println("COV: " + changedObject + " Status_Flags=" + statusFlags
                        + " timeRemaining=" + timeRemaining);
                }

                @Override
                public void onCovEventState(BacNetObject changedObject,
                        com.serotonin.bacnet4j.type.Encodable eventState, long timeRemaining) {
                    System.out.println("COV: " + changedObject + " Event_State=" + eventState
                        + " timeRemaining=" + timeRemaining);
                }

                @Override
                public void onCovOutOfService(BacNetObject changedObject,
                        com.serotonin.bacnet4j.type.Encodable outOfService, long timeRemaining) {
                    System.out.println("COV: " + changedObject + " Out_Of_Service=" + outOfService
                        + " timeRemaining=" + timeRemaining);
                }
            })) {
            System.out.println("COV subscription active: processId=" + subscription.getSubscriberProcessIdentifier()
                + " lifetime=" + subscription.getLifetime() + " confirmed=" + subscription.isConfirmed());
            boolean received = notification.await(waitSeconds, TimeUnit.SECONDS);
            System.out.println(received ? "COV notification received." : "No COV notification received within wait window.");
        }
        System.out.println("COV subscription closed.");
    }

    private static void runCovRenew(BacNetClient client, int targetDeviceId, Type objectType,
            int objectInstance, int lifetime, int renewAfterSeconds, int waitAfterRenewSeconds)
            throws InterruptedException {
        if (renewAfterSeconds <= 0 || renewAfterSeconds >= lifetime) {
            throw new IllegalArgumentException("renewAfterSeconds must be greater than zero and less than lifetime");
        }
        if (waitAfterRenewSeconds < 0) {
            throw new IllegalArgumentException("waitAfterRenewSeconds must not be negative");
        }

        BacNetObject object = findObject(client, targetDeviceId, objectType, objectInstance);
        System.out.println("Monitoring renewal: " + object + " name=\"" + object.getName() + "\"");
        Object initialValue = client.getPresentValue(object, encodable -> encodable);
        System.out.println("Initial Present_Value: " + initialValue);

        CountDownLatch initialNotification = new CountDownLatch(1);
        try (CovSubscription subscription = client.subscribeCov(object, lifetime, false,
                (changedObject, presentValue, timeRemaining) -> {
                    System.out.println("COV: " + changedObject + " Present_Value=" + presentValue
                        + " timeRemaining=" + timeRemaining);
                    initialNotification.countDown();
                })) {
            int processId = subscription.getSubscriberProcessIdentifier();
            System.out.println("COV subscription active: processId=" + processId
                + " lifetime=" + subscription.getLifetime() + " confirmed=" + subscription.isConfirmed());

            boolean received = initialNotification.await(Math.min(renewAfterSeconds, 5), TimeUnit.SECONDS);
            System.out.println(received ? "Initial COV notification received." : "No initial COV notification within probe window.");

            long remainingBeforeRenewMs = TimeUnit.SECONDS.toMillis(renewAfterSeconds)
                - Math.min(TimeUnit.SECONDS.toMillis(renewAfterSeconds), TimeUnit.SECONDS.toMillis(5));
            if (received) {
                remainingBeforeRenewMs = TimeUnit.SECONDS.toMillis(Math.max(0, renewAfterSeconds - 1));
            }
            if (remainingBeforeRenewMs > 0) {
                Thread.sleep(remainingBeforeRenewMs);
            }

            System.out.println("Renewing COV subscription: processId=" + processId);
            subscription.renew();
            System.out.println("COV subscription renewed: processId=" + subscription.getSubscriberProcessIdentifier()
                + " lifetime=" + subscription.getLifetime() + " closed=" + subscription.isClosed());

            if (waitAfterRenewSeconds > 0) {
                System.out.println("Waiting " + waitAfterRenewSeconds + " seconds after renewal...");
                Thread.sleep(TimeUnit.SECONDS.toMillis(waitAfterRenewSeconds));
            }
        }
        System.out.println("COV subscription closed after renewal test.");
    }

    private static void runCovRestart(BacNetClient client, int targetDeviceId, Type objectType,
            int objectInstance, int lifetime, int waitAfterRenewSeconds) throws InterruptedException {
        if (lifetime <= 0) {
            throw new IllegalArgumentException("lifetime must be greater than zero");
        }
        if (waitAfterRenewSeconds <= 0) {
            throw new IllegalArgumentException("waitAfterRenewSeconds must be greater than zero");
        }

        BacNetObject object = findObject(client, targetDeviceId, objectType, objectInstance);
        System.out.println("Monitoring restart recovery: " + object + " name=\"" + object.getName() + "\"");
        Object initialValue = client.getPresentValue(object, encodable -> encodable);
        System.out.println("Initial Present_Value: " + initialValue);

        CountDownLatch initialNotification = new CountDownLatch(1);
        CountDownLatch notificationAfterRecovery = new CountDownLatch(1);
        AtomicBoolean recoveryArmed = new AtomicBoolean();
        AtomicInteger notificationCount = new AtomicInteger();

        try (CovSubscription subscription = client.subscribeCov(object, lifetime, false,
                (changedObject, presentValue, timeRemaining) -> {
                    int count = notificationCount.incrementAndGet();
                    System.out.println("COV[" + count + "]: " + changedObject + " Present_Value=" + presentValue
                        + " timeRemaining=" + timeRemaining);
                    initialNotification.countDown();
                    if (recoveryArmed.get()) {
                        notificationAfterRecovery.countDown();
                    }
                })) {
            int processId = subscription.getSubscriberProcessIdentifier();
            System.out.println("COV subscription active: processId=" + processId
                + " lifetime=" + subscription.getLifetime() + " confirmed=" + subscription.isConfirmed());

            boolean received = initialNotification.await(10, TimeUnit.SECONDS);
            System.out.println(received ? "Initial COV notification received." : "No initial COV notification within 10 seconds.");
            System.out.println("Local subscription before outage: processId=" + processId
                + " closed=" + subscription.isClosed());

            Scanner scanner = new Scanner(System.in);
            System.out.println();
            System.out.println("POWER OFF the BACnet device now. Keep it off, then press Enter to attempt renew while it is offline.");
            scanner.nextLine();

            System.out.println("Attempting renew while device is OFFLINE: processId=" + processId);
            boolean offlineRenewFailed = false;
            try {
                subscription.renew();
                System.out.println("WARNING: renew succeeded while the device was expected to be offline.");
            } catch (RuntimeException e) {
                offlineRenewFailed = true;
                System.out.println("Expected renew failure while offline: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            }
            System.out.println("Local subscription after offline renew attempt: processId=" + processId
                + " closed=" + subscription.isClosed());

            System.out.println();
            System.out.println("POWER ON the BACnet device now. Wait until BACnet is fully online, then press Enter to retry renew on the SAME subscription.");
            scanner.nextLine();

            recoveryArmed.set(true);
            System.out.println("Retrying renew after device recovery: processId=" + processId
                + " previousOfflineRenewFailed=" + offlineRenewFailed);
            subscription.renew();
            System.out.println("Recovery renew accepted: processId=" + subscription.getSubscriberProcessIdentifier()
                + " lifetime=" + subscription.getLifetime() + " closed=" + subscription.isClosed());

            boolean recovered = notificationAfterRecovery.await(waitAfterRenewSeconds, TimeUnit.SECONDS);
            System.out.println(recovered
                ? "Fresh COV notification received after recovery renew: subscription recovered."
                : "No fresh COV notification received after recovery renew within wait window.");
        }
        System.out.println("COV outage/recovery test finished; subscription closed.");
    }

    private static BacNetObject findObject(BacNetClient client, int targetDeviceId, Type objectType, int objectInstance) {
        Device target = findDevice(client, targetDeviceId);
        System.out.println("Discovered target: " + target);
        return client.getDeviceObjects(target).stream()
            .filter(candidate -> candidate.getType() == objectType)
            .filter(candidate -> candidate.getId() == objectInstance)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Object " + objectType + ":" + objectInstance + " was not found"));
    }

    private static Device findDevice(BacNetClient client, int targetDeviceId) {
        System.out.println("Discovering target device " + targetDeviceId + "...");
        return client.discoverDevices(DISCOVERY_TIMEOUT_MS).stream()
            .filter(device -> device.getInstanceNumber() == targetDeviceId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Target device " + targetDeviceId + " was not discovered"));
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length != count) {
            usage();
            System.exit(2);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  CovProbe discover  <localIp> <broadcast> <localDeviceId>");
        System.err.println("  CovProbe services  <localIp> <broadcast> <localDeviceId> <targetDeviceId>");
        System.err.println("  CovProbe objects   <localIp> <broadcast> <localDeviceId> <targetDeviceId>");
        System.err.println("  CovProbe cov-info  <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance>");
        System.err.println("  CovProbe properties <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance>");
        System.err.println("  CovProbe cov       <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [waitSeconds]");
        System.err.println("  CovProbe cov-renew <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [renewAfterSeconds] [waitAfterRenewSeconds]");
        System.err.println("  CovProbe cov-restart <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [waitAfterRenewSeconds]");
    }
}
