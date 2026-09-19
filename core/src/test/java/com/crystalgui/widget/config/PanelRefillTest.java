package com.crystalgui.widget.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A refill keeps what it places the same way, and nothing the last fill did to it.</b>
 *
 * <p>What a selection-driven panel costs is the rows it builds; a refill over a subject of the same shape builds
 * none. The failures worth pinning are the ones reuse makes possible: a row still reading the old subject, and a
 * decoration from the last fill acting on it.</p>
 */
public class PanelRefillTest {

    private static Configurator only(ConfiguratorPanel panel) {
        assertEquals(1, panel.children().size());
        return (Configurator) panel.children().get(0);
    }

    @Test
    public void aRowOfTheSameShapeIsKeptAndBoundToTheNewValue() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        Property<String> first = Property.of("first");
        Property<String> second = Property.of("second");
        panel.refill(form -> form.prop(ConfigDescriptor.text("name", "Name"), first));
        Configurator row = only(panel);

        ConfigDescriptor next = ConfigDescriptor.text("name", "Name");
        panel.refill(form -> form.prop(next, second));

        assertSame("the row was rebuilt", row, only(panel));
        ValueControl<?> control = (ValueControl<?>) row.control();
        assertSame("the control still edits the old value", second, control.property());
        assertEquals("second", control.getValue());
        assertSame("the control still asks the old descriptor", next, control.descriptor());
        assertSame(control, panel.control("name"));
    }

    @Test
    public void aRowOfAnotherShapeIsReplaced() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.refill(form -> form.prop(ConfigDescriptor.text("name", "Name"), Property.of("a")));
        Configurator row = only(panel);

        panel.refill(form -> form.prop(ConfigDescriptor.text("name", "Title"), Property.of("a")));

        assertNotSame(row, only(panel));
    }

    /** What the last fill hung on a row, and the classes it added, are gone before the next fill sees it. */
    @Test
    public void aKeptRowShedsWhatTheLastFillDidToIt() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        Signal.Action poke = new Signal.Action();
        AtomicInteger heard = new AtomicInteger();
        panel.refill(form -> {
            Configurator row = form.prop(ConfigDescriptor.text("name", "Name"), Property.of("a"));
            row.addClass("__set__");
            row.setDisplayed(false);
            row.decorations().add(poke.connect(heard::incrementAndGet));
        });

        panel.refill(form -> form.prop(ConfigDescriptor.text("name", "Name"), Property.of("b")));
        Configurator row = only(panel);
        poke.emit();

        assertFalse("a class the last fill added survived", row.hasClass("__set__"));
        assertTrue("a row the last fill hid is still hidden", row.isDisplayed());
        assertEquals("a listener the last fill attached still runs", 0, heard.get());
    }

    @Test
    public void keptRowsTakeTheOrderTheFillWrote() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.refill(form -> {
            form.prop(ConfigDescriptor.text("a", "A"), Property.of(""));
            form.prop(ConfigDescriptor.text("b", "B"), Property.of(""));
        });
        UIElement a = panel.children().get(0);
        UIElement b = panel.children().get(1);

        panel.refill(form -> {
            form.prop(ConfigDescriptor.text("b", "B"), Property.of(""));
            form.prop(ConfigDescriptor.text("c", "C"), Property.of(""));
            form.prop(ConfigDescriptor.text("a", "A"), Property.of(""));
        });

        List<UIElement> rows = panel.children();
        assertEquals(3, rows.size());
        assertSame(b, rows.get(0));
        assertSame(a, rows.get(2));
    }

    @Test
    public void aGroupIsKeptByItsTitleAndWhatItNoLongerHoldsGoes() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.refill(form -> {
            ConfigForm about = form.group("About");
            about.prop(ConfigDescriptor.text("a", "A"), Property.of(""));
            about.prop(ConfigDescriptor.text("b", "B"), Property.of(""));
        });
        ConfiguratorGroup group = (ConfiguratorGroup) panel.children().get(0);
        UIElement a = group.content().children().get(0);

        panel.refill(form -> form.group("About").prop(ConfigDescriptor.text("a", "A"), Property.of("")));

        assertSame(group, panel.children().get(0));
        assertEquals(List.of(a), group.content().children());
    }

    @Test
    public void aCustomElementIsKeptOnlyWhenItCanAdoptItsReplacement() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.refill(form -> {
            form.custom(new Plain());
            form.custom(new Adopting("first"));
        });
        UIElement plain = panel.children().get(0);
        Adopting adopting = (Adopting) panel.children().get(1);

        Adopting[] returned = new Adopting[1];
        panel.refill(form -> {
            form.custom(new Plain());
            returned[0] = form.custom(new Adopting("second"));
        });

        assertNotSame("a custom element that cannot adopt was kept", plain, panel.children().get(0));
        assertSame(adopting, panel.children().get(1));
        assertSame("the form did not hand back the element on screen", adopting, returned[0]);
        assertEquals("second", adopting.shows);
    }

    @Test
    public void aFillThatWritesNothingEmptiesThePanel() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.refill(form -> form.prop(ConfigDescriptor.text("a", "A"), Property.of("")));

        assertFalse(panel.refill(form -> { }));
        assertTrue(panel.children().isEmpty());
        assertTrue(panel.controls().isEmpty());
    }

    private static final class Plain extends UIElement {
    }

    private static final class Adopting extends UIElement implements Refillable<Adopting> {
        String shows;

        Adopting(String shows) {
            this.shows = shows;
        }

        @Override
        public boolean adopt(Adopting fresh) {
            shows = fresh.shows;
            return true;
        }
    }
}
