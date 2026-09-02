package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.workbench.StripeRail;
import com.crystalgui.ui.elements.workbench.ToolWindowType;
import com.crystalgui.ui.elements.workbench.Workbench;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tearing a tool window out of its rail — and, more importantly, <b>not</b> tearing one out.
 *
 * <h3>Why this is driven through real mouse events</h3>
 *
 * <p>The gesture is the whole subject. {@code onDragEnd} fires on every button <em>release</em>,
 * including one that never passed the drag activation threshold, so the difference between a click and
 * a drag exists only in the input layer — a test that called {@code floatPanel} directly would pass
 * against a build where <b>every click on a rail button tore its panel out into a window</b>, which is
 * exactly what shipped. It is the same reason the menu bar's focus-owner test has to go through
 * {@code emitMouseDown} rather than {@code sendInputEvent}.</p>
 */
public class StripeTearOutTest extends UiTestBase {

    private Workbench workbench;
    private UIWindow window;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<UIElement, Object> server =
                OldEngineSessions.serve(1, new UIElement(), pair[0]);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(OldEngineSessions.view(pair[1]), PlainOps.INSTANCE);
    }

    @Before
    public void setUpWorkbench() {
        workbench = new Workbench(client())
                .setJobScheduler(new JobScheduler(Runnable::run, System::currentTimeMillis, 1));
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1000, 700);
        frame();
    }

    /**
     * A frame, <b>including the input half</b>.
     *
     * <p>{@code updateWithoutPainting()} is style, animation and layout only — it deliberately does no
     * input handling, because no frame was presented for hover to be relative to. But a payload drag is
     * ticked from {@code endFrame()}, so a test that only calls it never passes the activation threshold
     * and no drag ever begins: the gesture under test simply does not happen, and the test fails looking
     * like the tear-out is broken.</p>
     */
    private void frame() {
        for (int i = 0; i < 4; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /**
     * <b>A click is not a drag.</b> The regression this exists for: every press on a rail button ran
     * {@code onDragEnd}, nothing had accepted a drop, and the tear-out fired — so clicking Project
     * opened Project <em>and</em> tore it out into a window, on every button, every time.
     */
    @Test
    public void clickingARailButtonDoesNotTearItOut() {
        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(Workbench.PROJECT_TYPE);
        assertNotNull("no rail button to click", button);

        float[] at = centreOf(button);
        press(at[0], at[1]);
        release(at[0], at[1]);
        frame();

        assertEquals("a click tore the panel out",
                ToolWindowType.DOCKED, workbench.toolWindowManager().typeOf(Workbench.PROJECT_TYPE));
        assertNull("and it built a frame for it",
                workbench.toolWindowManager().frameOf(Workbench.PROJECT_TYPE));
    }

    /**
     * <b>A drag into the middle is.</b> The other half, so the gate above cannot be satisfied by simply
     * never tearing out — which is the shape a too-eager fix would take.
     */
    @Test
    public void draggingARailButtonIntoTheMiddleTearsItOut() {
        UIElement button = workbench.stripe(StripeRail.LEFT).buttonFor(Workbench.PROJECT_TYPE);
        assertNotNull(button);

        float[] at = centreOf(button);
        press(at[0], at[1]);
        // Well past the activation threshold, and into the workbench's centre — the zone
        // RegionDropZones answers `null` for, which is what "no region wanted it" means.
        moveTo(at[0] + 400f, at[1] + 40f);
        frame();
        moveTo(at[0] + 420f, at[1] + 40f);
        frame();
        release(at[0] + 420f, at[1] + 40f);
        frame();

        assertEquals(ToolWindowType.WINDOWED,
                workbench.toolWindowManager().typeOf(Workbench.PROJECT_TYPE));
        assertNotNull("no frame was built",
                workbench.toolWindowManager().frameOf(Workbench.PROJECT_TYPE));
        // AND THE REGION GIVES ITS SPACE BACK. A region that keeps its share with nothing in it is the
        // whole failure: the panel is gone and the gap it left is still there.
        assertFalse("the sidebar kept its space after the panel left",
                workbench.regions().isVisible(com.crystalgui.ui.elements.dock.DockRegion.SIDEBAR));
    }

    /**
     * The element's centre, in the surface pixels the input layer speaks.
     *
     * <p><b>From the layout chain, never {@code localToWorld}.</b> That matrix is populated during
     * {@code drawSubtree}, and nothing here paints — so it reads as identity and every press lands at
     * the window's corner. The click test would still have passed, which is the trap: "the press
     * missed the button" and "the press did not tear anything out" are the same assertion, so the
     * half that matters would have been green against a build that never delivered the press.</p>
     */
    private float[] centreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        var surface = Transform2D.apply(window.getRootTransform(),
                element.getWindowX() + cache.getWidth() / 2f,
                element.getWindowY() + cache.getHeight() / 2f);
        return new float[] { surface.x(), surface.y() };
    }

    private void moveTo(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, -1, false, 0f, -1L));
    }

    private void press(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void release(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }
}
