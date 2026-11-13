package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** The shell dims a document that stops answering, offers its dialog, and ends it by the ladder. */
public class HungApplicationTest extends UiDocumentTestBase {

    private static final long SECOND = 1_000_000_000L;

    private static final class Probe implements AppDocument.Backend {
        UIDocument own;
        int aborted, abandoned;
        boolean stopped;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return own.input().consumeMouseEvent(event);
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public void abort() {
            aborted++;
        }

        @Override
        public void abandon() {
            abandoned++;
        }

        @Override
        public boolean stopped() {
            return stopped;
        }

        @Override
        public void close() {
        }
    }

    private static final class Pad implements Application {
        private final AppWindow main;
        private final ApplicationKind kind;

        Pad(AppContext context, ApplicationKind kind) {
            this.kind = kind;
            UIElement content = new UIElement();
            content.layout(l -> l.width(80f).height(60f));
            main = context.windows().open(WindowSpec.titled("Pad").content(content).size(300, 200).at(20, 20));
        }

        @Override
        public ApplicationKind kind() {
            return kind;
        }

        @Override
        public AppWindow mainWindow() {
            return main;
        }

        @Override
        public boolean open(Resource resource) {
            return false;
        }

        @Override
        public void activate() {
        }

        @Override
        public void dispose() {
        }
    }

    private static ApplicationKind kind;
    private Probe probe;
    private AppDocument application;
    private Desktop desktop;
    private boolean animationsWere;
    private Application running;

    @Before
    public void arrange() throws IOException {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        desktop = Desktop.of(document).useStorage(Files.createTempDirectory("hung"));
        kind = ApplicationKind.of("test:hung", "Hung").standalone().separateDocument()
                .launch(context -> new Pad(context, kind));
        desktop.applications().install(kind);
        probe = new Probe();
        desktop.applications().useDocuments(k -> {
            UIDocument own = new UIDocument();
            own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
            own.useTopLayer(new PopupLayer());
            probe.own = own;
            application = new AppDocument(own, probe);
            return application;
        });
        running = desktop.applications().launch(kind, (WorkspaceClient) null);
        frame();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private WindowFrame mainFrame() {
        return running.mainWindow().frame();
    }

    @Test
    public void aDocumentWithInputWaitingForFiveSecondsIsDimmedAndOffersItsDialog() {
        long now = System.nanoTime();
        application.mouse(new CgSystemInput.Mouse.Event(5, 5, 0, 0, -1, false, 0f, 0L));
        desktop.applications().check(now + SECOND);
        assertFalse(mainFrame().isUnresponsive());
        desktop.applications().check(now + 6 * SECOND);
        assertTrue("dimmed", mainFrame().isUnresponsive());
        int windows = desktop.windowManager().registry().windows().size();
        desktop.applications().check(now + 12 * SECOND);
        assertEquals("its dialog opened", windows + 1, desktop.windowManager().registry().windows().size());
    }

    @Test
    public void aCommitRestoresIt() {
        long now = System.nanoTime();
        application.mouse(new CgSystemInput.Mouse.Event(5, 5, 0, 0, -1, false, 0f, 0L));
        desktop.applications().check(now + 6 * SECOND);
        application.committed();
        desktop.applications().check(now + 7 * SECOND);
        assertFalse(mainFrame().isUnresponsive());
    }

    @Test
    public void closingClimbsTheLadderAndTakesTheApplicationOff() {
        long now = System.nanoTime();
        application.mouse(new CgSystemInput.Mouse.Event(5, 5, 0, 0, -1, false, 0f, 0L));
        desktop.applications().check(now + 6 * SECOND);
        application.health().close(now + 6 * SECOND);
        assertEquals("rung 1", 1, probe.aborted);
        desktop.applications().check(now + 9 * SECOND);
        assertEquals("rung 3 after two seconds", 1, probe.abandoned);
        desktop.applications().check(now + 10 * SECOND);
        assertFalse("off the running list", desktop.applications().running().contains(running));
        assertEquals("its window is gone", WindowState.DESTROYED, mainFrame().state());
    }
}
