package com.crystalgui.desktop.taskbar;

import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.ui.service.Input;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The taskbar's hover previews — the wait, and the fact that a preview appears at all.
 *
 * <h3>Driven through the real pointer</h3>
 *
 * <p>The whole feature is a hover, and hover is resolved by the input handler's own per-frame diff — so
 * emitting {@code onMouseEnter} by hand would assert against a signal nobody has proved gets sent. This
 * moves an actual pointer onto the entry's drawn box, which also means a strip laid out under the work
 * area or with no hittable box fails here instead of passing.</p>
 *
 * <h3>It really does wait half a second</h3>
 *
 * <p>The delay is measured on {@code System.nanoTime()} and cannot be stepped, so this one test spends
 * real time. That is worth it exactly once: a preview that appeared instantly would be a panel strobing
 * across the screen as the pointer crossed the strip, and that is the failure the delay exists for.</p>
 */
public class TaskbarPreviewTest extends UiDocumentTestBase {

    private Input input;
    private Desktop desktop;

    @Before
    public void build() {
        Desktop.setAnimationsEnabled(false);
        UINode root = new UINode().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        input = document.input();
        desktop = Desktop.of(document);
        frame();
    }

    @After
    public void restoreDefault() {
        Desktop.setAnimationsEnabled(false);
    }


    private WindowFrame open(String title) {
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame(title));
        frame.resizeTo(160, 120);
        frame();
        frame();
        return frame;
    }

    /** Puts the pointer on an entry's drawn centre, in the physical pixels the input layer speaks. */
    private void hover(Button entry) {
        var box = entry.box();
        assertTrue("the entry has no box, so hovering it means nothing",
                box.width() > 0f && box.height() > 0f);
        int x = Math.round((box.x() + box.width() / 2f) * 2f);
        int y = Math.round((box.y() + box.height() / 2f) * 2f);
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
    }

    private void waitPastTheDelay() {
        long until = System.nanoTime() + 600L * 1_000_000L;
        while (System.nanoTime() < until) {
            frame();
        }
    }

    /**
     * <b>Resting on an entry raises its preview — but not straight away.</b>
     *
     * <p>Both halves are the feature. Without the wait the strip strobes as the pointer crosses it;
     * without the preview there is nothing to wait for.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void restingOnAnEntryRaisesItsPreviewAfterTheDelay() {
        WindowFrame frame = open("One");
        Taskbar taskbar = desktop.taskbar();
        Button entry = taskbar.entryFor(frame);
        assertNotNull(entry);

        hover(entry);
        assertNull("a preview appeared immediately -- the strip will strobe as the pointer crosses it",
                taskbar.previewedWindow());

        waitPastTheDelay();

        assertSame("resting on an entry raised no preview", frame, taskbar.previewedWindow());
    }

    /**
     * <b>And it goes when the pointer leaves.</b>
     *
     * <p>Leaving is a question about the entry AND the panel — the natural way to reach a preview's
     * close button is to move up off the entry onto the panel — so this moves the pointer somewhere that
     * is neither.</p>
     *
     * <p>And it waits, because there is a deliberate grace before the panel goes. The panel sits a few
     * pixels above the entry, so reaching it means crossing ground that belongs to neither, and the
     * entry's {@code mouseleave} lands before the panel's {@code mouseenter} — dismissing on the first
     * frame with nothing hovered made the preview unreachable, vanishing the instant you set off
     * towards it.</p>
     */
    @Ignore("M6 port: rewrite pending -- the old-engine behaviour this asserts has no counterpart yet")
    @Test
    public void leavingEverythingTakesThePreviewDown() {
        WindowFrame frame = open("One");
        Taskbar taskbar = desktop.taskbar();
        hover(taskbar.entryFor(frame));
        waitPastTheDelay();
        assertNotNull(taskbar.previewedWindow());

        // The top-left corner: not the strip, and not the panel, which sits above the strip.
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(2, 2, 0, 0, -1, false, 0f, -1L));
        long until = System.nanoTime() + 400L * 1_000_000L;
        while (System.nanoTime() < until) {
            frame();
        }

        assertNull("the preview outlived the pointer", taskbar.previewedWindow());
    }
}
