package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.document.DraggedResources;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;

/** A value drag of an application released on the bare desktop opens its resources in a window of that application. */
public class DesktopDropOpensWindowTest extends UiDocumentTestBase {

    private static final List<List<Resource>> OPENED = new ArrayList<>();
    private static final DraggedResources FILE = new DraggedResources(List.of(Resource.of("scratch", "Main.java")));

    private static final class Live implements AppDocument.Backend {
        UIDocument own;

        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return own.input().consumeMouseEvent(event);
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return own.input().consumeKeyboardEvent(event);
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
            OPENED.add(context.open());
            UIElement handle = new UIElement();
            handle.layout(l -> l.width(80f).height(60f));
            handle.setHitTest(true);
            handle.onMouseDown.attachListener((element, event) -> Drag.startWithPayload(handle,
                    event.getPosition().x(), event.getPosition().y(), FILE, new Drag.Listener() {
                        @Override
                        public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                        }
                    }), false, true);
            main = context.windows().open(WindowSpec.titled("Pad").content(handle).size(300, 200).at(20, 20));
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
            OPENED.add(List.of(resource));
            return true;
        }

        @Override
        public void activate() {
        }

        @Override
        public void dispose() {
        }
    }

    private ApplicationKind kind;
    private final List<AppDocument> documents = new ArrayList<>();
    private boolean animationsWere;
    private Desktop desktop;

    @Before
    public void arrange() throws IOException {
        OPENED.clear();
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        desktop = Desktop.of(document).useStorage(Files.createTempDirectory("drop"));
        kind = ApplicationKind.of("test:pad", "Pad").standalone().separateDocument()
                .launch(context -> new Pad(context, KIND_HOLDER[0]));
        KIND_HOLDER[0] = kind;
        desktop.applications().install(kind);
        desktop.applications().useDocuments(k -> {
            UIDocument own = new UIDocument();
            own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
            own.useTopLayer(new PopupLayer());
            Live live = new Live();
            live.own = own;
            AppDocument opened = new AppDocument(own, live);
            documents.add(opened);
            return opened;
        });
    }

    private static final ApplicationKind[] KIND_HOLDER = new ApplicationKind[1];

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            for (AppDocument application : documents) {
                ClientPortal portal = application.active();
                if (portal != null && portal.box() != null) portal.paintContent(null, portal.box());
                float w = application.windowWidth(), h = application.windowHeight();
                application.document().frame(0.016f, w > 0f ? (int) w : 800, h > 0f ? (int) h : 600);
                application.committed();
            }
            frame();
        }
    }

    @Test
    public void aDragReleasedOnTheBareDesktopOpensItsFileInAWindowOfTheSameApplication() {
        desktop.applications().launch(kind, (com.crystalgui.fs.client.WorkspaceClient) null);
        settle();
        assertEquals("one window before", 1, OPENED.size());
        Box box = documents.get(0).active().box();
        float x = box.worldX() + 20f, y = box.worldY() + 20f;
        press(x, y);
        settle();
        move(x + 20f, y + 20f);
        settle();
        move(700f, 500f);
        settle();
        release(700f, 500f);
        settle();
        assertEquals("the file reached a window of the application: " + OPENED, 2, OPENED.size());
        assertEquals(FILE.resources(), OPENED.get(1));
    }
}
