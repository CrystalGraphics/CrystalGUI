package com.crystalgui.desktop.host;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.HostThread;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A host session with {@code -Dcrystalgui.ui.sequence=true}: the desktop's document belongs to a sequence, and every
 * host entry still works from the host's thread, while the tree refuses that thread outside them.
 */
public class HostSessionLockstepTest {

    @After
    public void tearDown() {
        if (HostSession.isInstalled()) HostSession.session().dispose();
        System.clearProperty("crystalgui.ui.sequence");
    }

    private static final HostSession.PaintHost NO_SCREEN = new HostSession.PaintHost() {
        @Override public boolean ownScreenUp() { return true; }
        @Override public boolean anyScreenUp() { return true; }
        @Override public void enter() { }
        @Override public void leave() { }
    };

    /**
     * Asynchronously no host entry reads the tree on the host's thread at all: each answers from the last commit or posts
     * to the sequence, and this thread is one the tree refuses.
     */
    @Test
    public void asynchronouslyNoHostEntryReadsTheTree() throws Exception {
        System.setProperty("crystalgui.ui.async", "true");
        try {
            HostSession session = install(Files.createTempDirectory("cgui-async"));
            session.shown();
            UiSequence sequence = session.document().sequence();
            assertNotNull(sequence);

            session.frame(0.016f);
            assertNotNull(session.presentation(NO_SCREEN));
            assertNull("no commit yet, so no caret to place", session.textInputArea());
            assertTrue("a key to a document recording on its own is answered ours at once",
                    session.input().consumeKeyboardEvent(
                            new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F12, true, false, 0L)));
            session.hidden();
            session.shown();
            sequence.runNow(() -> { });
        } finally {
            System.clearProperty("crystalgui.ui.async");
        }
    }

    private static HostSession install(Path root) {
        return HostSession.install(new HostServices() {
            @Override public Path installationDirectory() { return root; }
            @Override public Path localWorldDirectory() { return null; }
            @Override public float uiScale() { return 1f; }
            @Override public int surfaceWidth() { return 1280; }
            @Override public int surfaceHeight() { return 720; }
            @Override public String desktopId() { return "lockstep"; }
            @Override public CgProtocolConnection<Object> connection() { return null; }
            @Override public Locale locale() { return Locale.getDefault(); }
            @Override public void reinjectKey(CgSystemInput.Keyboard.Event key) { }
            @Override public HostThread.Binding clientThread() { return null; }
            @Override public HostThread.Binding serverThread() { return null; }
        }, ApplicationKind.of("test.lockstep", "Lockstep"));
    }

    @Test
    public void everyHostEntryRunsOnTheSequenceAndNothingElseReachesTheTree() throws Exception {
        System.setProperty("crystalgui.ui.sequence", "true");
        HostSession session = install(Files.createTempDirectory("cgui-lockstep"));

        session.shown();
        UiSequence sequence = session.document().sequence();
        assertNotNull("the desktop's document was not given a sequence", sequence);

        session.frame(0.016f);
        assertNotNull(session.presentation(NO_SCREEN));
        assertFalse(session.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F12, true, false, 0L)));
        assertNull(session.textInputArea());
        session.hidden();
        session.shown();

        try {
            session.document().append(new UIElement());
            fail("the host's thread reached the tree outside the sequence");
        } catch (IllegalStateException expected) {
            // what the lockstep exists to make visible
        }
    }
}
