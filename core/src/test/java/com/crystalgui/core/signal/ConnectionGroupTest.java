package com.crystalgui.core.signal;

import org.junit.Assert;
import org.junit.Test;

public class ConnectionGroupTest {

    @Test
    public void shouldDisconnectAllTrackedConnections() {
        Signal.Value<String> signal = new Signal.Value<>();
        ConnectionGroup group = new ConnectionGroup();
        final int[] counter = {0};

        group.add(signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { counter[0]++; }
        }));
        group.add(signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { counter[0]++; }
        }));

        signal.emit("a");
        Assert.assertEquals(2, counter[0]);

        group.disconnectAll();
        signal.emit("b");
        Assert.assertEquals(2, counter[0]);
        Assert.assertEquals(0, group.size());
    }

    @Test
    public void shouldBeIdempotent() {
        Signal.Value<String> signal = new Signal.Value<>();
        ConnectionGroup group = new ConnectionGroup();
        group.add(signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) {}
        }));

        group.disconnectAll();
        group.disconnectAll();
        Assert.assertEquals(0, group.size());
    }
}
