package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.ui.input.UIInputHandler;
import dev.vfyjxf.taffy.style.TaffyDimension;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The CSS {@code resize} property (CSS Basic User Interface L4) — a port, not an invention.
 *
 * <p>Ambient on any element, driven by the cascade, exactly as {@code overflow} makes any element a
 * scroll container. Nobody constructs a "Resizable" widget; you write {@code resize: both} and a
 * {@code __resizer__} handle appears.</p>
 *
 * <p>The assertion that matters most here is the <b>origin</b> one. The spec says a user resize writes
 * width/height into the style attribute "replacing existing property declaration(s), if any,
 * <em>without {@code !important}</em>" — so it goes in at {@link StyleOrigin#INLINE}. Every other
 * code-driven geometry write in this engine uses {@code IMPORTANT}, which makes this the single most
 * likely thing to be "tidied" into the wrong pipeline, at which point a user drag would silently
 * start outranking an author's {@code !important} rule.</p>
 */
public class ResizeTest {

    @Before
    public void registerStubAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root, panel;

    private void build(Resize mode) {
        root = new UIElement().layout(l -> l.width(400).height(400));
        panel = new UIElement().layout(l -> l.width(100).height(80));
        panel.generalStyle(g -> g.resize(mode));
        root.addChild(panel);

        window = new UIWindow(Ui.of(root));
        window.init(800, 800); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame(); // firstFrameOver — input is dropped before a frame exists
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    private UIElement handleOf(UIElement target) {
        for (UIElement child : target.getChildren()) {
            if (child.hasClass("__resizer__")) return child;
        }
        return null;
    }

    private void press(float x, float y) {
        input.consumeMouseEvent(new SystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void move(float x, float y) {
        input.consumeMouseEvent(new SystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
    }

    /** Grabs the handle and drags by a logical delta. Sizes the handle explicitly so the fixture does
     * not depend on default.css's chosen grabber size. */
    private void dragHandleBy(float dx, float dy) {
        UIElement handle = handleOf(panel);
        handle.layout(l -> l.width(10).height(10));
        settle();

        float hx = handle.getRuntimeCache().getX() + 5f;
        float hy = handle.getRuntimeCache().getY() + 5f;
        press(hx, hy);
        move(hx + dx, hy + dy);
        settle();
    }

    // ── The handle is cascade-driven ────────────────────────────────────────

    @Test
    public void noHandleUntilResizeIsSet() {
        build(Resize.NONE);
        assertNull("resize: none must cost nothing structurally", handleOf(panel));
    }

    @Test
    public void settingResizeAddsTheHandleAndClearingItRemovesIt() {
        build(Resize.NONE);

        panel.generalStyle(g -> g.resize(Resize.BOTH));
        settle();
        assertNotNull("the handle appears from the cascade, not from constructing a widget",
                handleOf(panel));

        panel.generalStyle(g -> g.resize(Resize.NONE));
        settle();
        assertNull(handleOf(panel));
    }

    /** The handle is internal, so it is invisible to public traversal and to the codec — like every
     * other structural part in this engine. */
    @Test
    public void theHandleIsAnInternalChild() {
        build(Resize.BOTH);
        assertTrue(handleOf(panel).isInternalUI());
    }

    /** Out of flow, or adding `resize:` to an element would visibly reflow its content. */
    @Test
    public void addingTheHandleDoesNotReflowTheContent() {
        build(Resize.NONE);
        panel.addChild(new UIElement().layout(l -> l.width(40).height(20)));
        settle();
        float contentYBefore = panel.getChildren().get(0).getRuntimeCache().getY();

        panel.generalStyle(g -> g.resize(Resize.BOTH));
        settle();

        assertEquals("the grabber must not take a slot in its parent's layout",
                contentYBefore, panel.getChildren().get(0).getRuntimeCache().getY(), 0.001f);
    }

    // ── Resizing ────────────────────────────────────────────────────────────

    @Test
    public void draggingTheHandleResizesBothAxes() {
        build(Resize.BOTH);

        dragHandleBy(30f, 20f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(100f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    @Test
    public void horizontalOnlyLeavesHeightAlone() {
        build(Resize.HORIZONTAL);

        dragHandleBy(30f, 20f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals("height must be untouched", 80f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    @Test
    public void verticalOnlyLeavesWidthAlone() {
        build(Resize.VERTICAL);

        dragHandleBy(30f, 20f);

        assertEquals("width must be untouched", 100f, panel.getRuntimeCache().getWidth(), 0.5f);
        assertEquals(100f, panel.getRuntimeCache().getHeight(), 0.5f);
    }

    /** Resizing accumulates from the size at grab time, not from the live box — reading the live box
     * each frame would compound the delta and the element would race away from the cursor. */
    @Test
    public void resizingIsRelativeToTheSizeAtGrabTimeNotCompounded() {
        build(Resize.BOTH);
        UIElement handle = handleOf(panel);
        handle.layout(l -> l.width(10).height(10));
        settle();

        float hx = handle.getRuntimeCache().getX() + 5f;
        float hy = handle.getRuntimeCache().getY() + 5f;
        press(hx, hy);
        move(hx + 10f, hy + 10f);
        settle();
        move(hx + 20f, hy + 20f); // same drag, twice as far from the origin
        settle();

        assertEquals("total growth must equal the total drag, not the sum of per-frame deltas",
                120f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Origin (the fidelity detail) ────────────────────────────────────────

    /**
     * <b>The one most likely to be broken by a well-meaning tidy-up.</b> Spec: the UA writes the
     * resulting size "without {@code !important}". At IMPORTANT origin a user drag would outrank an
     * author's {@code !important} rule, which the spec explicitly does not allow.
     */
    @Test
    public void theResultingSizeIsWrittenAtInlineOriginNotImportant() {
        build(Resize.BOTH);

        dragHandleBy(30f, 0f);

        // Asserting merely that an INLINE candidate EXISTS would be vacuous — build() already writes
        // one via layout(). It has to be the RESIZED value that sits there.
        assertTrue("the resized width must be the value at INLINE origin",
                panel.getStyle().containsCandidate(LayoutProperties.WIDTH,
                        slot -> slot.origin() == StyleOrigin.INLINE
                                && slot.value() != null
                                && Math.abs(((TaffyDimension) slot.value()).getValue() - 130f) < 0.5f));
        assertFalse("and nothing may land at IMPORTANT",
                panel.getStyle().containsCandidate(LayoutProperties.WIDTH,
                        slot -> slot.origin() == StyleOrigin.IMPORTANT));
    }

    /** The consequence of the above, stated as behaviour rather than as plumbing. */
    @Test
    public void anAuthorsImportantWidthStillBeatsAUserResize() {
        build(Resize.BOTH);
        StyleGroup.importantPipeline(panel.getStyle().getLayoutGroup(), l -> l.width(100));
        settle();

        dragHandleBy(60f, 0f);

        assertEquals("!important must survive a user resize", 100f,
                panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Constraints ─────────────────────────────────────────────────────────

    /** The spec's only constraints are min/max, and Taffy already applies them — the resizer must not
     * clamp again, or it would double-apply and desync from the cascade. */
    @Test
    public void maxWidthConstrainsTheResize() {
        build(Resize.BOTH);
        panel.layout(l -> l.maxWidth(120));
        settle();

        dragHandleBy(200f, 0f);

        assertEquals("max-width must cap it", 120f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    @Test
    public void minWidthConstrainsTheResize() {
        build(Resize.BOTH);
        panel.getStyle().getLayoutGroup().set(LayoutProperties.MIN_WIDTH, TaffyDimension.length(60f));
        settle();

        dragHandleBy(-200f, 0f);

        assertEquals("min-width must floor it", 60f, panel.getRuntimeCache().getWidth(), 0.5f);
    }

    // ── Divergence from the spec, asserted so it stays deliberate ───────────

    /**
     * The spec restricts {@code resize} to scroll containers. We do not — that restriction is an
     * artifact of browsers drawing the grabber in the scrollbar corner, and we draw our own. A
     * resizable panel in a UI toolkit is very often not scrollable.
     */
    @Test
    public void resizeWorksOnANonScrollContainer() {
        build(Resize.BOTH);
        assertFalse("fixture must genuinely not be a scroll container", panel.isScrollContainer());

        dragHandleBy(30f, 0f);

        assertEquals(130f, panel.getRuntimeCache().getWidth(), 0.5f);
    }
}
