package com.crystalgui.style.node;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.layout.dimension.DimensionValue;
import com.crystalgui.style.property.layout.length.LPAValue;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyDimension;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The {@code em} unit — a length that is a multiple of the element's own {@code font-size}.
 *
 * <p>Tests the seam rather than the arithmetic: that the unit is <b>recognised</b> where a px length is,
 * that it is resolved <b>per element</b> rather than once per rule, and that it follows a font size that
 * changes after the rule was written. Those are the three things that make it different from every other
 * unit; the multiplication itself is one line and cannot be got wrong on its own.</p>
 */
public class EmUnitTest extends UiDocumentTestBase {

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The suffix must be tested before the bare-number fallback, not merely before {@code px}.
     *
     * <p>The failure mode if it is not is worse than "em is ignored": {@code Float.parseFloat("1.5em")}
     * throws, {@code doCompute} catches it and answers null, and {@code StyleEngine.toSlot} drops the
     * whole declaration with a warning about a value that is perfectly well formed.</p>
     */
    @Test
    public void anEmLengthParsesRatherThanFailing() {
        assertTrue(new LPAValue("1.5em").isFontRelative());
        assertTrue(new DimensionValue("2em").isFontRelative());
        assertFalse(new LPAValue("12px").isFontRelative());
        assertFalse(new DimensionValue("50%").isFontRelative());
        assertFalse("a bare number is px, not a unit-less em", new DimensionValue("8").isFontRelative());
    }

    /** An unresolved read answers against the reference size, not against zero and not by throwing. */
    @Test
    public void anUnresolvedEmUsesTheReferenceSize() {
        TaffyDimension computed = new DimensionValue("2em").compute();
        assertEquals(2f * FontRelative.REFERENCE_FONT_SIZE, computed.getValue(), 0.001f);
    }

    /** {@code em} is a length once resolved — a consumer switching on the type must not see a new case. */
    @Test
    public void aResolvedEmIsAnOrdinaryLength() {
        LengthPercentageAuto resolved = new LPAValue("1.5em").resolveAgainst(20f);
        assertTrue(resolved.isLength());
        assertEquals(30f, resolved.getValue(), 0.001f);
    }

    // ── Resolution through the cascade ──────────────────────────────────────────────────────────

    /**
     * The same rule gives two elements two different pixel values.
     *
     * <p>This is the property that forced the unit out of {@link com.crystalgui.style.property.StyleValue}
     * and into the engine: a parsed value is cached and shared by every element its rule matches, so an
     * {@code em} resolved there would give whichever element cascaded first its answer and hand it to all
     * the others.</p>
     */
    @Test
    public void oneRuleResolvesPerElement() {
        UINode root = new UINode();
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(
                ".boxed { padding-left: 2em; }"
                        + ".small { font-size: 5; }"
                        + ".large { font-size: 40; }"));

        UINode small = new UINode();
        small.addClass("boxed");
        small.addClass("small");
        UINode large = new UINode();
        large.addClass("boxed");
        large.addClass("large");
        root.append(small, large);

        settle(document);

        assertEquals("2em against font-size 5", 10f, padLeftOf(small), 0.001f);
        assertEquals("2em against font-size 40", 80f, padLeftOf(large), 0.001f);
    }

    /**
     * A font size written from Java after the sheet was applied still moves the {@code em}.
     *
     * <p>The case that made a one-pass resolution wrong. Picking the winning {@code font-size}
     * <em>declaration</em> out of the sheets is not enough — a widget writing its own size at IMPORTANT
     * beats every sheet and appears in no rule at all, which is exactly what {@code TextEditor} does to
     * its gutter on every zoom.</p>
     */
    @Test
    public void anImportantFontSizeStillDrivesTheEm() {
        UINode root = new UINode();
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse(
                ".boxed { padding-left: 2em; font-size: 10; }"));

        UINode box = new UINode();
        box.addClass("boxed");
        root.append(box);
        settle(document);
        assertEquals(20f, padLeftOf(box), 0.001f);

        // NOTHING ELSE IS CALLED. The font-size property's own listener has to notice and ask for a
        // re-match; if it does not, this is the assertion that fails.
        StyleGroup.importantPipeline(box.getStyle().getGeneralGroup(), g -> g.fontSize(30f));
        settle(document);

        assertEquals("the em follows the size the widget imposed", 60f, padLeftOf(box), 0.001f);
    }

    /** Layout settles over a few passes -- UIText pushes its measured size back as a candidate. */
    private void settle(UIDocument document) {
        for (int i = 0; i < 3; i++) frame();
    }

    private float padLeftOf(UINode element) {
        LengthPercentageAuto value =
                element.getStyle().getLayoutGroup().getValueSave(LayoutProperties.PADDING_LEFT);
        return value == null || !value.isLength() ? Float.NaN : value.getValue();
    }
}
