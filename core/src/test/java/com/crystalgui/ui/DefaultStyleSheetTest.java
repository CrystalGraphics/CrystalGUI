package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.sheet.StyleSheet;

import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Switch;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link StyleSheet#DEFAULT} — the user-agent stylesheet.
 *
 * <p>Covers the two things that would otherwise fail silently: the resource actually being present,
 * and author sheets always out-ranking it.</p>
 */
public class DefaultStyleSheetTest {

    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    /** A missing/misplaced default.css degrades into every widget laying out at 0x0, which is easy to
     * miss. StyleSheetRegistry hands back an empty sheet rather than failing, and DEFAULT holds that
     * forever — so pin it here. */
    @Test
    public void defaultSheetIsPresentAndParsed() {
        assertFalse("default.css is missing or failed to parse — StyleSheet.DEFAULT has no rules",
                StyleSheet.DEFAULT.getRules().isEmpty());
    }

    @Test
    public void defaultSheetCarriesTheUserAgentOrigin() {
        assertEquals(StyleOrigin.USER_AGENT, StyleSheet.DEFAULT.getOrigin());
        assertEquals(StyleOrigin.STYLESHEET, StyleSheet.parse(".a { width: 1px; }").getOrigin());
    }

    /**
     * The guarantee the separate origin exists for: an author rule wins even when it is
     * <em>less specific</em> than the UA rule it overrides.
     *
     * <p>{@code default.css} styles the slider thumb with {@code slider .__thumb__} (type + class).
     * The author rule here is {@code .__thumb__} — one class, strictly lower specificity. Sharing the
     * STYLESHEET origin would let the UA rule win, since specificity outranks source order. This is
     * exactly the trap a theme author would hit overriding {@code splitview .__divider__} against
     * default.css's more specific {@code splitview.__vertical__ .__divider__}.</p>
     */
    @Test
    public void authorSheetBeatsUserAgentEvenAtLowerSpecificity() {
        Slider slider = new Slider();
        UIWindow window = layOut(slider,
                StyleSheet.DEFAULT,
                StyleSheet.parse(".__thumb__ { width: 47px; }"));

        assertEquals("author rule lost to the more specific user-agent rule",
                47f, thumbOf(slider).getRuntimeCache().getWidth(), 0.5f);
        window.getStyleEngine();
    }

    /** Registration order must not matter — origin is compared before anything else. */
    @Test
    public void authorSheetWinsRegardlessOfRegistrationOrder() {
        Slider first = new Slider();
        layOut(first, StyleSheet.DEFAULT, StyleSheet.parse(".__thumb__ { width: 47px; }"));

        Slider second = new Slider();
        layOut(second, StyleSheet.parse(".__thumb__ { width: 47px; }"), StyleSheet.DEFAULT);

        assertEquals(thumbOf(first).getRuntimeCache().getWidth(),
                thumbOf(second).getRuntimeCache().getWidth(), 0.5f);
        assertEquals(47f, thumbOf(second).getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Widgets are usable with no theme at all ─────────────────────────────

    @Test
    public void sliderHasFunctionalGeometryFromDefaultsAlone() {
        Slider slider = new Slider();
        layOut(slider, StyleSheet.DEFAULT);

        assertTrue("slider collapsed to zero width", slider.getRuntimeCache().getWidth() > 0f);
        assertTrue("slider thumb collapsed", thumbOf(slider).getRuntimeCache().getWidth() > 0f);
    }

    @Test
    public void switchHasFunctionalGeometryFromDefaultsAlone() {
        Switch sw = new Switch();
        layOut(sw, StyleSheet.DEFAULT);
        assertTrue("switch collapsed to zero size", sw.getRuntimeCache().getWidth() > 0f);
    }

    /* Checkbox is deliberately NOT covered here: it always builds a UIText label, and measuring text
     * loads the native FreeType bindings, which aren't on the headless test classpath
     * (NoClassDefFoundError: com/crystalgraphics/freetype/FreeTypeException). Its `.__mark__` sizing
     * is verified visually instead, by cgui-checkbox in the harness. The three text-free widgets
     * below cover the same claim. */

    /** The divider is the whole interaction surface — at 0px wide the widget can't be used at all. */
    @Test
    public void splitViewDividerIsGrabbableFromDefaultsAlone() {
        SplitView sv = new SplitView();
        layOut(sv, StyleSheet.DEFAULT);
        assertTrue("split view divider is 0px wide and cannot be grabbed",
                sv.divider().getRuntimeCache().getWidth() > 0f);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UIWindow layOut(UIElement widget, StyleSheet... sheets) {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(widget);

        UIWindow window = new UIWindow(Ui.of(root));
        for (StyleSheet sheet : sheets) {
            window.getStyleEngine().addStylesheet(sheet);
        }
        window.init(800, 600);
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        return window;
    }

    private static UIElement thumbOf(Slider slider) {
        UIElement thumb = slider.querySelector("." + Slider.THUMB_CLASS);
        assertNotNull("slider has no " + Slider.THUMB_CLASS + " child", thumb);
        return thumb;
    }
}
