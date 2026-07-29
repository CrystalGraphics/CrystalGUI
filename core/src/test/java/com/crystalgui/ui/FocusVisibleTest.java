package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.TextField;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * CSS {@code :focus-visible} — the web's rule that a focus ring shows for keyboard focus but not after
 * a mouse click, with the standard carve-out that text inputs always ring.
 *
 * <p>Driven through {@code UIInputHandler}'s real entry points rather than by poking the flag, because
 * the whole feature <em>is</em> the distinction between how focus arrived. A test that called
 * {@code setFocused} directly would pass no matter which source mapped to which.</p>
 *
 * <p>In {@code core/src/test} rather than the headless set: {@link StyleSheet} can't class-load without
 * CrystalGraphics, since its {@code DEFAULT} field reads {@code default.css} through {@code CgIO} at
 * class-init.</p>
 */
public class FocusVisibleTest {

    private UIWindow window;
    private UIElement root;

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

    /** uiScale 1 so logical == physical and the click coordinates below need no conversion. */
    private void setUp(UIElement... children) {
        root = new UIElement().layout(l -> l.width(400).height(400));
        for (UIElement child : children) root.addChild(child);
        window = new UIWindow(Ui.of(root));
        window.setUiScale(1f);
        window.init(400, 400);
        frame();
    }

    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** A press-and-release over the element's centre, the way a real click arrives. */
    private void click(UIElement target) {
        var cache = target.getRuntimeCache();
        int x = Math.round(cache.getX() + cache.getWidth() / 2f);
        int y = Math.round(cache.getY() + cache.getHeight() / 2f);
        var handler = window.getInputHandler();
        handler.consumeMouseEvent(new SystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        handler.consumeMouseEvent(new SystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, now()));
        handler.consumeMouseEvent(new SystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, now()));
        frame();
    }

    private void pressTab() {
        window.getInputHandler().consumeKeyboardEvent(
                new SystemInput.Keyboard.Event('\t', CgUiKeyCodes.KEY_TAB, true, false, now()));
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

    /** The hyphen is the only thing separating the CSS name from the enum constant. */
    @Test
    public void aTypoStillFailsLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> StyleSheet.parse("button:focus-visibel { outline: 2px #FF0000; }"));
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

        window.getInputHandler().requestFocus(button);

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
