package com.crystalgui.ui;

import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.testsupport.UiTestBase;
import org.joml.Vector2f;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link Tooltip} — the first real consumer of the top layer, and the thing that proves it works.
 *
 * <p>Sizes are set explicitly here rather than coming from {@code default.css}, so these tests pin
 * <em>placement logic</em> and don't turn red the moment someone re-tunes the user-agent sheet's
 * tooltip padding.</p>
 */
public class TooltipTest extends UiTestBase {

    private static final float ROOT_W = 800f, ROOT_H = 600f;
    private static final float TIP_W = 60f, TIP_H = 20f;

    private UIElement root;
    private UIWindow window;

    private UIElement newRoot() {
        root = new UIElement().layout(l -> l.width(ROOT_W).height(ROOT_H));
        return root;
    }

    private void attach() {
        window = new UIWindow(Ui.of(root));
        window.init(Math.round(ROOT_W * 2), Math.round(ROOT_H * 2)); // uiScale 2
        settle();
    }

    /** {@link #attach}, then a presented frame — for any test that moves the pointer. @see #frame */
    private void attachAndPresent() {
        attach();
        frame();
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    /**
     * A whole frame — style, layout, every {@code UIFrameTicker}, then the input handler's hover pass.
     *
     * <p><b>Required before any pointer position is believed at all.</b> {@code consumeMouseEvent} opens
     * with {@code if (!firstFrameOver) return false}, so in a test that never presents a frame every move
     * is silently dropped and the pointer stays at (0, 0). That is a false-PASS machine for anything
     * geometric: the origin is inside the top-left of practically every element under test, so a region
     * test "passes" while proving nothing. Two of the four below did exactly that until this existed.</p>
     *
     * <p>It is also how a region is re-resolved the way the real thing does it — {@code updateWithoutPainting}
     * runs the placement ticker, which is where {@code resolveRegion} lives.</p>
     */
    private void frame() {
        frame(0f);
    }

    /**
     * A frame that advances timed work by {@code deltaSeconds}, whatever the wall clock says.
     *
     * <p>{@code updateWithoutPainting} derives its delta from {@code System.nanoTime()}, which in a test
     * loop is microseconds — so a one-second wait would need thousands of frames to elapse. {@code
     * UIWindow.tickAnimations} is public for exactly this ("call it directly if you drive frames
     * yourself"), and driving the clock rather than sleeping on it is what keeps a timing test from
     * being a flaky test.</p>
     */
    private void frame(float deltaSeconds) {
        window.updateWithoutPainting();
        if (deltaSeconds > 0f) window.tickAnimations(deltaSeconds);
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** An anchor at a known place, plus a sized tooltip already parented to it. */
    private Tooltip tooltipOn(UIElement anchor) {
        Tooltip tip = new Tooltip("hello");
        tip.layout(l -> l.width(TIP_W).height(TIP_H));
        anchor.addChild(tip);
        return tip;
    }

    private float x(UIElement e) { return e.getRuntimeCache().getX() - root.getRuntimeCache().getX(); }
    private float y(UIElement e) { return e.getRuntimeCache().getY() - root.getRuntimeCache().getY(); }

    // ── Not disturbing the tree it lives in ─────────────────────────────────

    /**
     * A tooltip is an internal child of its anchor so the cascade reaches it — which means a
     * <em>closed</em> one would otherwise pad every element that had a tooltip. Closed popovers are
     * {@code display: none} on the web for the same reason.
     */
    @Test
    public void aHiddenTooltipDoesNotAffectItsAnchorsLayout() {
        UIElement anchor = new UIElement().layout(l -> l.width(100));
        anchor.addChild(new UIElement().layout(l -> l.width(100).height(30)));
        newRoot().addChild(anchor);
        attach();
        float heightWithout = anchor.getRuntimeCache().getHeight();

        tooltipOn(anchor);
        settle();

        assertEquals("a closed tooltip must not take up space in its anchor",
                heightWithout, anchor.getRuntimeCache().getHeight(), 0.001f);
    }

    @Test
    public void attachInstallsAnInternalChildNotAPublicOne() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");

        // Internal children DO live in `children` — what makes them internal is the flag, which is
        // what public traversal and UIDescriptionCodec filter on.
        assertTrue("a tooltip must be an internal child, not public content", tip.isInternalUI());
        assertSame(anchor, tip.getParent());
    }

    @Test
    public void detachRemovesItFromTheAnchor() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");
        tip.showFor(anchor);
        tip.detach();

        assertNull(tip.getParent());
        assertFalse(tip.isShown());
        assertTrue("detaching must also take it out of the top layer", window.getTopLayer().isEmpty());
    }

    /**
     * Ownership regression. When this wiring lived on {@code UIElement.setTooltip}, a
     * set/clear/set cycle attached a <em>second</em> pair of hover listeners each time. Creating the
     * tooltip and its listeners together in one call makes that unrepresentable.
     */
    @Test
    public void attachingWiresExactlyOnePairOfListeners() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20).marginTop(50));
        newRoot().addChild(anchor);
        attach();

        Tooltip tip = Tooltip.attach(anchor, "explain");
        tip.showFor(anchor);
        tip.showFor(anchor);
        settle();

        assertEquals(1, window.getTopLayer().elements().size());
    }

    /** Hovering the tooltip itself must not count as leaving the anchor, or it flickers forever. */
    @Test
    public void aTooltipNeverEatsThePointer() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        assertFalse("a tooltip must be transparent to hit-testing", tip.isHitTest());
    }

    // ── Placement ───────────────────────────────────────────────────────────

    @Test
    public void showingPlacesItBelowTheAnchor() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40).marginLeft(50).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertTrue(tip.isShown());
        assertTrue(tip.isInTopLayer());
        assertEquals("left-aligned with the anchor", x(anchor), x(tip), 0.5f);
        assertEquals("directly below the anchor's bottom edge",
                y(anchor) + anchor.getRuntimeCache().getHeight(), y(tip), 0.5f);
    }

    /** Near the bottom there is no room below, so it flips above — the useful subset of the web's
     * {@code position-try-fallbacks}. */
    @Test
    public void itFlipsAboveWhenThereIsNoRoomBelow() {
        UIElement spacer = new UIElement().layout(l -> l.width(100).height(ROOT_H - 30f));
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20));
        newRoot().addChild(spacer);
        root.addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertEquals("with no room below, it must sit above the anchor",
                y(anchor) - TIP_H, y(tip), 0.5f);
    }

    @Test
    public void itClampsInsteadOfOverflowingTheRightEdge() {
        // Narrow, and hard against the right edge, so a left-aligned tooltip genuinely overflows:
        // anchor spans 770..790, so the tooltip would want 770..830 against a 800-wide root.
        UIElement anchor = new UIElement()
                .layout(l -> l.width(20).height(20).marginLeft(ROOT_W - 30f).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();

        assertTrue("must not overflow the right edge", x(tip) + TIP_W <= ROOT_W + 0.5f);
        assertEquals("clamped flush to the right edge", ROOT_W - TIP_W, x(tip), 0.5f);
    }

    // ── The reason the top layer exists ─────────────────────────────────────

    /**
     * <b>The whole feature.</b> An anchor deep inside an {@code overflow: hidden} scroller gets a
     * tooltip that lands outside the scroller's box — and is hittable there, which is the half that
     * used to be impossible even with a paint-order hack.
     */
    @Test
    public void aTooltipEscapesAClippingAncestor() {
        // The scroller is SHORTER than its row on purpose: the row's bottom edge — and therefore the
        // tooltip hanging off it — falls outside the clip box. That is the case that used to be
        // impossible to render.
        UIElement scroller = new UIElement()
                .layout(l -> l.width(200).height(30).marginLeft(40).marginTop(40))
                .generalStyle(g -> g.overflow(Overflow.HIDDEN));
        newRoot().addChild(scroller);
        UIElement row = new UIElement().layout(l -> l.width(200).height(40));
        scroller.addChild(row);
        attach();
        Tooltip tip = tooltipOn(row);

        tip.showFor(row);
        settle();

        float scrollerBottom = y(scroller) + scroller.getRuntimeCache().getHeight();
        assertTrue("the tooltip should land below the scroller's clipped box, at y=" + y(tip)
                        + " vs bottom=" + scrollerBottom,
                y(tip) + TIP_H > scrollerBottom);

        // And it must be reachable by the pointer out there. This cannot work by reordering the
        // main walk: elementHitTest refuses to recurse into a clipping ancestor's children when the
        // pointer is outside it, which is exactly where the tooltip now is.
        int probeX = Math.round((root.getRuntimeCache().getX() + x(tip) + 2f) * 2f);
        int probeY = Math.round((root.getRuntimeCache().getY() + y(tip) + TIP_H - 2f) * 2f);
        assertNotSame("the clip must no longer swallow the tooltip's own area",
                scroller, window.getHoveredElement(probeX, probeY));
    }

    /** Placement is recomputed per frame, so a scrolling anchor drags its tooltip along. */
    @Test
    public void placementFollowsAScrollingAnchor() {
        UIElement scroller = new UIElement()
                .layout(l -> l.width(200).height(100).marginLeft(40).marginTop(40))
                .generalStyle(g -> g.overflow(Overflow.AUTO));
        newRoot().addChild(scroller);
        for (int i = 0; i < 5; i++) scroller.addChild(new UIElement().layout(l -> l.width(200).height(40)));
        UIElement row = scroller.getChildren().get(2);
        attach();
        Tooltip tip = tooltipOn(row);

        tip.showFor(row);
        settle();
        float before = y(tip);

        scroller.setScrollTop(60f);
        // updateWithoutPainting(), not settle(), and no manual reposition() — because scrolling
        // changes no Taffy layout at all (the offset lives in the transform chain), so the
        // onLayoutChanged hook never fires for a pure scroll. Following a scrolling anchor is
        // carried entirely by the per-frame placement ticker, and updateWithoutPainting is the
        // documented headless driver that runs style -> tickers -> layout in the production order.
        // Calling reposition() by hand here would still pass if the ticker were deleted.
        window.updateWithoutPainting();

        assertEquals("the tooltip must follow its anchor up as the container scrolls",
                before - 60f, y(tip), 0.5f);
    }

    // ── Hiding ──────────────────────────────────────────────────────────────

    @Test
    public void hidingDemotesAndTakesItOutOfLayoutAgain() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        float heightWhenClosed = anchor.getRuntimeCache().getHeight();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        settle();
        tip.hide();
        settle();

        assertFalse(tip.isShown());
        assertFalse(tip.isInTopLayer());
        assertTrue(window.getTopLayer().isEmpty());
        assertEquals("hiding must put it back out of flow",
                heightWhenClosed, anchor.getRuntimeCache().getHeight(), 0.001f);
    }

    @Test
    public void showingIsIdempotent() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(20).marginTop(50));
        newRoot().addChild(anchor);
        attach();
        Tooltip tip = tooltipOn(anchor);

        tip.showFor(anchor);
        tip.showFor(anchor);
        settle();

        assertEquals("re-showing must not stack duplicates in the top layer",
                1, window.getTopLayer().elements().size());
    }

    // ── Regions: one tooltip, two things worth saying ───────────────────────────────────

    /** Moves the pointer to a physical point. No button, no scroll — a bare move. */
    private void movePointerTo(Vector2f physical) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(physical.x()), Math.round(physical.y()), 0, 0, -1, false, 0f, -1L));
    }

    /** The physical point at an element's centre — the space pointer events are reported in. */
    private Vector2f centreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        return Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() / 2f, cache.getY() + cache.getHeight() / 2f);
    }

    /**
     * An anchor 100×40 whose top half is a part with wording of its own.
     *
     * <p>Returned as {@code [anchor, part, filler]}. The default flex direction here is COLUMN, so the
     * part occupies the top 20 logical pixels and the filler the bottom 20.</p>
     */
    private UIElement[] anchorWithPart() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40));
        UIElement part = new UIElement().layout(l -> l.width(100).height(20));
        UIElement filler = new UIElement().layout(l -> l.width(100).height(20));
        anchor.addChild(part);
        anchor.addChild(filler);
        newRoot().addChild(anchor);
        attachAndPresent();
        return new UIElement[] { anchor, part, filler };
    }

    /**
     * <b>A region speaks for its own part of the anchor, and the base text for everywhere else.</b>
     *
     * <p>IntelliJ's editor tab is the shape: the icon says what the declaration IS, the rest of the tab
     * says where it lives. One control, two answers, chosen by which part you are pointing at.</p>
     */
    @Test
    public void aRegionSpeaksForItsOwnPartOfTheAnchor() {
        UIElement[] parts = anchorWithPart();
        UIElement anchor = parts[0], part = parts[1], filler = parts[2];

        Tooltip tip = tooltipOn(anchor).setText("java.util.ArrayList");
        tip.addRegion(part, "Final class");
        settle();

        tip.showFor(anchor);
        movePointerTo(centreOf(part));
        frame();
        assertEquals("the region's own wording did not win over the part it covers",
                "Final class", tip.getText());

        movePointerTo(centreOf(filler));
        frame();
        assertEquals("leaving the region did not fall back to the tooltip's own text",
                "java.util.ArrayList", tip.getText());
    }

    /**
     * <b>...including when that part cannot be hit.</b>
     *
     * <p>The load-bearing one. Every composite part in this engine is {@code setHitTest(false)} —
     * click-focus targets the exact element hit rather than the nearest focusable ancestor, so a hittable
     * tab icon swallows the press that selects the tab and the drag that starts from it. An unhittable
     * element receives no {@code mouseenter} at all, which is precisely why a second {@code Tooltip}
     * attached to it could never work and a region can.</p>
     *
     * <p>It rests on {@code containsScreenPoint} being pure geometry rather than a hit test. Make that
     * consult {@code hitTest} and every region in the application goes silently dead — the tooltip still
     * appears, so nothing looks broken; it just never says the specific thing.</p>
     */
    @Test
    public void aRegionResolvesOnAPartThatCannotBeHit() {
        UIElement[] parts = anchorWithPart();
        UIElement anchor = parts[0], part = parts[1];
        part.setHitTest(false);

        Tooltip tip = tooltipOn(anchor).setText("path");
        tip.addRegion(part, "Interface");
        settle();

        tip.showFor(anchor);
        movePointerTo(centreOf(part));
        frame();
        assertEquals("an unhittable part got no region — containsScreenPoint is meant to be geometry only",
                "Interface", tip.getText());
        // AND IT IS REALLY UNHITTABLE, or the assertion above proves nothing about the case it names.
        assertNotSame("the part took the hit, so this never exercised the unhittable path",
                part, window.getHoveredElement(centreOf(part).x(), centreOf(part).y()));
    }

    /**
     * <b>The deepest region under the pointer wins, not the first one registered.</b>
     *
     * <p>Regions nest — an icon inside a header inside a tab — and the answer a person wants is the most
     * specific thing they are pointing at, which is the rule hit-testing and {@code :hover} already
     * follow. Registration order is not that, and it is the order a caller is least aware of: the outer
     * region is usually wired first because the outer element is built first.</p>
     */
    @Test
    public void theDeepestRegionUnderThePointerWins() {
        UIElement anchor = new UIElement().layout(l -> l.width(100).height(40));
        UIElement outer = new UIElement().layout(l -> l.width(100).height(40));
        UIElement inner = new UIElement().layout(l -> l.width(40).height(20));
        outer.addChild(inner);
        anchor.addChild(outer);
        newRoot().addChild(anchor);
        attachAndPresent();

        Tooltip tip = tooltipOn(anchor).setText("base");
        tip.addRegion(outer, "outer");   // registered FIRST, and deliberately the shallower one
        tip.addRegion(inner, "inner");
        settle();

        tip.showFor(anchor);
        movePointerTo(centreOf(inner));
        frame();
        assertEquals("registration order beat depth", "inner", tip.getText());
    }

    /**
     * <b>A base text written while a region is speaking is what shows when the pointer leaves it.</b>
     *
     * <p>{@code setText} only touches the label when no region is active, or it would overwrite the
     * region's wording under the pointer — so it has to keep the new value somewhere. It is not a
     * hypothetical path: a dock tab's title provider is re-read on a frame tick, and the pointer is
     * routinely sitting on the icon while that happens.</p>
     */
    @Test
    public void aBaseTextWrittenWhileARegionSpeaksSurvivesUntilThePointerLeaves() {
        UIElement[] parts = anchorWithPart();
        UIElement anchor = parts[0], part = parts[1], filler = parts[2];

        Tooltip tip = tooltipOn(anchor).setText("before");
        tip.addRegion(part, "region");
        settle();

        tip.showFor(anchor);
        movePointerTo(centreOf(part));
        frame();
        assertEquals("region", tip.getText());

        tip.setText("after");
        assertEquals("a base write stole the label from the region under the pointer",
                "region", tip.getText());
        assertEquals("the new base text was dropped rather than held", "after", tip.getBaseText());

        movePointerTo(centreOf(filler));
        frame();
        assertEquals("leaving the region restored the OLD base text", "after", tip.getText());
    }

    // ── The wait before it appears ──────────────────────────────────────────────────

    /** A point in the root's far corner, well outside {@link #hoverTooltipWaiting}'s anchor. */
    private Vector2f awayFromEverything() {
        return new Vector2f(ROOT_W * 2f - 4f, ROOT_H * 2f - 4f);
    }

    /**
     * An anchor in the root's top-left, with a hover-wired tooltip that waits {@code delay} seconds.
     *
     * <p><b>The pointer is parked away from it first</b>, and that is not tidiness. An unset pointer sits
     * at (0, 0), which is inside any element laid out at the origin — so the anchor is <em>already</em>
     * hovered, the hover diff sees no change, and no {@code Enter} is ever dispatched. Every test here
     * then fails on its first assertion for a reason that has nothing to do with what it is testing,
     * which is exactly how it presented.</p>
     */
    private Tooltip hoverTooltipWaiting(float delay) {
        UIElement anchor = new UIElement().layout(l -> l.width(200).height(100));
        newRoot().addChild(anchor);
        attachAndPresent();

        Tooltip tip = Tooltip.attach(anchor, "hello");
        tip.layout(l -> l.width(TIP_W).height(TIP_H));
        StyleGroup.inlinePipeline(tip.getStyle().getGeneralGroup(), g -> g.tooltipDelay(delay));
        settle();

        movePointerTo(awayFromEverything());
        frame();
        return tip;
    }

    private UIElement onlyAnchor() {
        return root.getChildren().get(0);
    }

    /**
     * <b>A hover does not show a tooltip until the pointer has rested.</b>
     *
     * <p>A hover is not a request — the pointer crosses a dozen controls on the way to the one it wants,
     * and firing on each of them puts a box over whatever is being read. The wait is what separates
     * "passed over" from "asked about".</p>
     */
    @Test
    public void aHoverDoesNotShowATooltipUntilTheDelayHasElapsed() {
        Tooltip tip = hoverTooltipWaiting(1f);

        movePointerTo(centreOf(onlyAnchor()));
        frame();
        assertFalse("the tooltip appeared on the frame the pointer arrived", tip.isShown());
        assertTrue("nothing was waiting, so it would never have appeared either", tip.isShowPending());

        frame(1.1f);
        assertTrue("the wait elapsed and the tooltip still did not appear", tip.isShown());
        assertFalse("it showed but is still counting down", tip.isShowPending());
    }

    /**
     * <b>Leaving during the wait means it never appears at all.</b>
     *
     * <p>The half that fails loudest if it is missed, and it fails a whole second after the gesture that
     * caused it: a box opening over some other control the pointer has since moved to, describing
     * something that is no longer under it. Which is also why {@code hide} cancels the wait rather than
     * leaving that to callers — a caller cannot cancel something it has no idea is running.</p>
     */
    @Test
    public void leavingDuringTheWaitMeansTheTooltipNeverAppears() {
        Tooltip tip = hoverTooltipWaiting(1f);
        UIElement anchor = onlyAnchor();

        movePointerTo(centreOf(anchor));
        frame();
        assertTrue("the wait never started, so this proves nothing", tip.isShowPending());

        movePointerTo(awayFromEverything());
        frame();
        assertFalse("leaving did not abandon the wait", tip.isShowPending());

        frame(5f);
        assertFalse("the tooltip appeared a second after the pointer had gone", tip.isShown());
    }

    /**
     * <b>...and with no delay it is still instant.</b>
     *
     * <p>Zero is the property's initial value, so it is what every tooltip in a tree with no user-agent
     * sheet gets — which is every existing caller and most of this suite. The delay had to be additive:
     * a widget that started waiting because someone registered a property would be a silent behaviour
     * change in code that never asked for one.</p>
     */
    @Test
    public void aTooltipWithNoDelayStillAppearsOnTheFrameTheHoverArrives() {
        Tooltip tip = hoverTooltipWaiting(0f);

        movePointerTo(centreOf(onlyAnchor()));
        frame();
        assertTrue("a zero delay made the tooltip wait anyway", tip.isShown());
    }

    /**
     * <b>A tooltip points at the REGION that is speaking, not at the whole anchor.</b>
     *
     * <p>The text was right and the box was not. A region exists because a sub-area means something of
     * its own, so anchoring to the parent puts the answer next to something that is not the question: a
     * dock tab is small enough to hide it, and a full-width tree row put "Final class" at the far left of
     * the panel, a hundred pixels from the icon it described.</p>
     */
    @Test
    public void aTooltipIsPlacedAgainstTheRegionThatSpeaks() {
        UIElement[] parts = anchorWithPart();
        UIElement anchor = parts[0], part = parts[1], filler = parts[2];

        Tooltip tip = tooltipOn(anchor).setText("the whole thing");
        tip.addRegion(part, "the part");
        settle();
        tip.showFor(anchor);

        // THE VERTICAL AXIS, because this fixture stacks its two parts and they share an X -- the part is
        // the top half of the anchor, so "below the part" and "below the anchor" are twenty pixels apart
        // and nothing else about the placement differs.
        movePointerTo(centreOf(filler));
        frame();
        float againstAnchor = placedTop(tip);

        movePointerTo(centreOf(part));
        frame();
        float againstPart = placedTop(tip);

        assertEquals("the region is not speaking, so this asserts nothing", "the part", tip.getText());
        assertNotEquals("the box stayed under the whole anchor while a region was speaking",
                againstAnchor, againstPart, 0.5f);
        assertTrue("the tooltip should sit ABOVE where it sat for the anchor, since the part it "
                        + "describes is the anchor's top half: part=" + againstPart
                        + " anchor=" + againstAnchor,
                againstPart < againstAnchor);
    }

    /**
     * Where placement actually put it — the {@code top} inset {@code AnchoredPlacement} writes.
     *
     * <p>Not {@code getWindowX()}: a tooltip is TOP-LAYER promoted, so its layout parent is the root and
     * the chain reads zero until it has been through a paint. The inset is what placement computes and is
     * readable the frame it is written.</p>
     */
    private static float placedTop(Tooltip tip) {
        LengthPercentageAuto top = tip.getStyle().getComputed(LayoutProperties.TOP);
        return top == null ? Float.NaN : top.getValue();
    }

    /**
     * <b>A tooltip with nothing to say does not appear.</b>
     *
     * <p>An empty one draws a bare rounded rectangle over whatever is underneath \u2014 an answer-shaped
     * thing with no answer in it, which reads as a rendering fault rather than as silence. The dock
     * recorded this as a reason NOT to wire a region without a base text; it is really a reason to make
     * emptiness mean "say nothing".</p>
     *
     * <p>Which is what lets a REGION be the only thing a tooltip says: the project tree's icon knows what
     * a file declares and its row has nothing to add, where a dock tab genuinely does.</p>
     */
    @Test
    public void aTooltipWithNothingToSayStaysHidden() {
        UIElement[] parts = anchorWithPart();
        UIElement anchor = parts[0], part = parts[1], filler = parts[2];

        Tooltip tip = tooltipOn(anchor).setText("");
        tip.addRegion(part, "the part");
        settle();
        tip.showFor(anchor);

        movePointerTo(centreOf(filler));
        frame();
        assertTrue("a tooltip with no text drew a bare box", isHidden(tip));

        movePointerTo(centreOf(part));
        frame();
        assertFalse("the region has something to say and was not shown", isHidden(tip));
        assertEquals("the part", tip.getText());

        // AND BACK, because the pointer crosses between them without ever leaving the anchor.
        movePointerTo(centreOf(filler));
        frame();
        assertTrue("it kept speaking after the region stopped", isHidden(tip));
    }

    /** Whether the box is laid out at all \u2014 {@code display: none} is how a tooltip hides. */
    private static boolean isHidden(Tooltip tip) {
        return tip.getStyle().getComputed(LayoutProperties.DISPLAY) == TaffyDisplay.NONE;
    }
}
