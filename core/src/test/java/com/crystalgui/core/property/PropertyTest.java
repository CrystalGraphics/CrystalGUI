package com.crystalgui.core.property;

import com.crystalgui.core.signal.Signal;
import org.junit.Assert;
import org.junit.Test;

public class PropertyTest {

    @Test
    public void shouldEmitOnChange() {
        Property<String> prop = new Property<String>("initial");
        final int[] count = {0};
        prop.changed.connect(new Signal.Pair.Listener<String, String>() {
            @Override public void accept(String o, String n) { count[0]++; }
        });

        prop.set("updated");
        Assert.assertEquals(1, count[0]);
        Assert.assertEquals("updated", prop.get());
    }

    @Test
    public void shouldNotEmitWhenValueIsEqual() {
        Property<String> prop = new Property<String>("same");
        final int[] count = {0};
        prop.changed.connect(new Signal.Pair.Listener<String, String>() {
            @Override public void accept(String o, String n) { count[0]++; }
        });

        prop.set("same");
        Assert.assertEquals(0, count[0]);
    }

    @Test
    public void shouldBindOneWay() {
        Property<Integer> source = new Property<Integer>(10);
        Property<Integer> target = new Property<Integer>(0);
        target.bindTo(source);

        Assert.assertEquals(Integer.valueOf(10), target.get());

        source.set(42);
        Assert.assertEquals(Integer.valueOf(42), target.get());
    }

    @Test
    public void shouldBindBidirectionallyWithoutLooping() {
        Property<String> a = new Property<String>("a");
        Property<String> b = new Property<String>("b");
        b.bindBidirectional(a);

        Assert.assertEquals("a", b.get());
        Assert.assertEquals("a", a.get());

        a.set("x");
        Assert.assertEquals("x", b.get());

        b.set("y");
        Assert.assertEquals("y", a.get());
    }

    @Test
    public void shouldDisconnectBinding() {
        Property<Integer> source = new Property<Integer>(1);
        Property<Integer> target = new Property<Integer>(0);
        com.crystalgui.core.signal.Connection conn = target.bindTo(source);

        source.set(5);
        Assert.assertEquals(Integer.valueOf(5), target.get());

        conn.disconnect();
        source.set(99);
        Assert.assertEquals(Integer.valueOf(5), target.get());
    }
}
