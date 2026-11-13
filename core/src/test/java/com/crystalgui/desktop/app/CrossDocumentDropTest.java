package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.ForeignDrag;
import com.crystalgui.ui.service.TransferData;

/** A drag of values leaves its application's document: another application's window hears it, the bare desktop opens it. */
public class CrossDocumentDropTest extends UiDocumentTestBase {

    /** What a viewport does on the document's thread: hands each event to the document's input. */
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

    private static final ApplicationKind KIND = ApplicationKind.of("test:cross", "Cross").standalone().separateDocument();
    private static final DraggedResources FILE = new DraggedResources(List.of(Resource.of("scratch", "Main.java")));

    private boolean animationsWere;
    private UIDocument fromDocument, toDocument;
    private AppDocument from, to;
    private AppWindow fromWindow, toWindow;
    private UIElement handle, well;
    private final List<String> heard = new ArrayList<>();
    private Object carried = FILE;

    private AppDocument open(UIDocument own, Live live) {
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        own.useTopLayer(new PopupLayer());
        live.own = own;
        return new AppDocument(own, live);
    }

    @Before
    public void arrange() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        fromDocument = new UIDocument();
        toDocument = new UIDocument();
        from = open(fromDocument, new Live());
        to = open(toDocument, new Live());

        handle = new UIElement();
        handle.layout(l -> l.width(80f).height(60f));
        handle.setHitTest(true);
        handle.onMouseDown.attachListener((element, event) -> Drag.startWithPayload(handle, event.getPosition().x(),
                event.getPosition().y(), carried, new Drag.Listener() {
                    @Override
                    public void onDragUpdate(float x, float y, float startX, float startY, float dx, float dy) {
                    }
                }), false, true);
        well = new UIElement();
        well.layout(l -> l.widthPercent(100f).heightPercent(100f));
        well.setHitTest(true);
        well.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (TransferData.find(event.getPayload(), DraggedResources.class) != null) event.preventDefault();
        }, false, true);
        well.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            assertEquals(ForeignDrag.SOURCE, event.getSource());
            heard.add("drop:" + TransferData.find(event.getPayload(), DraggedResources.class).resources().get(0));
        }, false, true);
        well.events.getGroup(DragEvent.Leave.class).attachListener((element, event) -> heard.add("leave"), false, true);

        fromWindow = AppWindows.open(desktop, KIND, from, WindowSpec.titled("From").content(handle).size(300, 200).at(20, 20));
        toWindow = AppWindows.open(desktop, KIND, to, WindowSpec.titled("To").content(well).size(300, 200).at(420, 20));
        settle();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
            for (AppDocument application : List.of(from, to)) {
                ClientPortal portal = application.active();
                if (portal != null && portal.box() != null) portal.paintContent(null, portal.box());
                float w = application.windowWidth(), h = application.windowHeight();
                application.document().frame(0.016f, w > 0f ? (int) w : 800, h > 0f ? (int) h : 600);
                application.committed();
            }
            frame();
        }
    }

    private float[] centre(AppDocument application) {
        Box box = application.active().box();
        return new float[] {box.worldX() + box.width() / 2f, box.worldY() + box.height() / 2f};
    }

    private float[] onHandle() {
        Box box = from.active().box();
        return new float[] {box.worldX() + 20f, box.worldY() + 20f};
    }

    private void dragTo(float x, float y) {
        float[] at = onHandle();
        press(at[0], at[1]);
        settle();
        move(at[0] + 20f, at[1] + 20f);
        settle();
        move(x, y);
        settle();
        assertNotNull("the drag carries a value", fromDocument.input().portableDrag());
    }

    @Test
    public void aValueDragOverAnotherApplicationsWindowIsOfferedToItsElements() {
        float[] over = centre(to);
        dragTo(over[0], over[1]);
        release(over[0], over[1]);
        settle();
        assertEquals(List.of("drop:" + FILE.resources().get(0), "leave"), heard.subList(0, 2));
        assertEquals("the source's drag ended", null, fromDocument.input().portableDrag());
    }

    @Test
    public void leavingTheWindowBeforeReleaseIsALeaveNotADrop() {
        float[] over = centre(to);
        dragTo(over[0], over[1]);
        move(5f, 590f);
        settle();
        assertTrue("left: " + heard, heard.contains("leave"));
        release(5f, 590f);
        settle();
        assertFalse("nothing dropped", heard.stream().anyMatch(s -> s.startsWith("drop")));
    }

    @Test
    public void aDragThatHoldsNodesNeverLeavesItsDocument() {
        float[] at = onHandle();
        carried = new Object();
        press(at[0], at[1]);
        settle();
        float[] over = centre(to);
        move(at[0] + 20f, at[1] + 20f);
        settle();
        move(over[0], over[1]);
        settle();
        assertEquals("not portable", null, fromDocument.input().portableDrag());
        assertTrue("the other application heard nothing: " + heard, heard.isEmpty());
        release(over[0], over[1]);
        settle();
    }
}
