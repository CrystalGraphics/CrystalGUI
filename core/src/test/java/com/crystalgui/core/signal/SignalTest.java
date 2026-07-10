package com.crystalgui.core.signal;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SignalTest {

    @Test
    public void shouldEmitInRegistrationOrder() {
        Signal.Value<String> signal = new Signal.Value<>();
        final List<String> order = new ArrayList<String>();

        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { order.add("A"); }
        });
        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { order.add("B"); }
        });
        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { order.add("C"); }
        });

        signal.emit("test");
        Assert.assertEquals(3, order.size());
        Assert.assertEquals("A", order.get(0));
        Assert.assertEquals("B", order.get(1));
        Assert.assertEquals("C", order.get(2));
    }

    @Test
    public void shouldAllowDisconnectDuringEmit() {
        final Signal.Value<String> signal = new Signal.Value<>();
        final List<String> calls = new ArrayList<String>();
        final Connection[] conn = new Connection[1];

        conn[0] = signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) {
                calls.add("first");
                conn[0].disconnect();
            }
        });
        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { calls.add("second"); }
        });

        signal.emit("a");
        Assert.assertEquals(2, calls.size());
        Assert.assertEquals("first", calls.get(0));
        Assert.assertEquals("second", calls.get(1));

        calls.clear();
        signal.emit("b");
        Assert.assertEquals(1, calls.size());
        Assert.assertEquals("second", calls.get(0));
    }

    @Test
    public void shouldDisconnectIdempotently() {
        Signal.Value<String> signal = new Signal.Value<>();
        Connection conn = signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) {}
        });

        Assert.assertEquals(1, signal.connectionCount());
        conn.disconnect();
        Assert.assertEquals(0, signal.connectionCount());
        conn.disconnect();
        Assert.assertEquals(0, signal.connectionCount());
    }

    @Test
    public void shouldDisconnectAll() {
        Signal.Value<String> signal = new Signal.Value<>();
        final int[] counter = {0};
        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { counter[0]++; }
        });
        signal.connect(new Signal.Value.Listener<String>() {
            @Override public void accept(String v) { counter[0]++; }
        });

        signal.emit("x");
        Assert.assertEquals(2, counter[0]);

        signal.disconnectAll();
        signal.emit("y");
        Assert.assertEquals(2, counter[0]);
    }
}
