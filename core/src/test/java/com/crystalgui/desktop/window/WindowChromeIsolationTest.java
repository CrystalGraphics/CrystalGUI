package com.crystalgui.desktop.window;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A window's chrome is built apart from what the window shows: the caption is its own element, its controls call back
 * what they were given, and nothing about the content can stand between a press on a control and its effect.
 *
 * <p>This is the structural half of the split. That a content stalled on another thread cannot hold the chrome up is
 * proved once an application has a document of its own.</p>
 */
public class WindowChromeIsolationTest extends UiDocumentTestBase {

    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    @Test
    public void theCaptionWorksWithNoWindowAround() {
        AtomicInteger pins = new AtomicInteger(), minimises = new AtomicInteger(),
                maximises = new AtomicInteger(), closes = new AtomicInteger();
        WindowCaption caption = new WindowCaption("Planner",
                pins::incrementAndGet, minimises::incrementAndGet, maximises::incrementAndGet, closes::incrementAndGet);

        caption.pinButton().onPressed.emit();
        caption.minimizeButton().onPressed.emit();
        caption.maximizeButton().onPressed.emit();
        caption.closeButton().onPressed.emit();

        assertEquals(1, pins.get());
        assertEquals(1, minimises.get());
        assertEquals(1, maximises.get());
        assertEquals(1, closes.get());
    }

    @Test
    public void theFramesTitleBarIsTheCaptionAndNotPartOfTheContent() {
        Desktop desktop = desktop();
        WindowFrame frame = desktop.addWindow(new WindowFrame("App"));
        frame.setContent(new UIElement());

        assertTrue(frame.titleBar() instanceof WindowCaption);
        assertSame("the caption is a child of the frame", frame, frame.titleBar().parentElement());
        for (UIElement at = frame.titleBar(); at != null; at = at.parentElement()) {
            assertFalse("the caption is not under the content slot", at == frame.content());
        }
    }

    @Test
    public void contentThatSwallowsEveryEventDoesNotStopTheCaptionsControls() {
        Desktop desktop = desktop();
        WindowFrame frame = desktop.addWindow(new WindowFrame("App"));
        UIElement content = new UIElement();
        content.onMouseDown.attachListener((element, event) -> event.stopPropagation(), true, false);
        content.onMouseUp.attachListener((element, event) -> event.stopPropagation(), true, false);
        frame.setContent(content);
        frame();

        frame.minimizeButton().onPressed.emit();
        frame();
        assertEquals(WindowState.HIDDEN, frame.state());

        frame.show(true);
        frame.closeButton().onPressed.emit();
        frame();
        assertEquals(WindowState.DESTROYED, frame.state());
    }

    private Desktop desktop() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        Desktop desktop = Desktop.of(document);
        frame();
        frame();
        return desktop;
    }
}
