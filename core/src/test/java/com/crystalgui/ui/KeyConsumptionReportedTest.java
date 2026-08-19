package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.input.FocusPolicy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>What {@code consumeKeyboardEvent} tells the HOST, and why the answer is load-bearing.</b>
 *
 * <p>It is a boolean returned to whatever pumps the keyboard, and the host acts on what is left over. On
 * 1.7.10 that host is {@code GuiScreen}, whose Escape closes the screen — so CrystalGUI's own Escape
 * cascade only works if a consumed Escape is <em>reported</em> consumed.</p>
 *
 * <p>It was not. The method ended with an unconditional {@code return false}, so a key a widget had taken
 * through {@code stopPropagation()} came back as untouched. Escape closed the completion popup and then
 * Minecraft closed the whole editor behind it, which reads as Escape being wired to the wrong thing
 * rather than as a return value — and it is invisible in the harness, which has no host that acts on
 * leftover keys.</p>
 *
 * <p>Asserted at the handler's own entry point rather than through a widget: the contract is about the
 * boundary, and any widget used to reach it would be a second thing that could be wrong.</p>
 */
public class KeyConsumptionReportedTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private UIElement focusable;

    private void setUp() {
        root = new UIElement().layout(l -> l.width(200).height(200));
        focusable = new UIElement().layout(l -> l.width(50).height(50));
        focusable.setFocusPolicy(FocusPolicy.FOCUSABLE);
        root.addChild(focusable);
        window = new UIWindow(Ui.of(root));
        window.setUiScale(1f);
        window.init(200, 200);
        frame();
        window.getInputHandler().requestFocus(focusable);
        frame();
    }

    private void frame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private boolean press(int key) {
        return window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', key, true, false, System.currentTimeMillis()));
    }

    /** A listener that stops propagation is the only way a widget says "mine" — so it must be believed. */
    @Test
    public void aConsumedKeyIsReportedConsumed() {
        setUp();
        focusable.events.getGroup(KeyboardEvent.Down.class)
                .attachListener((element, event) -> event.stopPropagation(), false, false);

        assertTrue("a key a widget took was reported to the host as untouched",
                press(CgKeyCodes.KEY_ESCAPE));
    }

    /**
     * And an untouched key is still reported untouched, or the host stops working entirely.
     *
     * <p>The failure mode of over-correcting: report everything consumed and Escape never closes the
     * screen at all, which is the same defect from the other side and just as hard to place.</p>
     */
    @Test
    public void anUntouchedKeyIsReportedUntouched() {
        setUp();
        assertFalse("nothing claimed this key, so the host must still get it",
                press(CgKeyCodes.KEY_ESCAPE));
    }
}
