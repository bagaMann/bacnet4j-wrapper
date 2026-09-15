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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.serotonin.bacnet4j.type.constructed.ServicesSupported;
import org.code_house.bacnet4j.wrapper.api.BacNetClient;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
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

    private static void runCov(BacNetClient client, int targetDeviceId, Type objectType,
            int objectInstance, int lifetime, int waitSeconds) throws InterruptedException {
        Device target = findDevice(client, targetDeviceId);
        System.out.println("Discovered target: " + target);

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
        System.err.println("  CovProbe discover <localIp> <broadcast> <localDeviceId>");
        System.err.println("  CovProbe services <localIp> <broadcast> <localDeviceId> <targetDeviceId>");
        System.err.println("  CovProbe objects  <localIp> <broadcast> <localDeviceId> <targetDeviceId>");
        System.err.println("  CovProbe cov      <localIp> <broadcast> <localDeviceId> <targetDeviceId> <objectType> <objectInstance> [lifetimeSeconds] [waitSeconds]");
    }
}
