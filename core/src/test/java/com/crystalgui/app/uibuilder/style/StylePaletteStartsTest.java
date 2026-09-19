package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.StyleValue;

/**
 * Every name the palette offers has to start at a value its own parser reads.
 *
 * <p>A pick writes the starting value inline, and {@code LiveEdits.setInline} treats a value that computes to null as
 * a malformed edit: it logs and changes nothing. So a name with no startable value is offered, picked, and silently
 * does nothing — which is what {@code backdrop-filter} did, whose initial is null because an element with no filter
 * must not capture and blur the surface behind it to draw nothing.</p>
 */
public class StylePaletteStartsTest {

    /** Every name the palette offers, the way {@code PropertyPalette.offered} builds it. */
    private static List<String> offered() {
        TreeSet<String> names = new TreeSet<>();
        for (StyleProperty<?> property : StylePropertyRegistry.all()) names.add(PropertyPalette.written(property));
        return List.copyOf(names);
    }

    @Test
    public void everyOfferedNameHasAStartingValue() {
        List<String> empty = new ArrayList<>();
        for (String name : offered()) {
            if (DeclarationList.initialOf(name).isBlank()) empty.add(name);
        }
        assertTrue("these have no value to start at, so picking one writes nothing: " + empty
                + " -- give each a DeclarationList.STARTERS entry", empty.isEmpty());
    }

    @Test
    public void everyStartingValueParses() {
        List<String> refused = new ArrayList<>();
        for (String name : offered()) {
            StyleProperty<?> property = StylePropertyRegistry.byName(name);
            if (property == null) continue;   // a shorthand: DeclarationParser splits it, not one parser
            String start = DeclarationList.initialOf(name);
            StyleValue<?> parsed = property.valueParser.parse(start);
            if (parsed == null || parsed.compute() == null) refused.add(name + ": '" + start + "'");
        }
        assertTrue("picked from the palette, these are refused by their own parser: " + refused, refused.isEmpty());
    }

    @Test
    public void backdropFilterStartsAsGlass() {
        String start = DeclarationList.initialOf("backdrop-filter");
        StyleValue<?> parsed = StylePropertyRegistry.BACKDROP_FILTER.valueParser.parse(start);
        assertNotNull("a pick of backdrop-filter must land: " + start, parsed == null ? null : parsed.compute());
    }
}
