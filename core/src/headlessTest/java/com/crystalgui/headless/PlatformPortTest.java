package com.crystalgui.headless;

import com.crystalgui.core.async.UiThread;
import com.crystalgui.core.cursor.Cursor;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.PlatformPort;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A document's platform calls go through the document's own port, so a document on its own thread can route
 * them to the render thread (plan engine-threaded-ui, T0c).
 */
public class PlatformPortTest {

    @After
    public void forget() {
        UiThread.forgetForTesting();
    }

    /** A port that answers nothing and records nothing: only its identity matters here. */
    private static final class Fake implements PlatformPort {
        @Override public int modifiers() { return 0; }
        @Override public boolean isKeyDown(int localKeyCode) { return false; }
        @Override public boolean isMouseDown(int localMouseCode) { return false; }
        @Override public String clipboard() { return null; }
        @Override public void setClipboard(String text) { }
        @Override public void playSound(String soundId) { }
        @Override public void setCursor(Cursor cursor) { }
    }

    @Test
    public void codeInAFrameReachesItsDocumentsPort() {
        UIDocument document = new UIDocument();
        Fake port = new Fake();
        document.usePlatform(port);
        UIElement owner = new UIElement();
        document.append(owner);

        AtomicReference<PlatformPort> seen = new AtomicReference<>();
        AtomicBoolean uiThread = new AtomicBoolean();
        document.animation().every(owner, delta -> {
            seen.set(PlatformPort.current());
            uiThread.set(UiThread.isCurrent());
            return true;
        });
        document.frame(0.016f, 100f, 100f);

        assertSame(port, seen.get());
        assertTrue("a thread running a document is a UI thread", uiThread.get());
        assertSame("outside a frame nothing is running", PlatformPort.INLINE, PlatformPort.current());
        assertFalse("and the thread is not a UI thread outside it", UiThread.isCurrent());
    }

    @Test
    public void scopesNestAndRestore() {
        UIDocument outer = new UIDocument();
        UIDocument inner = new UIDocument();
        assertNull(UIDocument.current());
        try (UIDocument.Running a = outer.makeCurrent()) {
            assertSame(outer, UIDocument.current());
            try (UIDocument.Running b = inner.makeCurrent()) {
                assertSame(inner, UIDocument.current());
            }
            assertSame(outer, UIDocument.current());
            assertTrue(UiThread.isCurrent());
        }
        assertNull(UIDocument.current());
        assertFalse(UiThread.isCurrent());
    }
}
