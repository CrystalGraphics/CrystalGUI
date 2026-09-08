package com.crystalgui.serialization.style;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import com.crystalgraphics.util.io.CgIO;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>Every declaration this engine actually ships, written back out and read in again.</b>
 *
 * <p>{@link StyleValueRoundTripTest} checks each property's INITIAL value, which is the one value
 * guaranteed to exist and the weakest possible example: {@code width} starts at {@code auto} and would
 * pass while {@code 100px} failed. This is the other half — several thousand real declarations, written
 * by hand across ten user-agent parts, two themes and the app sheets, covering the shapes people
 * genuinely write.</p>
 *
 * <p>The corpus is the sheets rather than a table of examples on purpose. A table is a list somebody
 * maintains, and the failure this whole mechanism exists to prevent was a list going quietly out of
 * step with the registry. Anything anybody adds to a stylesheet tomorrow is tested tomorrow.</p>
 *
 * <p>Read through the real parser rather than lifted out with a regex, and that is not a detail: most
 * of what a sheet declares is a SHORTHAND. {@code padding-all} expands into four longhands and
 * {@code border-radius} into eight, so the properties that actually hold the values never appear as
 * authored text at all — a regex over the same files covered 46 properties where this covers far
 * more.</p>
 */
public class ShippedStyleRoundTripTest {

    @Test
    public void everyShippedDeclarationSurvivesARoundTrip() {
        UIElementRegistry.bootstrap();

        List<String> failures = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        int checked = 0;

        for (String path : sheets()) {
            String css = CgIO.loadSource(path);
            if (css == null) continue;
            for (StyleRule rule : StyleSheet.parse(css).getRules()) {
                for (StyleRule.Declaration declaration : rule.declarations()) {
                    Object value = declaration.value().compute();
                    if (value == null) continue;   // the sheet's own problem, not this mechanism's
                    checked++;
                    covered.add(declaration.property().name);
                    String failure = check(declaration.property(), value, path);
                    if (failure != null) failures.add(failure);
                }
            }
        }

        // A FLOOR, so the corpus cannot quietly stop covering things -- a path that stops resolving, or a
        // parser change that drops declarations, would otherwise leave this passing on nothing. It reads
        // 993 declarations across 55 properties today.
        assertTrue("the corpus is not being read: " + checked + " declarations across "
                + covered.size() + " properties", checked > 800 && covered.size() >= 50);
        assertTrue("Real declarations that cannot survive being written and read back (" + failures.size()
                + " of " + checked + " checked, across " + covered.size() + " properties):"
                + System.lineSeparator() + "  "
                + String.join(System.lineSeparator() + "  ",
                        failures.subList(0, Math.min(failures.size(), 25))),
                failures.isEmpty());
    }

    /** Write the parsed value back out, read that, and compare the two values. */
    @SuppressWarnings("unchecked")
    private static <V> String check(StyleProperty<V> property, Object parsed, String where) {
        V value = (V) parsed;
        String written;
        try {
            written = property.write(value);
        } catch (RuntimeException e) {
            return property.name + " = " + parsed + " (" + where + ") — write() threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        V back;
        try {
            back = property.valueParser.parse(written).compute();
        } catch (RuntimeException e) {
            return property.name + " wrote '" + written + "' (" + where + ") — reading it threw "
                    + e.getClass().getSimpleName();
        }
        if (back == null) {
            return property.name + " wrote '" + written + "' (" + where
                    + "), which its own parser could not read";
        }
        if (!value.equals(back)) {
            return property.name + " wrote '" + written + "' (" + where
                    + "), which read back as " + back + " rather than " + value;
        }
        return null;
    }

    /** The user-agent parts, the themes, and the app sheets — everything shipped. */
    private static List<String> sheets() {
        List<String> paths = new ArrayList<>(StyleSheetRegistry.DEFAULT_SHEET_PARTS.stream()
                .map(part -> part + ".css").toList());
        paths.add("crystalgui:ui/themes/base.css");
        paths.add("crystalgui:ui/themes/crystal-dark.css");
        paths.add("crystalgui:ui/styles/ore.css");
        paths.add("crystalgui:ui/styles/graph.css");
        paths.add("crystalgui:ui/styles/filetypes.css");
        paths.add("crystalgui:ui/styles/decorations.css");
        return paths;
    }
}
