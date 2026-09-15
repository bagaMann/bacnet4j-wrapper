package org.code_house.bacnet4j.wrapper.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CovEventAdapterTest {

    @Test
    public void forwardsPresentValueForMatchingSubscription() {
        Device device = new Device(100, new byte[] {1, 2, 3, 4, 0, 1}, 0);
        BacNetObject object = new BacNetObject(device, 7, Type.ANALOG_INPUT, "AI7", "", null);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Encodable> value = new AtomicReference<>();

        CovEventAdapter adapter = new CovEventAdapter(42, object, (source, presentValue, remaining) -> {
            calls.incrementAndGet();
            value.set(presentValue);
            assertEquals(120L, remaining);
        });

        adapter.covNotificationReceived(new UnsignedInteger(42), device.getObjectIdentifier(),
            object.getBacNet4jIdentifier(), new UnsignedInteger(120),
            new SequenceOf<>(new PropertyValue(PropertyIdentifier.presentValue, new Real(21.5f))));

        assertEquals(1, calls.get());
        assertEquals(new Real(21.5f), value.get());
    }

    @Test
    public void ignoresDifferentSubscriberProcessIdentifier() {
        Device device = new Device(100, new byte[] {1, 2, 3, 4, 0, 1}, 0);
        BacNetObject object = new BacNetObject(device, 7, Type.ANALOG_INPUT, "AI7", "", null);
        AtomicReference<Encodable> value = new AtomicReference<>();
        CovEventAdapter adapter = new CovEventAdapter(42, object, (source, presentValue, remaining) -> value.set(presentValue));

        adapter.covNotificationReceived(new UnsignedInteger(43), device.getObjectIdentifier(),
            object.getBacNet4jIdentifier(), new UnsignedInteger(120),
            new SequenceOf<>(new PropertyValue(PropertyIdentifier.presentValue, new Real(21.5f))));

        assertNull(value.get());
    }
}
