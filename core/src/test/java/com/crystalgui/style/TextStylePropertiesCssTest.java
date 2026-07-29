package com.crystalgui.style;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The CSS-facing half of {@code line-height} / {@code caret-width} / {@code selection-color}.
 *
 * <p>Here rather than in {@code headlessTest} because {@link StyleSheet} cannot be class-loaded
 * without CrystalGraphics — its {@code DEFAULT} field reads {@code default.css} through {@code CgIO}
 * at class-init, so even {@code parse()} needs it. The value-level half of these tests lives in
 * {@code TextStylePropertiesTest} in the headless set.</p>
 */
public class TextStylePropertiesCssTest extends UiTestBase {

    /** {@code UIWindow}'s constructor builds a {@code UIInputHandler}, which asks the adapter how
     * many mouse buttons exist — a window cannot be constructed without one at all. */
    /**
     * Applies {@code css} to a root holding one {@link TextField} and returns the field's style.
     *
     * <p>{@code init()} is required, not incidental: it is what attaches the tree to the window, and
     * {@code invalidateStyleMatch()} early-returns on a detached element — so without it nothing is
     * ever marked dirty and {@code calculateStyle} silently matches nothing.</p>
     *
     * <p>Laying out is safe here only because this tree contains no {@code UIText}: a text element
     * would re-shape through the font stack, whose native bindings aren't on the test classpath.
     * {@code TextField} paints its own text and measures lazily, so it never gets there.</p>
     */
    private static GeneralGroup styled(String css) {
        UIElement root = new UIElement();
        root.setId("host");
        TextField field = new TextField();
        root.addChild(field);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse(css));
        window.init(800, 600);
        window.getStyleEngine().calculateStyle(0f);
        return field.getStyle().getGeneralGroup();
    }

    @Test
    public void allThreeParseFromCss() {
        GeneralGroup style = styled(
                "textfield { line-height: 1.5; caret-width: 2; selection-color: #FF000080; }");

        assertEquals(1.5f, style.lineHeight(), 0.0001f);
        assertEquals(2f, style.caretWidth(), 0.0001f);
        // CSS hex is #RRGGBBAA — alpha last — repacked to ARGB by the parser.
        assertEquals(0x80FF0000, style.selectionColor());
    }

    @Test
    public void selectionColorAcceptsRgba() {
        int argb = styled("textfield { selection-color: rgba(255, 0, 0, 0.5); }").selectionColor();
        assertEquals("red channel", 0xFF, (argb >> 16) & 0xFF);
        assertTrue("roughly half alpha", Math.abs(((argb >>> 24) & 0xFF) - 128) <= 1);
    }

    @Test
    public void aThemeRuleBeatsTheRegistryDefault() {
        assertEquals(0xFF112233, styled("textfield { selection-color: #112233FF; }").selectionColor());
    }

    @Test
    public void importantBeatsAPlainRule() {
        assertEquals(9f, styled("""
                textfield { caret-width: 2; }
                textfield { caret-width: 9 !important; }
                """).caretWidth(), 0.0001f);
    }

    /** A rule matching only the ancestor still reaches the field — these inherit like `font-size`. */
    @Test
    public void allThreeInheritThroughTheCascade() {
        GeneralGroup style = styled(
                "#host { line-height: 2; caret-width: 3; selection-color: #00FF00FF; }");

        assertEquals(2f, style.lineHeight(), 0.0001f);
        assertEquals(3f, style.caretWidth(), 0.0001f);
        assertEquals(0xFF00FF00, style.selectionColor());
    }

    /** An unstyled field still resolves to exactly what the deleted constants were. */
    @Test
    public void anUnstyledFieldKeepsTheOldConstants() {
        GeneralGroup style = styled("element { z-index: 0; }");
        assertTrue("line-height defaults to CSS's `normal`, not a 1.2 convention",
                Float.isNaN(style.lineHeight()));
        assertEquals(1f, style.caretWidth(), 0.0001f);
        assertEquals(0x803C8527, style.selectionColor());
        assertEquals("no nudge by default", 0f, style.textOffsetY().value, 0.0001f);
        assertEquals(0f, style.textOffsetX().value, 0.0001f);
    }

    /**
     * The whole point of {@code text-offset-*} being inheritable: a theme writes it once on a widget
     * root (or on {@code *}) and it reaches that widget's internal label, which no author selector
     * can name. Same mechanism {@code color} already relies on.
     */
    @Test
    public void textOffsetInheritsToAnInternalLabel() {
        UIElement root = new UIElement();
        root.setId("host");
        Button button = new Button("hi");
        root.addChild(button);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.parse("#host { text-offset-y: 1px; }"));
        window.init(800, 600);
        window.getStyleEngine().calculateStyle(0f);

        UIElement label = button.getChildren().stream()
                .filter(UIElement::isInternalUI)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Button has no internal label"));
        assertEquals("the label the theme cannot select directly",
                1f, label.getStyle().getGeneralGroup().textOffsetY().value, 0.0001f);
    }

    /** Percent and px both parse, matching outline-offset / mask-offset's LengthPercent grammar. */
    @Test
    public void textOffsetAcceptsPxAndPercent() {
        GeneralGroup style = styled("textfield { text-offset-x: 2px; text-offset-y: 50%; }");
        assertFalse(style.textOffsetX().percent);
        assertEquals(2f, style.textOffsetX().value, 0.0001f);
        assertTrue(style.textOffsetY().percent);
        assertEquals(0.5f, style.textOffsetY().value, 0.0001f);
    }
}
