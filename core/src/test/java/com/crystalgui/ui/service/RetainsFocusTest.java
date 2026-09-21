package com.crystalgui.ui.service;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.TextField;

import org.junit.Test;

import static org.junit.Assert.assertSame;

/**
 * <b>{@code retains-focus}: a press on scenery leaves the focus owner where it was.</b>
 *
 * <p>The default is the web's. {@code Focus.pressed} walks up from what was hit looking for the nearest
 * click-focusable ancestor and, finding none, <em>clears</em> focus — Blink's {@code HandleMouseFocus},
 * and the reason clicking a bare {@code <div>} blurs the input beside it. For a prompt whose one field
 * is the whole point that is the wrong default: pressing its caption left the window with no focus owner
 * at all, so the next keystroke went nowhere.</p>
 *
 * <p>The flag itself is this engine's own: the web has no declarative form of it, only
 * {@code preventDefault()} on the press. @see Attribute#RETAINS_FOCUS</p>
 *
 * <p>Driven through {@code Input}'s real entry points, because the feature <em>is</em> what the press
 * path does — calling {@code Focus} directly would assert the branch rather than the behaviour.</p>
 */
public class RetainsFocusTest extends UiDocumentTestBase {

    private UIElement root;
    private UIElement scenery;
    private UIElement clickable;
    private TextField field;

    private void setUp(boolean retains) {
        document.removeAll();
        withDefaultStyles();

        root = new UIElement().layout(l -> l.width(400).height(400));
        root.setRetainsFocus(retains);

        field = new TextField();
        field.layout(l -> l.width(200).height(20));
        root.append(field);

        // A caption, a divider, a strip of padding: it has a box and takes no focus.
        scenery = new UIElement().layout(l -> l.width(200).height(40));
        root.append(scenery);

        // A list row, which HAS to be focusable where selection is driven from focus.
        clickable = new UIElement().layout(l -> l.width(200).height(40));
        clickable.setFocusPolicy(FocusPolicy.CLICK);
        root.append(clickable);

        document.append(root);
        document.boxes().setUiScale(1f);
        frame();

        document.focus().requestFocus(field);
        frame();
        assertSame("the fixture should start with the field focused", field, document.focus().focused());
    }

    private void click(UIElement target) {
        int[] centre = centreOf(target);
        int x = centre[0], y = centre[1];
        var input = document.input();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, now()));
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, now()));
        frame();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** The default, stated so the change above reads as a change rather than as how it always was. */
    @Test
    public void withoutTheFlagAPressOnSceneryClearsFocus() {
        setUp(false);
        click(scenery);
        assertSame("a press on nothing focusable should clear the owner, as a browser does",
                null, document.focus().focused());
    }

    @Test
    public void withTheFlagAPressOnSceneryChangesNothing() {
        setUp(true);
        click(scenery);
        assertSame("the field should still hold focus", field, document.focus().focused());
    }

    /**
     * The half that makes it usable: a retaining container must not make the things inside it
     * unfocusable, or a list whose selection is driven from focus stops selecting on click — which is
     * exactly how {@code ListView} works.
     */
    @Test
    public void aClickFocusableChildInsideStillTakesFocus() {
        setUp(true);
        click(clickable);
        assertSame("the walk should stop at the first focusable ancestor, as it always did",
                clickable, document.focus().focused());
    }

    /** Pressing the field itself is unaffected either way. */
    @Test
    public void theFieldItselfStillTakesItsOwnPress() {
        setUp(true);
        click(clickable);
        click(field);
        assertSame(field, document.focus().focused());
    }
}
