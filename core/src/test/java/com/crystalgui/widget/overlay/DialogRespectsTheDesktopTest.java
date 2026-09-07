package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

/**
 * <b>A floating dialog obeys the work area, exactly as a window does.</b>
 *
 * <p>Its own fixture rather than {@code DialogTest}'s, and the reason is the failure that led here: a
 * desktop with no stylesheet lays its window layer out at 0x0, so an assertion of the shape "the work
 * area is smaller than the screen" passes on nothing at all. The sheet is what makes the taskbar have a
 * height, and without it this test cannot fail for the right reason.</p>
 */
public class DialogRespectsTheDesktopTest extends UiDocumentTestBase {

    private static final float CAPTION = 16f;

    private Desktop desktop;
    private WindowFrame editor;
    private Dialog dialog;

    @Before
    public void aDesktopWithABar() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        desktop = Desktop.of(document);
        // Raised from inside a window, which is the real path: the window layer takes frames only.
        editor = new WindowFrame("Editor");
        desktop.addWindow(editor);
        UIElement content = new UIElement().layout(l -> l.width(200f).height(120f));
        editor.content().append(content);
        frame();

        dialog = new Dialog("Panel");
        dialog.layout(l -> l.width(120).height(80));
        dialog.getTitleBar().layout(l -> l.height(CAPTION));
        document.addOverlay(dialog, content);
        dialog.show();
        frame();
        frame();
    }

    /**
     * Promotion puts a dialog in the top layer, which spans the whole surface — so clamping against its
     * own host let it be dragged down over the taskbar, where no window can go. Where it paints and how
     * far it may travel are different questions.
     */
    @Test
    public void aDialogStopsAtTheTaskbarRatherThanTheScreenEdge() {
        Box area = desktop.windowLayer().box();
        assertTrue("the fixture needs a work area at all", area.height() > 0f);
        assertTrue("…and a taskbar under it, or this test is about nothing",
                area.height() < H - 1f);

        dialog.moveTo(0f, 9999f);
        frame();
        frame();

        float top = dialog.box().y();
        assertTrue("it stops at the work area, not at the bottom of the screen, was " + top,
                top <= area.height() - CAPTION + 0.5f);
        assertTrue("and it really did travel down there", top > area.height() * 0.5f);
    }

    /**
     * <b>And above the window that raised it, even after that window is raised again.</b>
     *
     * <p>The first attempt hosted it on the window layer itself, which put it at stack order 0 — windows
     * carry a z-index from {@code Desktop.raise}, so the dialog opened BEHIND the editor and could only
     * be reached by minimising it. Being in the band is what fixes that, and a hit test is the honest
     * way to ask: it walks the same order the paint does.</p>
     */
    @Test
    public void aDialogIsReachableOverTheWindowThatRaisedIt() {
        // RAISED MORE THAN ONCE, which is what makes this reproduce. `raise` writes an ever-growing
        // counter into the frame's z-index, and the sheet gives a dialog a flat `z-index: 5` -- so the
        // window overtakes it only after a few raises. A single-raise fixture passed against the broken
        // arrangement, and the bug looked intermittent in the running application for the same reason.
        for (int i = 0; i < 10; i++) desktop.raise(editor);
        // OVER the window, deliberately: a dialog that happens to sit beside it proves nothing, and the
        // first version of this test passed against the broken arrangement for exactly that reason --
        // addWindow centres a frame, so a dialog at the top-left overlapped nothing at all.
        dialog.moveTo(editor.left() + 8f, editor.top() + 8f);
        frame();
        frame();

        Box editorBox = editor.box();
        Box box = dialog.box();
        assertTrue("the fixture must actually overlap, or this test proves nothing",
                box.worldX() < editorBox.worldX() + editorBox.width()
                        && box.worldX() + box.width() > editorBox.worldX());
        float x = box.worldX() + box.width() * 0.5f;
        float y = box.worldY() + 4f;
        Box hit = document.boxes().hitTest(x, y);

        assertTrue("a press in the dialog must not land on the window under it, got "
                + (hit == null ? "nothing" : hit.node().name()), inside(hit, dialog));
    }

    /**
     * <b>A minimised window takes its dialogs WITH the gesture, not after it.</b>
     *
     * <p>A free dialog is hosted out into the desktop's overlay band so it can be dragged past the panel
     * that raised it — which means it does not fly with the frame. Left to {@code hide()}, which is the
     * flight's continuation, it stood still for the whole animation and blinked out once the window had
     * already landed.</p>
     */
    @Test
    public void minimisingTheWindowTakesItsDialogsAtGestureTime() {
        assertTrue(dialog.isPresented());

        editor.minimize();

        assertTrue("the dialog is not closed — it comes back with its state", dialog.isOpen());
        assertTrue("but it is off screen before the flight, not after it", !dialog.isPresented());

        editor.show(true);
        assertTrue("and back when the window is", dialog.isPresented());
        assertTrue(dialog.isOpen());
    }

    /**
     * <b>A dialog on its way out stops taking clicks immediately.</b>
     *
     * <p>The sheet's fade names {@code display} in its transition list, which is what makes the fade-out
     * exist — the box stays laid out for its duration. That is also the window in which a closed dialog
     * would go on swallowing every press over its own rectangle, invisibly, which is the failure this
     * codebase records more often than any other.</p>
     */
    @Test
    public void aClosingDialogStopsTakingClicksAtOnce() {
        dialog.moveTo(40f, 40f);
        frame();
        Box box = dialog.box();
        float x = box.worldX() + box.width() * 0.5f;
        float y = box.worldY() + box.height() * 0.5f;
        assertTrue("while open it takes them", inside(document.boxes().hitTest(x, y), dialog));

        dialog.close();
        frame();

        assertTrue("and the moment it closes it does not, fade or no fade",
                !inside(document.boxes().hitTest(x, y), dialog));
    }

    private static boolean inside(Box hit, UIElement ancestor) {
        for (UIElement walk = hit == null ? null : hit.node(); walk != null; walk = walk.composedParent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    /**
     * <b>Hosted where the windows are, so the taskbar paints over it.</b>
     *
     * <p>The window layer is {@code overflow: visible} — nothing clips a window dragged to the bottom;
     * the bar simply paints after it, being a later child of the desktop. A dialog in the TOP layer
     * paints after the whole main tree instead, so it came out over the bar. Hosting is what decides
     * both the containing block and the paint order, which is why one answer settles both.</p>
     */
    @Test
    public void aDialogIsHostedWithTheWindowsRatherThanAboveEverything() {
        assertTrue("it is still hosted out of the panel that raised it", document.isPromoted(dialog));
        assertSame("…but into the band inside the work area, so the taskbar still paints over it",
                desktop.overlayLayer(), document.promotionHost(dialog));
        assertSame(desktop.overlayLayer().box(), dialog.box().host());
    }
}
