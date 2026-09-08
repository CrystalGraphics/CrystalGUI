package com.crystalgui.style.property.layout.grid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;

/**
 * <b>A grid can be written back as the CSS that made it.</b>
 *
 * <p>It could not, and that reached a person as Copy Attributes throwing: {@code write} refused every
 * template with real tracks in it, so an element carrying {@code grid-template-columns: repeat(3, 1fr)}
 * could neither be copied nor saved. The serialiser had been written all along — {@code toString} walks
 * the components and covers all seven track types — and only the wiring was missing.</p>
 *
 * <p>{@code grid-auto-*} and {@code grid-template-areas} had the same serialisers and the same gap, and
 * fell back to {@link String#valueOf}: they put {@code GridAuto[values=[1.0fr]]} on the clipboard, which
 * is not CSS and does not read back.</p>
 */
public class GridWritesItselfTest {

    private static <V> void roundTrips(StyleProperty<V> property, String css) {
        V parsed = property.valueParser.parse(css).compute();
        assertNotNull(css + " does not parse", parsed);

        String written = property.write(parsed);
        V back = property.valueParser.parse(written).compute();
        assertNotNull(css + " wrote '" + written + "', which its own parser could not read", back);
        assertEquals(css + " wrote '" + written + "'", parsed, back);
    }

    /** The form the crash was reported on. */
    @Test
    public void aRepeatWritesItself() {
        roundTrips(LayoutProperties.GRID_TEMPLATE_COLUMNS, "repeat(3, 1fr)");
        roundTrips(LayoutProperties.GRID_TEMPLATE_COLUMNS, "repeat(auto-fill, 100px)");
        roundTrips(LayoutProperties.GRID_TEMPLATE_ROWS, "repeat(auto-fit, minmax(100px, 1fr))");
    }

    /** Plain track lists, across every sizing function the type carries. */
    @Test
    public void everyTrackKindWritesItself() {
        roundTrips(LayoutProperties.GRID_TEMPLATE_COLUMNS, "100px 1fr auto");
        roundTrips(LayoutProperties.GRID_TEMPLATE_ROWS, "minmax(10px, 1fr) auto");
        roundTrips(LayoutProperties.GRID_TEMPLATE_ROWS, "min-content max-content");
        roundTrips(LayoutProperties.GRID_TEMPLATE_COLUMNS, "fit-content(200px) 50%");
    }

    /**
     * <b>And the order survives</b>, which is the whole reason the components are one list.
     *
     * <p>{@code 100px repeat(2, 1fr) 50px} is not the same grid as {@code 100px 50px repeat(2, 1fr)},
     * so a model that kept the plain tracks and the repetitions apart could not say which was meant.</p>
     */
    @Test
    public void aMixedTemplateKeepsItsOrder() {
        StyleProperty<GridTemplate> property = LayoutProperties.GRID_TEMPLATE_COLUMNS;
        GridTemplate parsed = property.valueParser.parse("100px repeat(2, 1fr) 50px").compute();
        assertNotNull(parsed);
        assertEquals(3, parsed.components().size());
        assertEquals("100px repeat(2, 1fr) 50px", property.write(parsed));
    }

    /** Named lines come back where they were. */
    @Test
    public void namedLinesWriteThemselves() {
        roundTrips(LayoutProperties.GRID_TEMPLATE_COLUMNS, "[start] 100px [middle] 1fr [end]");
    }

    /** The implicit tracks, which wrote a Java toString onto the clipboard. */
    @Test
    public void gridAutoWritesItself() {
        roundTrips(LayoutProperties.GRID_AUTO_COLUMNS, "1fr");
        roundTrips(LayoutProperties.GRID_AUTO_ROWS, "100px 1fr auto");
        roundTrips(LayoutProperties.GRID_AUTO_ROWS, "minmax(10px, 1fr)");
    }

    /** And the named areas, which wrote one too. */
    @Test
    public void gridTemplateAreasWritesItself() {
        roundTrips(LayoutProperties.GRID_TEMPLATE_AREAS, "\"a b\" \"c d\"");
        roundTrips(LayoutProperties.GRID_TEMPLATE_AREAS, "\"head head\" \"nav main\" \"foot foot\"");
    }

    /**
     * Every one of them spells "nothing" as {@code none}, and reads it back.
     *
     * <p>{@code StyleValueRoundTripTest} holds each property to this at its initial value; stating it
     * here as well is what says the spelling is deliberate rather than incidental — an empty declaration
     * is not something to put in a stylesheet or on a clipboard.</p>
     */
    @Test
    public void nothingIsSpelledNone() {
        assertEquals("none", LayoutProperties.GRID_TEMPLATE_COLUMNS.write(GridTemplate.EMPTY));
        assertEquals("none", LayoutProperties.GRID_AUTO_ROWS.write(GridAuto.EMPTY));
        assertEquals("none", LayoutProperties.GRID_TEMPLATE_AREAS.write(GridTemplateAreas.EMPTY));

        assertEquals(GridTemplate.EMPTY,
                LayoutProperties.GRID_TEMPLATE_COLUMNS.valueParser.parse("none").compute());
        assertEquals(GridAuto.EMPTY,
                LayoutProperties.GRID_AUTO_ROWS.valueParser.parse("none").compute());
        assertEquals(GridTemplateAreas.EMPTY,
                LayoutProperties.GRID_TEMPLATE_AREAS.valueParser.parse("none").compute());
    }
}
