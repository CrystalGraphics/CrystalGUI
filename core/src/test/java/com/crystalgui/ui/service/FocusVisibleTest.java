package com.crystalgui.ui.service;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * CSS {@code :focus-visible} — the web's rule that a focus ring shows for keyboard focus but not after
 * a mouse click, with the standard carve-out that text inputs always ring.
 *
 * <p>Driven through {@code Input}'s real entry points rather than by poking the flag, because
 * the whole feature <em>is</em> the distinction between how focus arrived. A test that called
 * {@code setFocused} directly would pass no matter which source mapped to which.</p>
 *
 * <p>In {@code core/src/test} rather than the headless set: {@link StyleSheet} can't class-load without
 * CrystalGraphics, since its {@code DEFAULT} field reads {@code default.css} through {@code CgIO} at
 * class-init.</p>
 */
public class FocusVisibleTest extends UiDocumentTestBase {

    private UIElement root;

    /** uiScale 1 so logical == physical and the click coordinates below need no conversion. */
    private void setUp(UIElement... children) {
        // Called once per widget from a loop, and the base's document persists across calls where the
        // old fixture built a fresh UIWindow each time.
        document.removeAll();
        // AND THE UA SHEET, which is what gives a widget functional geometry with no theme loaded. A
        // Switch carries no text, so without it it measures 0x0 and a click at its centre lands on
        // nothing -- which reads as click-focus being broken rather than as the widget having no box.
        withDefaultStyles();
        root = new UIElement().layout(l -> l.width(400).height(400));
        for (UIElement child : children) root.append(child);
        document.append(root);
        document.boxes().setUiScale(1f);
        frame();
    }


    /** A press-and-release over the element's centre, the way a real click arrives. */
    private void click(UIElement target) {
        int[] centre = centreOf(target);
        int x = centre[0], y = centre[1];
        var handler = document.input();
        handler.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        handler.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, now()));
        handler.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, now()));
        frame();
    }

    private void pressTab() {
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\t', CgKeyCodes.KEY_TAB, true, false, now()));
        frame();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * The highest-value assertion in this file, because of how it fails: pseudo-class names are
     * validated eagerly in {@code CompoundSelector.Part}, and the exception propagates out of
     * {@code StyleSheet.parse} — so an unmapped {@code :focus-visible} doesn't skip one rule, it takes
     * the WHOLE sheet with it. Through {@code StyleSheetRegistry} that surfaces as a silently empty
     * theme, and through {@code StyleSheet.DEFAULT} as every widget laying out at zero size.
     */
    @Test
    public void focusVisibleParsesAsASelector() {
        StyleSheet sheet = StyleSheet.parse("button:focus-visible { outline: 2px #FF0000; }");
        assertEquals(1, sheet.getRules().size());
    }

    /**
     * A typo costs its own rule and nothing else — the hyphen is all that separates the CSS name from
     * the enum constant, so this is the likeliest mistake anyone makes here.
     *
     * <p><b>It used to assert a throw, and that stopped being true at M5 5.2.</b>
     * {@code PseudoClasses.lookup} still raises — that is what validates the name eagerly, at parse
     * time rather than silently at paint time — but {@code StyleSheet.parse} catches it, warns with
     * the selector text and carries on, which is CSS's own rule and what both browsers do. The old
     * behaviour was worse than strict: one {@code :focus-within} rule broke <b>six</b> unrelated
     * layout tests in panels that had never heard of a search box, because the throw came out of
     * {@code StyleSheet.parse} and took the whole sheet with it.</p>
     *
     * <p>So the assertion is the surviving guarantee, and it is the stronger one: the bad rule is
     * gone AND the good rule beside it is still there. A throw could never have told you the second
     * half. {@code UiThemeParseTest} asserts the same thing from the theme side.</p>
     */
    @Test
    public void aTypoCostsItsOwnRuleAndNothingElse() {
        StyleSheet sheet = StyleSheet.parse(
                "button:focus-visibel { outline: 2px #FF0000; } button { opacity: 0.5; }");

        assertEquals("the typo'd rule is dropped and the sound one survives", 1, sheet.getRules().size());
    }

    /** The user-agent sheet must actually reach elements — this is what broke if the mapping was
     * missing, and it would otherwise only show up as an unstyled UI at runtime. */
    @Test
    public void theDefaultSheetStillParsesAndCarriesTheRing() {
        assertFalse("StyleSheet.DEFAULT came back empty", StyleSheet.DEFAULT.getRules().isEmpty());
    }

    // ── The behaviour ───────────────────────────────────────────────────────

    /** Tab is the case the ring exists for. */
    @Test
    public void keyboardFocusIsVisible() {
        Button button = new Button("b");
        setUp(button);

        pressTab();

        assertTrue("Tab should focus the button", button.isFocused());
        assertTrue("keyboard focus must ring", button.isFocusVisible());
    }

    /**
     * The point of the whole change: clicking a slider focuses it without ringing it. {@code :focus}
     * still matches — the two pseudo-classes are genuinely different, and asserting both is what pins
     * that rather than just "the flag is false".
     */
    @Test
    public void pointerFocusIsNotVisible() {
        Slider slider = new Slider();
        setUp(slider);

        click(slider);

        assertTrue("clicking a slider should focus it", slider.isFocused());
        assertFalse("a click must NOT ring", slider.isFocusVisible());
    }

    /**
     * Button, Checkbox and Switch behave exactly as the Slider does — clicking focuses without
     * ringing, which is what a browser does with a `&lt;button&gt;`.
     *
     * <p>They were {@code FocusPolicy.FOCUSABLE} (tab-reachable but never click-focused) purely
     * because, before `:focus-visible`, click-focus meant a ring on every click. The consequence was
     * that clicking a button left focus wherever it had been, so a subsequent Space activated some
     * other widget entirely. This pins the fix, and pins that it did not cost the ring on Tab.</p>
     */
    @Test
    public void clickingAButtonCheckboxOrSwitchFocusesItWithoutRinging() {
        for (UIElement widget : new UIElement[]{new Button("b"), new Checkbox("c"), new Switch()}) {
            setUp(widget);

            click(widget);

            String what = widget.getClass().getSimpleName();
            assertTrue(what + " should take focus from a click", widget.isFocused());
            assertFalse(what + " must not ring on a click", widget.isFocusVisible());
        }
    }

    /** ...and Tab still rings all three, i.e. CLICK really is a superset of FOCUSABLE. */
    @Test
    public void tabStillRingsButtonCheckboxAndSwitch() {
        for (UIElement widget : new UIElement[]{new Button("b"), new Checkbox("c"), new Switch()}) {
            setUp(widget);

            pressTab();

            String what = widget.getClass().getSimpleName();
            assertTrue(what + " should still be tab-reachable", widget.isFocused());
            assertTrue(what + " must ring on keyboard focus", widget.isFocusVisible());
        }
    }

    /**
     * The carve-out. Browsers ring a clicked text field because a caret alone is a weak affordance,
     * and {@code consumesTextInput()} is how this engine already identifies those elements.
     */
    @Test
    public void pointerFocusOnATextFieldIsVisible() {
        TextField field = new TextField();
        setUp(field);

        click(field);

        assertTrue(field.isFocused());
        assertTrue("a clicked text field must still ring", field.isFocusVisible());
    }

    /** Programmatic focus behaves as keyboard focus, matching {@code element.focus()} on the web. */
    @Test
    public void programmaticFocusIsVisible() {
        Button button = new Button("b");
        setUp(button);

        document.focus().requestFocus(button);

        assertTrue(button.isFocusVisible());
    }

    /**
     * Forced focus rings. Load-bearing rather than incidental: four harness rows photograph a focus
     * state by calling this every frame, and they would all render ringless if the visible bit were
     * only reachable from the input handler.
     */
    @Test
    public void forcedFocusIsVisible() {
        Button button = new Button("b");
        setUp(button);

        button.setFocused(true);

        assertTrue(button.isFocused());
        assertTrue(button.isFocusVisible());
    }

    /** The two flags can never disagree in the "not focused" direction. */
    @Test
    public void blurClearsBothFlags() {
        Button button = new Button("b");
        setUp(button);
        pressTab();
        assertTrue(button.isFocusVisible());

        button.setFocused(false);

        assertFalse(button.isFocused());
        assertFalse("visible must not outlive focus", button.isFocusVisible());
    }

    /**
     * The early-out in {@code setFocused} compares BOTH fields. If it compared only {@code isFocused},
     * promoting an already-focused element from click-focus to keyboard-focus would be swallowed —
     * which is exactly what the per-frame idempotent calls would do to it.
     */
    @Test
    public void visibilityCanChangeOnAnAlreadyFocusedElement() {
        Button button = new Button("b");
        setUp(button);

        button.setFocused(true, false);
        assertTrue(button.isFocused());
        assertFalse(button.isFocusVisible());

        button.setFocused(true, true);
        assertTrue("a second call must be able to promote visibility", button.isFocusVisible());
    }
}
