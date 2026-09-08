package com.crystalgui.serialization.style;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Every registered property writes CSS its own parser reads back.</b>
 *
 * <p>This is the whole guarantee behind {@link StyleValueCodecs}. A style value travels as the CSS that
 * would produce it — written by {@link StyleProperty#write}, read by the parser the stylesheet uses —
 * and those two halves live on the same object precisely so they can be held to agreeing. Nothing else
 * checks it: a property whose writer drifts from its parser fails at the moment somebody copies an
 * element carrying it, which is a strange place to find out.</p>
 *
 * <p><b>It runs over the registry rather than a list.</b> The failure this replaces was fifteen
 * properties silently having no codec at all, and a hand-kept list of what to check would have gone
 * stale in exactly the same way. A property added tomorrow is checked tomorrow.</p>
 *
 * <p>Equality of the VALUE, not of the text: {@code #FFF} may come back {@code #FFFFFFFF} and that is
 * fine. What may not happen is a value that comes back different, or not at all.</p>
 */
public class StyleValueRoundTripTest {

    @Test
    public void everyPropertyReadsBackWhatItWrites() {
        UIElementRegistry.bootstrap();
        List<String> broken = new ArrayList<>();
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            String failure = check(property);
            if (failure != null) broken.add(failure);
        }
        assertTrue("These properties cannot survive being written and read back. Each needs a"
                + " StyleProperty.write() that its own ValueParser accepts — a subclass override, or"
                + " setWriter() where the property is declared:\n  " + String.join("\n  ", broken),
                broken.isEmpty());
    }

    /** The property's own initial value, out and back. Null when it survives. */
    private static <V> String check(StyleProperty<V> property) {
        V initial = property.initialValue;
        if (initial == null) return null;   // nothing to write, and nothing that could go wrong
        String written;
        try {
            written = property.write(initial);
        } catch (RuntimeException e) {
            return property.name + " — write() threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        V back;
        try {
            back = property.valueParser.parse(written).compute();
        } catch (RuntimeException e) {
            return property.name + " — wrote '" + written + "', and parsing it threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (back == null) {
            return property.name + " — wrote '" + written + "', which its own parser could not read";
        }
        if (!initial.equals(back)) {
            return property.name + " — wrote '" + written + "', which read back as a different value ("
                    + initial + " became " + back + ")";
        }
        return null;
    }
}
