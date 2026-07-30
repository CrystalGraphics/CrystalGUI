package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * CSS {@code cursor} (CSS UI 4) — resolution and dispatch.
 *
 * <p>The engine's job stops at deciding <em>which</em> cursor the pointer should show; presenting one
 * is a platform concern behind {@link CgCursorService}, because the loaders differ sharply (LWJGL3/GLFW
 * has standard cursors, LWJGL2 has none at all). These tests therefore assert what gets <b>resolved
 * and handed over</b>, which is the part that can be wrong independently of any platform.</p>
 */
public class CursorTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root;
    private final List<CgCursor> pushed = new ArrayList<>();

    @Before
    public void installRecordingCursorService() {
        pushed.clear();
        TestPlatformService.get().cursor(pushed::add);
    }

    private void attach(UIElement rootElement) {
        root = rootElement;
        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private void moveTo(float logicalX, float logicalY) {
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(logicalX * 2f), Math.round(logicalY * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    @Test
    public void anElementsDeclaredCursorIsWhatGetsPushed() {
        UIElement box = new UIElement().layout(l -> l.width(100).height(100));
        box.generalStyle(g -> g.cursor(CgCursor.POINTER));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(box);
        attach(r);

        moveTo(50f, 50f);

        assertEquals(CgCursor.POINTER, input.currentCursor());
    }

    /**
     * {@code cursor} <b>inherits</b> (initial {@code auto}), so a container can set one for its whole
     * subtree — which is also why {@code default.css} never sets it on a container it does not mean to
     * cover entirely.
     */
    @Test
    public void cursorInheritsToDescendants() {
        UIElement child = new UIElement().layout(l -> l.width(50).height(50));
        UIElement parent = new UIElement().layout(l -> l.width(200).height(200));
        parent.generalStyle(g -> g.cursor(CgCursor.CROSSHAIR));
        parent.addChild(child);
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(parent);
        attach(r);

        moveTo(20f, 20f); // over the child

        assertEquals("the child inherits its parent's cursor", CgCursor.CROSSHAIR, input.currentCursor());
    }

    /** The spec's {@code auto} rule, half one: "{@code default} otherwise". */
    @Test
    public void autoResolvesToDefaultOverOrdinaryContent() {
        UIElement box = new UIElement().layout(l -> l.width(100).height(100));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(box);
        attach(r);

        moveTo(50f, 50f);

        assertEquals(CgCursor.DEFAULT, input.currentCursor());
    }

    /**
     * The spec's {@code auto} rule, half two: "behaves as {@code text} over selectable text or editable
     * elements". Implemented against {@code consumesTextInput()} — the engine's existing notion of
     * editable, which {@code TextField} already overrides — rather than a new signal.
     */
    @Test
    public void autoResolvesToTextOverAnEditableElement() {
        TextField field = new TextField();
        field.layout(l -> l.width(150).height(20));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(field);
        attach(r);

        moveTo(20f, 10f);

        assertEquals("auto over an editable element is `text`", CgCursor.TEXT, input.currentCursor());
    }

    /** Nothing under the pointer is not "no cursor" — it is the platform default. */
    @Test
    public void pointingAtNothingResolvesToDefault() {
        UIElement box = new UIElement().layout(l -> l.width(50).height(50));
        box.generalStyle(g -> g.cursor(CgCursor.POINTER));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(box);
        attach(r);

        moveTo(20f, 20f);
        assertEquals(CgCursor.POINTER, input.currentCursor());

        moveTo(300f, 300f); // off the box, onto the bare root

        assertEquals(CgCursor.DEFAULT, input.currentCursor());
    }

    // ── Dispatch ────────────────────────────────────────────────────────────

    /** Pushed on change only — a still pointer must not hand the platform the same cursor every frame,
     * since an implementation may be creating a native cursor object each time. */
    @Test
    public void theServiceIsOnlyCalledWhenTheCursorChanges() {
        UIElement box = new UIElement().layout(l -> l.width(100).height(100));
        box.generalStyle(g -> g.cursor(CgCursor.GRAB));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(box);
        attach(r);

        moveTo(20f, 20f);
        int afterFirst = pushed.size();
        moveTo(30f, 30f); // still inside the same element
        moveTo(40f, 40f);

        assertEquals("no repeat pushes while the resolved cursor is unchanged", afterFirst, pushed.size());
        assertEquals(CgCursor.GRAB, pushed.get(pushed.size() - 1));
    }

    /** {@code auto} is resolved before dispatch, so a platform implementation never has to handle it. */
    @Test
    public void theServiceNeverReceivesAuto() {
        UIElement box = new UIElement().layout(l -> l.width(100).height(100));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(box);
        attach(r);

        moveTo(50f, 50f);
        moveTo(300f, 300f);

        assertFalse("auto must never reach the platform", pushed.contains(CgCursor.AUTO));
    }

    // ── Interaction with the rest of the engine ─────────────────────────────

    /**
     * A resize handle advertises its axis. The bidirectional keyword is the right one — a handle
     * resizes both ways along its axis, so {@code ew-resize} rather than {@code e-resize}.
     *
     * <p>Set from {@code default.css}, so this also pins that the user-agent sheet's cursor rules
     * actually reach the handles.</p>
     */
    @Test
    public void resizeHandlesAdvertiseTheirAxis() {
        UIElement panel = new UIElement().layout(l -> l.width(120).height(80).marginLeft(40).marginTop(40));
        panel.generalStyle(g -> g.resize(Resize.BOTH));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(panel);
        attach(r);
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();

        UIElement rightEdge = null;
        for (UIElement child : panel.getChildren()) {
            if (child.hasClass("__resizer-right__")) rightEdge = child;
        }
        assertNotNull(rightEdge);
        assertEquals("default.css must give the side edges ew-resize",
                CgCursor.EW_RESIZE, rightEdge.getStyle().getGeneralGroup().cursor());
    }

    /**
     * <b>Pointer capture pins the cursor for the whole drag.</b> Falls out of hover resolving to the
     * capture target, rather than needing its own rule — a resize that reverted to {@code default} the
     * moment the pointer left the handle would look broken.
     */
    @Test
    public void aCapturedPointerKeepsTheCapturingElementsCursor() {
        UIElement handle = new UIElement().layout(l -> l.width(20).height(20));
        handle.generalStyle(g -> g.cursor(CgCursor.NWSE_RESIZE));
        UIElement elsewhere = new UIElement().layout(l -> l.width(100).height(100).marginTop(100));
        elsewhere.generalStyle(g -> g.cursor(CgCursor.POINTER));
        UIElement r = new UIElement().layout(l -> l.width(400).height(400));
        r.addChild(handle);
        r.addChild(elsewhere);
        attach(r);

        // Press on the handle, then capture, then wander far away.
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(20, 20, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
        input.setPointerCapture(handle);
        moveTo(60f, 150f); // physically over `elsewhere`

        assertEquals("the cursor must stay with the captured element",
                CgCursor.NWSE_RESIZE, input.currentCursor());
    }
}
