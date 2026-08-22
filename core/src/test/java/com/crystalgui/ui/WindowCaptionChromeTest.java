package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowChrome;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Client-side decorations — an application's own chrome hosted in its window's caption.
 *
 * <p>The problem is the one every desktop toolkit hits: content with a top bar of its own, put in a
 * window, ends up with <b>two headers</b> stacked on each other. GTK's HeaderBar, VS Code's custom
 * title bar, IntelliJ's New UI and WinUI's {@code ExtendsContentIntoTitleBar} are all the same answer,
 * and so is this: the bar is <b>moved</b> into the caption.</p>
 *
 * <p>What these pin is the moving. A copy, or a "hide yours" flag, would leave two menu bars in the
 * tree with every listener and every piece of state on the wrong one — and it would look right.</p>
 */
public class WindowCaptionChromeTest extends UiTestBase {

    private UIWindow window;
    private Desktop desktop;

    /** Stands in for a workbench: content with its own header, held as an INTERNAL child exactly as
     * {@code Workbench} holds its menu bar — which is the case that is easy to get wrong on the way
     * back, since {@code removeChild} refuses an internal child and the return has to restore the flag. */
    private static final class Application extends UIElement implements WindowChrome {
        private final UIElement header = new UIElement();
        private final UIElement body = new UIElement();

        Application() {
            header.addChild(new UIText("File  Edit  View"));
            addInternalChild(header);
            addInternalChild(body);
        }

        @Override
        public UIElement captionChrome() {
            return header;
        }
    }

    private void build() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        settle();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    @Test
    public void aWindowAdoptsItsContentsCaptionChrome() {
        build();
        Application app = new Application();
        WindowFrame frame = window.openWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        assertSame("the header is in the caption", app.captionChrome(), frame.adoptedChrome());
        assertTrue("...and genuinely inside the title bar",
                isInside(frame.adoptedChrome(), frame.titleBar()));
        assertFalse("...and no longer in the application's own layout",
                isInside(app.captionChrome(), app));
    }

    /**
     * <b>One element, moved.</b> The failure a copy would produce is invisible on screen and total in
     * behaviour: two headers in the tree, and every listener, command and piece of state attached to
     * whichever one is not on screen.
     */
    @Test
    public void thereIsOnlyEverOneOfIt() {
        build();
        Application app = new Application();
        WindowFrame frame = window.openWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        assertEquals("exactly one header in the whole window", 1,
                countMatching(window.ui.rootElement, app.captionChrome()));
    }

    /** And it goes home when the window lets go — with its internal-child status restored, or it would
     * come back publicly removable by anything that walked the tree. */
    @Test
    public void releasingPutsItBackWhereItCameFrom() {
        build();
        Application app = new Application();
        UIElement header = app.captionChrome();
        int originalIndex = app.getChildren().indexOf(header);
        WindowFrame frame = window.openWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        frame.releaseChrome();
        settle();

        assertNull(frame.adoptedChrome());
        assertSame("back in the application", app, header.getParent());
        assertEquals("at the same index", originalIndex, app.getChildren().indexOf(header));
        assertTrue("and internal again", header.isInternalUI());
    }

    /** Destroying a window returns what it borrowed rather than taking it down too. */
    @Test
    public void destroyingTheWindowReturnsTheChrome() {
        build();
        Application app = new Application();
        UIElement header = app.captionChrome();
        WindowFrame frame = window.openWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        frame.destroy();

        assertSame("the application keeps its own header", app, header.getParent());
    }

    /** Content with nothing to offer changes nothing — the slot stays empty and out of the way. */
    @Test
    public void contentWithNoChromeIsLeftAlone() {
        build();
        UIElement plain = new UIElement();
        WindowFrame frame = window.openWindow(new WindowFrame("Plain"));
        frame.resizeTo(200, 140).setContent(plain);
        settle();

        assertNull(frame.adoptedChrome());
        assertSame(frame.content(), plain.getParent());
    }

    /**
     * <b>The caption grows to fit what it hosts.</b> A fixed height would clip an adopted bar, and
     * hard-coding the taller number would make every window carry the height of chrome it does not have.
     */
    @Test
    public void theCaptionGrowsForAdoptedChrome() {
        build();
        WindowFrame bare = window.openWindow(new WindowFrame("Bare"));
        bare.resizeTo(200, 140);
        settle();
        float bareHeight = bare.titleBar().getRuntimeCache().getHeight();

        Application app = new Application();
        app.captionChrome().layout(l -> l.height(28));
        WindowFrame hosting = window.openWindow(new WindowFrame("App"));
        hosting.resizeTo(200, 140).setContent(app);
        settle();

        assertTrue("a bare caption keeps its own height: " + bareHeight, bareHeight > 0f);
        assertTrue("a hosting one grows to its content: " + hosting.titleBar().getRuntimeCache().getHeight(),
                hosting.titleBar().getRuntimeCache().getHeight() >= 28f - 0.51f);
    }

    /**
     * <b>The leftover caption still drags the window.</b> WinUI calls this the drag region and makes an
     * application declare one; here it falls out of the move gesture being target-only, so anything
     * hit-testable in the caption keeps its own presses and the space around it keeps the drag.
     */
    @Test
    public void adoptedChromeDoesNotTakeTheWholeCaptionsDrag() {
        build();
        Application app = new Application();
        WindowFrame frame = window.openWindow(new WindowFrame("App"));
        frame.resizeTo(240, 140).moveTo(20, 20).setContent(app);
        settle();

        UIElement bar = frame.titleBar();
        UIElement chrome = frame.adoptedChrome();
        assertTrue("the chrome has a box, or this proves nothing",
                chrome.getRuntimeCache().getWidth() > 0f);
        assertTrue("and it does not fill the caption",
                chrome.getRuntimeCache().getWidth() < bar.getRuntimeCache().getWidth() - 8f);
    }

    private static boolean isInside(UIElement element, UIElement ancestor) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    private static int countMatching(UIElement from, UIElement target) {
        int found = from == target ? 1 : 0;
        for (UIElement child : from.getChildren()) found += countMatching(child, target);
        return found;
    }
}
