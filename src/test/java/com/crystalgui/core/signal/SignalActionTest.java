package com.crystalgui.core.signal;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SignalActionTest {

    @Test
    public void shouldEmitToAllListeners() {
        Signal.Action signal = new Signal.Action();
        final int[] counter = {0};
        signal.connect(new Runnable() {
            @Override public void run() { counter[0]++; }
        });
        signal.connect(new Runnable() {
            @Override public void run() { counter[0]++; }
        });

        signal.emit();
        Assert.assertEquals(2, counter[0]);
    }

    @Test
    public void shouldAllowDisconnectDuringEmit() {
        final Signal.Action signal = new Signal.Action();
        final List<String> calls = new ArrayList<String>();
        final Connection[] conn = new Connection[1];

        conn[0] = signal.connect(new Runnable() {
            @Override public void run() {
                calls.add("first");
                conn[0].disconnect();
            }
        });
        signal.connect(new Runnable() {
            @Override public void run() { calls.add("second"); }
        });

        signal.emit();
        Assert.assertEquals(2, calls.size());

        calls.clear();
        signal.emit();
        Assert.assertEquals(1, calls.size());
        Assert.assertEquals("second", calls.get(0));
    }

    @Test
    public void shouldTrackConnectionCount() {
        Signal.Action signal = new Signal.Action();
        Assert.assertEquals(0, signal.connectionCount());

        Connection c1 = signal.connect(new Runnable() {
            @Override public void run() {}
        });
        Assert.assertEquals(1, signal.connectionCount());

        c1.disconnect();
        Assert.assertEquals(0, signal.connectionCount());
    }

    @Test
    public void shouldDisconnectAll() {
        Signal.Action signal = new Signal.Action();
        final int[] counter = {0};
        signal.connect(new Runnable() {
            @Override public void run() { counter[0]++; }
        });
        signal.connect(new Runnable() {
            @Override public void run() { counter[0]++; }
        });

        signal.emit();
        Assert.assertEquals(2, counter[0]);

        signal.disconnectAll();
        signal.emit();
        Assert.assertEquals(2, counter[0]);
        Assert.assertEquals(0, signal.connectionCount());
    }
}
