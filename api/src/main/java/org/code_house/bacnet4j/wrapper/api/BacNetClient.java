/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 *     https://www.gnu.org/licenses/gpl-3.0.txt
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.code_house.bacnet4j.wrapper.api;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Definition of bacnet client
 *
 * @author Łukasz Dywicki &lt;luke@code-house.org&gt;
 */
public interface BacNetClient {

    void start();

    void stop();

    CompletableFuture<Void> listenForDevices(DeviceDiscoveryListener discoveryListener);

    CompletableFuture<Void> listenForDevices(DeviceDiscoveryListener discoveryListener, Integer min, Integer max);

    CompletableFuture<Set<Device>> doDiscoverDevices(DeviceDiscoveryListener discoveryListener, long timeout);

    Set<Device> discoverDevices(long timeout);

    Set<Device> discoverDevices(DeviceDiscoveryListener discoveryListener, long timeout);

    @Deprecated
    default List<Property> getDeviceProperties(Device device) {
        return getDeviceObjects(device).stream()
            .map(obj -> new Property(obj.getDevice(), obj.getId(), obj.getType()))
            .collect(Collectors.toList());
    }

    List<BacNetObject> getDeviceObjects(Device device);

    @Deprecated
    default List<Object> getPropertyValues(List<Property> properties) {
        return getPresentValues(properties.stream()
            .map(Property::toBacNetObject)
            .collect(Collectors.toList())
        );
    }

    List<java.lang.Object> getPresentValues(List<BacNetObject> objects);

    @Deprecated
    default <T> T getPropertyValue(Property property, BacNetToJavaConverter<T> converter) {
        return getPresentValue(property.toBacNetObject(), converter);
    }

    <T> T getPresentValue(BacNetObject object, BacNetToJavaConverter<T> converter);

    @Deprecated
    default <T> void setPropertyValue(Property property, T value, JavaToBacNetConverter<T> converter) {
        setPresentValue(property.toBacNetObject(), value, converter);
    }

    @Deprecated
    default <T> void setPropertyValue(Property property, T value, JavaToBacNetConverter<T> converter, int priority) {
        setPresentValue(property.toBacNetObject(), value, converter, priority);
    }

    @Deprecated
    default <T> void setPropertyValue(Property property, T value, JavaToBacNetConverter<T> converter, Priority priority) {
        setPresentValue(property.toBacNetObject(), value, converter, priority);
    }

    <T> void setPresentValue(BacNetObject object, T value, JavaToBacNetConverter<T> converter);
    <T> void setPresentValue(BacNetObject object, T value, JavaToBacNetConverter<T> converter, int priority);
    <T> void setPresentValue(BacNetObject object, T value, JavaToBacNetConverter<T> converter, Priority priority);

    <T> void setObjectPropertyValue(BacNetObject object, String property, T value, JavaToBacNetConverter<T> converter);
    <T> void setObjectPropertyValue(BacNetObject object, String property, T value, JavaToBacNetConverter<T> converter, int priority);
    <T> void setObjectPropertyValue(BacNetObject object, String property, T value, JavaToBacNetConverter<T> converter, Priority priority);

    List<String> getObjectPropertyNames(BacNetObject object);
    <T> T getObjectPropertyValue(BacNetObject object, String attribute, BacNetToJavaConverter<T> converter);

    List<Object> getObjectAttributeValues(BacNetObject object, List<String> properties);

    /**
     * Subscribe to Change of Value notifications for a BACnet object.
     *
     * @param object object to monitor
     * @param lifetime requested subscription lifetime in seconds; must be greater than zero
     * @param confirmed request confirmed COV notifications when true
     * @param listener notification listener
     * @return subscription handle; closing it cancels the subscription
     */
    CovSubscription subscribeCov(BacNetObject object, int lifetime, boolean confirmed, CovListener listener);
}
