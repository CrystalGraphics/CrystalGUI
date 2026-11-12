package com.crystalgui.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.app.AppDocument;
import com.crystalgui.desktop.app.ClientPortal;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.SheetRef;
import com.crystalgui.net.window.ClientWindowContext;
import com.crystalgui.net.window.Presentation;
import com.crystalgui.net.window.WindowMount;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** A server's window: the tree stays in the connection's document and the shell's frame shows a portal to it. */
public class DesktopWindowMountTest {

    private static final class Stub implements AppDocument.Backend {
        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static final class Context implements ClientWindowContext {
        final UIElement root = new UIElement();
        int userClosed;

        @Override
        public UIElement root() {
            return root;
        }

        @Override
        public String type() {
            return "test:panel";
        }

        @Override
        public String title() {
            return "Panel";
        }

        @Override
        public String key() {
            return null;
        }

        @Override
        public Presentation presentation() {
            return Presentation.WINDOW;
        }

        @Override
        public List<SheetRef> sheets() {
            return List.of();
        }

        @Override
        public boolean useUserAgentSheet() {
            return false;
        }

        @Override
        public ClientUiSession<UIElement, Object> session() {
            return null;
        }

        @Override
        public CgProtocolConnection<Object> connection() {
            return null;
        }

        @Override
        public boolean mayClose() {
            return true;
        }

        @Override
        public void evicted() {
        }

        @Override
        public void userClosed() {
            userClosed++;
        }

        @Override
        public void visibilityChanged(boolean visible) {
        }
    }

    private boolean animationsWere;
    private UIDocument shell;
    private UIDocument connection;
    private DesktopWindowMount mount;

    @Before
    public void open() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        shell = new UIDocument();
        Desktop.of(shell);
        connection = new UIDocument();
        mount = new DesktopWindowMount(shell, () -> new AppDocument(connection, new Stub()));
        mount.host();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    @Test
    public void theTreeStaysInTheConnectionsDocumentAndTheFrameHoldsAPortalToIt() {
        Context context = new Context();
        WindowMount.MountedWindow window = mount.mount(context);

        assertSame("the tree is the connection's", connection, context.root.document());
        List<WindowFrame> frames = Desktop.of(shell).windowManager().registry().windows();
        assertEquals(1, frames.size());
        WindowFrame frame = frames.get(0);
        assertTrue("the frame shows a portal", frame.content().children().get(0) instanceof ClientPortal);
        assertNotSame(shell, context.root.document());
        window.closedByServer("done");
        assertEquals(WindowState.DESTROYED, frame.state());
        assertEquals("a server's close is not the user's", 0, context.userClosed);
    }

    @Test
    public void aWindowTheUserClosesIsReported() {
        Context context = new Context();
        mount.mount(context);
        Desktop.of(shell).windowManager().registry().windows().get(0).destroy();
        assertEquals(1, context.userClosed);
    }
}
