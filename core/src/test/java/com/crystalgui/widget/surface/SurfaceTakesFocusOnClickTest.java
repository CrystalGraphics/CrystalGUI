package com.crystalgui.widget.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.surface.mode.SelectExtension;

/**
 * <b>Pressing a surface focuses it.</b>
 *
 * <p>{@code requestFocus} refuses anything whose policy is {@code NONE}, which is the default — so a
 * surface that does not ask for one takes no focus, and every command that resolves a surface from the
 * focused element disables itself while the widget looks entirely alive. Pressing an <em>item</em> still
 * works, because an item is click-focusable, which makes it read as "some keys work and some do not".</p>
 *
 * <p>The graph fixed this for itself and the fix stayed there, so the UI builder's own surface — the
 * second consumer of the same engine — arrived unfocusable. It belongs to {@link SurfaceEditor}, and
 * this asserts it there rather than once per consumer.</p>
 */
public class SurfaceTakesFocusOnClickTest extends UiDocumentTestBase {

    private SurfaceEditor surface;
    private UIElement item;

    @Before
    public void openABareSurface() {
        surface = new SurfaceEditor(TestSurface.policy(), List.of(SelectExtension.ID));
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.append(surface);
        document.append(root);

        item = new UIElement().layout(l -> l.width(60).height(40));
        surface.surface().place(item, 20f, 20f);
        document.update(W, H);
    }

    /** A canvas is not a tab stop: you reach it by pressing it, as in every editor. */
    @Test
    public void aSurfaceIsClickFocusableRatherThanTabbable() {
        assertEquals(FocusPolicy.CLICK, surface.focusPolicy());
    }

    /** The report: clicking empty canvas focuses the surface. */
    @Test
    public void pressingEmptyCanvasFocusesTheSurface() {
        press(400f, 300f);
        frame();

        assertSame(surface, document.focus().focused());
    }

    /**
     * And pressing an item focuses the surface too, rather than leaving focus where it was.
     *
     * <p>This is the half that used to work by accident and hid the rest.</p>
     */
    @Test
    public void pressingAnItemAlsoLeavesFocusOnTheSurface() {
        press(30f, 30f);
        frame();

        UIElement focused = document.focus().focused();
        assertTrue("focus is the surface or something inside it",
                focused == surface || surface.contains(focused));
    }
}
