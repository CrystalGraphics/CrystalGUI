package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.ui.dom.UIElement;
import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

    @Test
    public void everyHostEntryRunsOnTheSequenceAndNothingElseReachesTheTree() throws Exception {
        System.setProperty("crystalgui.ui.sequence", "true");
        Path root = Files.createTempDirectory("cgui-lockstep");
        HostSession session = HostSession.install(new HostServices() {
            @Override public Path installationDirectory() { return root; }
            @Override public Path localWorldDirectory() { return null; }
            @Override public float uiScale() { return 1f; }
            @Override public int surfaceWidth() { return 1280; }
            @Override public int surfaceHeight() { return 720; }
            @Override public String desktopId() { return "lockstep"; }
            @Override public ProtocolConnection<Object> connection() { return null; }
            @Override public Locale locale() { return Locale.getDefault(); }
        }, ApplicationKind.of("test.lockstep", "Lockstep"));

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
