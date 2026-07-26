package com.crystalgui.core.property;

import com.crystalgui.core.signal.Connection;
import org.junit.Test;

import static org.junit.Assert.*;

public class PropertyTest {

    @Test
    public void bindToWithTransformAppliesImmediately() {
        Property<Integer> source = new Property<>(5);
        Property<String> target = new Property<>("");

        target.bindTo(source, i -> "n=" + i);

        assertEquals("n=5", target.get());
    }

    @Test
    public void bindToWithTransformPropagatesSourceChanges() {
        Property<Integer> source = new Property<>(5);
        Property<String> target = new Property<>("");

        target.bindTo(source, i -> "n=" + i);
        source.set(9);

        assertEquals("n=9", target.get());
    }

    @Test
    public void bindToWithTransformDisconnectStopsPropagation() {
        Property<Integer> source = new Property<>(1);
        Property<String> target = new Property<>("");

        Connection connection = target.bindTo(source, i -> "n=" + i);
        connection.disconnect();
        source.set(42);

        assertEquals("n=1", target.get());
    }

    @Test
    public void bindToWithTransformRejectsNullArguments() {
        Property<Integer> source = new Property<>(1);
        Property<String> target = new Property<>("");

        try {
            target.bindTo(null, i -> "n=" + i);
            fail("expected IllegalArgumentException for null source");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            target.bindTo(source, null);
            fail("expected IllegalArgumentException for null transform");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
