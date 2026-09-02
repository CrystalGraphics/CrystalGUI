package com.crystalgui.desktop.window;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowChrome;
import com.crystalgui.desktop.window.WindowFrame;
import org.junit.Ignore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Client-side decorations — an application's own chrome hosted in its document's caption.
 *
 * <p>The problem is the one every desktop toolkit hits: content with a top bar of its own, put in a
 * document, ends up with <b>two headers</b> stacked on each other. GTK's HeaderBar, VS Code's custom
 * title bar, IntelliJ's New UI and WinUI's {@code ExtendsContentIntoTitleBar} are all the same answer,
 * and so is this: the bar is <b>moved</b> into the caption.</p>
 *
 * <p>What these pin is the moving. A copy, or a "hide yours" flag, would leave two menu bars in the
 * tree with every listener and every piece of state on the wrong one — and it would look right.</p>
 */
public class WindowCaptionChromeTest extends UiDocumentTestBase {

    /**
     * Animations OFF, said out loud rather than inherited. This fixture asserts a window's STATE
     * straight after a gesture, and an animation defers exactly that -- `hide()` detaches and
     * `close()` destroys only once the flight ends, so the assertion reads VISIBLE for a window that
     * has been asked to go. It used to pass by picking up a flag some other class had left off.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    private Desktop desktop;

    /** Stands in for a workbench: content with its own header, held as an INTERNAL child exactly as
     * {@code Workbench} holds its menu bar — which is the case that is easy to get wrong on the way
     * back, since {@code removeChild} refuses an internal child and the return has to restore the flag. */
    private static final class Application extends UINode implements WindowChrome {
        private final UINode header = new UINode();
        private final UINode body = new UINode();

        Application() {
            header.append(new UIText("File  Edit  View"));
            appendStructural(header);
            appendStructural(body);
        }

        @Override
        public UINode captionChrome() {
            return header;
        }
    }

    private void build() {
        UINode root = new UINode().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        settle();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
        frame();
        }
    }

    @Test
    public void aWindowAdoptsItsContentsCaptionChrome() {
        build();
        Application app = new Application();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("App"));
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
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        assertEquals("exactly one header in the whole document", 1,
                countMatching(document, app.captionChrome()));
    }

    /** And it goes home when the document lets go — with its internal-child status restored, or it would
     * come back publicly removable by anything that walked the tree. */
    @Test
    public void releasingPutsItBackWhereItCameFrom() {
        build();
        Application app = new Application();
        UINode header = app.captionChrome();
        int originalIndex = app.children().indexOf(header);
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        frame.releaseChrome();
        settle();

        assertNull(frame.adoptedChrome());
        assertSame("back in the application", app, header.parent());
        assertEquals("at the same index", originalIndex, app.children().indexOf(header));
        // THE FLAG IS GONE, and with it the state this asserted. The old engine stored
        // "is an internal child" as a bit and `removeChild` refused anything carrying it, so a
        // round trip had to put the bit back or the header became publicly detachable. Here what
        // makes a part a part is that the widget PUT IT THERE -- `insertStructuralAt` sets the flag
        // for the duration of one insertion and restores it -- so there is nothing on the node to
        // check and nothing that could have been left wrong. The two assertions above are the whole
        // of what the round trip has to get right now.
    }

    /** Destroying a document returns what it borrowed rather than taking it down too. */
    @Test
    public void destroyingTheWindowReturnsTheChrome() {
        build();
        Application app = new Application();
        UINode header = app.captionChrome();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("App"));
        frame.resizeTo(200, 140).setContent(app);
        settle();

        frame.destroy();

        assertSame("the application keeps its own header", app, header.parent());
    }

    /** Content with nothing to offer changes nothing — the slot stays empty and out of the way. */
    @Test
    public void contentWithNoChromeIsLeftAlone() {
        build();
        UINode plain = new UINode();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("Plain"));
        frame.resizeTo(200, 140).setContent(plain);
        settle();

        assertNull(frame.adoptedChrome());
        assertSame(frame.content(), plain.parent());
    }

    /**
     * <b>The caption grows to fit what it hosts.</b> A fixed height would clip an adopted bar, and
     * hard-coding the taller number would make every document carry the height of chrome it does not have.
     */
    @Test
    public void theCaptionGrowsForAdoptedChrome() {
        build();
        WindowFrame bare = Desktop.of(document).addWindow(new WindowFrame("Bare"));
        bare.resizeTo(200, 140);
        settle();
        float bareHeight = bare.titleBar().box().height();

        Application app = new Application();
        app.captionChrome().layout(l -> l.height(28));
        WindowFrame hosting = Desktop.of(document).addWindow(new WindowFrame("App"));
        hosting.resizeTo(200, 140).setContent(app);
        settle();

        assertTrue("a bare caption keeps its own height: " + bareHeight, bareHeight > 0f);
        assertTrue("a hosting one grows to its content: " + hosting.titleBar().box().height(),
                hosting.titleBar().box().height() >= 28f - 0.51f);
    }

    /**
     * <b>The leftover caption still drags the document.</b> WinUI calls this the drag region and makes an
     * application declare one; here it falls out of the move gesture being target-only, so anything
     * hit-testable in the caption keeps its own presses and the space around it keeps the drag.
     */
    @Test
    public void adoptedChromeDoesNotTakeTheWholeCaptionsDrag() {
        build();
        Application app = new Application();
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame("App"));
        frame.resizeTo(240, 140).moveTo(20, 20).setContent(app);
        settle();

        UINode bar = frame.titleBar();
        UINode chrome = frame.adoptedChrome();
        assertTrue("the chrome has a box, or this proves nothing",
                widthOf(chrome) > 0f);
        assertTrue("and it does not fill the caption",
                chrome.box().width() < bar.box().width() - 8f);
    }

    private static boolean isInside(UINode element, UINode ancestor) {
        for (UINode walk = element; walk != null; walk = walk.parent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    private static int countMatching(UINode from, UINode target) {
        int found = from == target ? 1 : 0;
        for (UINode child : from.children()) found += countMatching(child, target);
        return found;
    }
}
