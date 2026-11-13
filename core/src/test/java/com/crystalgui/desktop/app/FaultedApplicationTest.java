package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.WorkspaceClient;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** A fault in one application shows its box, names it, and leaves every other window alone. */
public class FaultedApplicationTest extends UiDocumentTestBase {

    private static final long SECOND = 1_000_000_000L;

    private static final class Probe implements AppDocument.Backend {
        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public void abort() {
        }

        @Override
        public void abandon() {
        }

        @Override
        public boolean stopped() {
            return true;
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
    private static Throwable failsToStart;
    private AppDocument application;
    private Desktop desktop;
    private boolean animationsWere;
    private Application running;

    @Before
    public void arrange() throws IOException {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        failsToStart = null;
        withDefaultStyles();
        desktop = Desktop.of(document).useStorage(Files.createTempDirectory("faulted"));
        kind = ApplicationKind.of("test:faulted", "Faulty").standalone().separateDocument().launch(context -> {
            if (failsToStart instanceof Error error) throw error;
            if (failsToStart instanceof RuntimeException failed) throw failed;
            return new Pad(context, kind);
        });
        desktop.applications().install(kind);
        desktop.applications().useDocuments(k -> {
            UIDocument own = new UIDocument();
            own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
            own.useTopLayer(new PopupLayer());
            application = new AppDocument(own, new Probe());
            return application;
        });
        running = desktop.applications().launch(kind, (WorkspaceClient) null);
        frame();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private int windows() {
        return desktop.windowManager().registry().windows().size();
    }

    private boolean titled(String title) {
        for (WindowFrame frame : desktop.windowManager().registry().windows()) {
            if (frame.getTitle().equals(title)) return true;
        }
        return false;
    }

    @Test
    public void anExceptionFromTheDocumentDimsItsWindowAndShowsTheBox() {
        int before = windows();
        application.fault(new IllegalStateException("no shader"));
        desktop.applications().check(System.nanoTime());
        assertTrue("dimmed", running.mainWindow().frame().isGhosted());
        assertEquals("its box opened", before + 1, windows());
        assertTrue("names the application", titled("Faulty has stopped working"));
    }

    @Test
    public void aSecondFaultWhileTheBoxIsOpenIsNotAnotherBox() {
        application.fault(new IllegalStateException("one"));
        desktop.applications().check(System.nanoTime());
        int shown = windows();
        application.fault(new IllegalStateException("two"));
        desktop.applications().check(System.nanoTime());
        assertEquals(shown, windows());
    }

    @Test
    public void aLaunchThatThrowsShowsCouldntStartAndLeavesNoApplication() {
        desktop.applications().launch(kind, (WorkspaceClient) null).mainWindow().frame().requestClose();
        failsToStart = new IllegalStateException("no shader");
        int running = desktop.applications().running().size();
        assertNull(desktop.applications().launch(ApplicationKind.of("test:other", "Other").standalone()
                .separateDocument().launch(context -> {
                    throw new IllegalStateException("no shader");
                }), (WorkspaceClient) null));
        frame();
        assertEquals("nothing joined the list", running, desktop.applications().running().size());
        assertTrue(titled("Other couldn't start"));
    }

    @Test
    public void aLinkageErrorIsTheSameFault() {
        failsToStart = new NoClassDefFoundError("com/example/Missing");
        ApplicationKind broken = ApplicationKind.of("test:linkage", "Linked").standalone().separateDocument()
                .launch(context -> {
                    throw new NoClassDefFoundError("com/example/Missing");
                });
        desktop.applications().install(broken);
        assertNull(desktop.applications().launch(broken, (WorkspaceClient) null));
        frame();
        assertTrue(titled("Linked couldn't start"));
    }

    @Test
    public void theThirdFaultInAMinuteStopsOfferingRestart() {
        long now = System.nanoTime();
        ApplicationRegistry registry = desktop.applications();
        assertEquals(1, registry.recordFault("test:faulted", now));
        assertEquals(2, registry.recordFault("test:faulted", now + 10 * SECOND));
        assertEquals("the third inside a minute", 3, registry.recordFault("test:faulted", now + 20 * SECOND));
        assertEquals("a minute later it counts afresh", 1, registry.recordFault("test:faulted", now + 100 * SECOND));
    }

    @Test
    public void aCrashLoopEndsAtKeepsStopping() {
        for (int i = 0; i < 3; i++) {
            AppDocument document = application;
            document.fault(new IllegalStateException("loop " + i));
            desktop.applications().check(System.nanoTime());
            if (i < 2) {
                assertTrue(titled("Faulty has stopped working"));
                document.health().close(System.nanoTime());
                desktop.applications().check(System.nanoTime());
                running = desktop.applications().launch(kind, (WorkspaceClient) null);
                frame();
            }
        }
        assertTrue("no Restart is offered", titled("Faulty keeps stopping"));
    }
}
