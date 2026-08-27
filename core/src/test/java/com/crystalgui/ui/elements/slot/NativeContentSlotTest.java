package com.crystalgui.ui.elements.slot;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.UIElement;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The three-state platform policy, and the descriptor round-trip.
 *
 * <p>What is <em>not</em> here, deliberately: anything that needs a real paint. The composite path, the
 * scratch target's pixel sizing and the GL bracket are all only observable against a live context, and
 * the harness scene is what exercises them. This file covers the decisions — which are the parts that
 * fail silently.</p>
 */
public class NativeContentSlotTest extends UiTestBase {

    /**
     * {@link UiTestBase} installs {@link NativeContentService#UNSUPPORTED} for every test, so a test that
     * wants a different state must put it back afterwards. A leaked platform is the documented way these
     * suites become order-dependent, and this slot is process-wide static.
     */
    @After
    public void restoreDeclaredUnsupported() {
        CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
    }

    // ── The policy ──────────────────────────────────────────────────────────

    @Test
    public void anUndeclaredPlatformIsRefusedRatherThanDrawingNothing() {
        CgPlatform.provide(NativeContentService.SERVICE, null);
        try {
            NativeContentService.require();
            fail("expected a platform that never declared a position to be refused");
        } catch (IllegalStateException expected) {
            String message = expected.getMessage();
            // The message is the feature: a reader hitting this has to be told BOTH remedies, because
            // "install a renderer" and "declare that you have none" are different decisions and nothing
            // else on screen says which one was meant.
            assertTrue("names the slot: " + message,
                    message.contains(NativeContentService.SERVICE.name()));
            assertTrue("offers the renderer remedy: " + message,
                    message.contains("new MyItemRenderer()"));
            assertTrue("offers the opt-out remedy: " + message,
                    message.contains("NativeContentService.UNSUPPORTED"));
        }
    }

    /**
     * The counter-assertion, and it is not a formality: a check written as "refuse unless available"
     * passes the test above and makes every slot on every platform throw, including the harness.
     */
    @Test
    public void aPlatformThatDeclaredItRendersNothingIsAccepted() {
        CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
        NativeContentService service = NativeContentService.require();
        assertSame(NativeContentService.UNSUPPORTED, service);
        assertFalse("an opted-out platform reports itself unavailable", service.isAvailable());
    }

    @Test
    public void aProvidedRendererIsUsed() {
        RecordingService recording = new RecordingService();
        CgPlatform.provide(NativeContentService.SERVICE, recording);
        NativeContentService service = NativeContentService.require();
        assertSame(recording, service);
        assertTrue(service.isAvailable());
    }

    /**
     * Absence and a deliberate opt-out are different states, and {@code isProvided} is the only thing
     * that separates them — which is why the slot's absent-value is not the {@code UNSUPPORTED} instance
     * even though it behaves identically.
     */
    @Test
    public void theAbsentValueIsNotTheOptOutSentinel() {
        CgPlatform.provide(NativeContentService.SERVICE, null);
        assertFalse(CgPlatform.isProvided(NativeContentService.SERVICE));
        NativeContentService absent = CgPlatform.get(NativeContentService.SERVICE);
        assertFalse("absent behaves unavailable", absent.isAvailable());
        assertFalse("but is a different object, or isProvided could not tell them apart",
                absent == NativeContentService.UNSUPPORTED);
    }

    // ── Registration ────────────────────────────────────────────────────────

    @Test
    public void bothTagsBuildTheirOwnType() {
        assertTrue(ElementRegistry.create("itemslot") instanceof ItemSlot);
        assertTrue(ElementRegistry.create("fluidslot") instanceof FluidSlot);
        assertEquals("itemslot", ElementRegistry.tagOf(ItemSlot.class));
        assertEquals("fluidslot", ElementRegistry.tagOf(FluidSlot.class));
    }

    /**
     * A slot's cascade identity is its tag, so this is what makes {@code itemslot { ... }} in the
     * user-agent sheet reach it at all — the lesson {@code Dropdown extends Button} paid for.
     */
    @Test
    public void aSlotReportsItsOwnTagRatherThanItsBaseClass() {
        assertEquals("itemslot", new ItemSlot().tagName());
        assertEquals("fluidslot", new FluidSlot().tagName());
    }

    @Test
    public void slotsRefusePublicChildren() {
        try {
            new ItemSlot().addChild(new UIElement());
            fail("a slot is not a container");
        } catch (RuntimeException expected) {
            // Composites throw from addChild; the type is the engine's business, not this test's.
        }
    }

    // ── The descriptor ──────────────────────────────────────────────────────

    @Test
    public void bindingTakesTheHandlesDescriptor() {
        ItemSlot slot = new ItemSlot();
        slot.bind(descriptor("slot:12"));
        assertEquals("slot:12", write(slot).getString("content", ""));
    }

    /**
     * Write, read into a fresh instance, write again — the only shape that catches a key written under
     * one name and read under another.
     */
    @Test
    public void theDescriptorRoundTrips() {
        ItemSlot original = new ItemSlot();
        original.bind(descriptor("slot:12"));

        ItemSlot rebuilt = new ItemSlot();
        rebuilt.readStateFrom(new StateMap<>(PlainOps.INSTANCE, write(original).encode()));

        assertEquals("slot:12", write(rebuilt).getString("content", ""));
    }

    /**
     * A descriptor decoded on a platform that cannot interpret it is <b>retained</b>, not discarded.
     *
     * <p>This is the case that makes a slot describable by a dedicated server: it writes a location, and
     * a reader with no renderer must still re-encode it identically or the description stops being
     * content-addressable. Resolving eagerly in {@code readState} would drop it here for good.</p>
     */
    @Test
    public void anUnresolvableDescriptorSurvivesReEncoding() {
        CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
        ItemSlot slot = new ItemSlot();

        StateMap<Object> in = new StateMap<>(PlainOps.INSTANCE);
        in.putString("content", "slot:99");
        slot.readStateFrom(new StateMap<>(PlainOps.INSTANCE, in.encode()));

        assertTrue("nothing could resolve it", slot.content().isEmpty());
        assertEquals("but it is still written back out", "slot:99", write(slot).getString("content", ""));
    }

    /**
     * A slot whose descriptor arrived before any renderer did repairs itself once one appears.
     *
     * <p>Not hypothetical: {@code CgService} is last-write-wins precisely because mod init order is not
     * guaranteed, so a description can legitimately be decoded first. Resolving once, at read time, would
     * leave such a slot empty for the life of the screen.</p>
     */
    @Test
    public void aDescriptorResolvesLateWhenTheRendererArrivesAfterIt() {
        CgPlatform.provide(NativeContentService.SERVICE, NativeContentService.UNSUPPORTED);
        ItemSlot slot = new ItemSlot();
        StateMap<Object> in = new StateMap<>(PlainOps.INSTANCE);
        in.putString("content", "slot:7");
        slot.readStateFrom(new StateMap<>(PlainOps.INSTANCE, in.encode()));
        assertTrue("nothing to resolve against yet", slot.content().isEmpty());

        CgPlatform.provide(NativeContentService.SERVICE, new RecordingService());
        assertFalse("resolved on the next ask", slot.content().isEmpty());
        assertEquals("slot:7", slot.content().descriptor());
    }

    // ── Fluid fill geometry ─────────────────────────────────────────────────

    @Test
    public void aTankFillsFromTheBottom() {
        float[] box = FluidSlot.fillBox(FluidSlot.FillDirection.BOTTOM_UP, 10f, 20f, 16f, 16f, 0.25f);
        assertEquals("x unchanged", 10f, box[0], 0.001f);
        assertEquals("top edge moves down by the empty part", 32f, box[1], 0.001f);
        assertEquals(16f, box[2], 0.001f);
        assertEquals(4f, box[3], 0.001f);
    }

    @Test
    public void theOtherThreeDirectionsAnchorToTheirOwnEdge() {
        float[] down = FluidSlot.fillBox(FluidSlot.FillDirection.TOP_DOWN, 0f, 0f, 16f, 16f, 0.25f);
        assertEquals(0f, down[1], 0.001f);
        assertEquals(4f, down[3], 0.001f);

        float[] right = FluidSlot.fillBox(FluidSlot.FillDirection.LEFT_RIGHT, 0f, 0f, 16f, 16f, 0.25f);
        assertEquals(0f, right[0], 0.001f);
        assertEquals(4f, right[2], 0.001f);

        float[] left = FluidSlot.fillBox(FluidSlot.FillDirection.RIGHT_LEFT, 0f, 0f, 16f, 16f, 0.25f);
        assertEquals("right-anchored, so the box starts where the fill begins", 12f, left[0], 0.001f);
        assertEquals(4f, left[2], 0.001f);
    }

    @Test
    public void afullTankIsTheWholeBox() {
        float[] box = FluidSlot.fillBox(FluidSlot.FillDirection.BOTTOM_UP, 3f, 4f, 16f, 16f, 1f);
        assertEquals(3f, box[0], 0.001f);
        assertEquals(4f, box[1], 0.001f);
        assertEquals(16f, box[2], 0.001f);
        assertEquals(16f, box[3], 0.001f);
    }

    /**
     * A bucket in a 32-bucket tank is 3% of an 18px slot — half a pixel, which is no pixels. The two
     * states a reader most needs to tell apart are "empty" and "nearly empty", so a non-empty tank keeps
     * at least one logical pixel. Tinkers' Construct clamps the same way.
     *
     * <p>All four directions, because the clamp has to apply to whichever axis is being narrowed and the
     * two anchored ones ({@code RIGHT_LEFT}, {@code BOTTOM_UP}) also have to move their origin by the
     * clamped length rather than the raw one — an off-by-that would put the pixel outside the slot.</p>
     */
    @Test
    public void aTraceAmountStillDrawsOnePixel() {
        float trace = 0.001f;

        float[] up = FluidSlot.fillBox(FluidSlot.FillDirection.BOTTOM_UP, 0f, 0f, 16f, 16f, trace);
        assertEquals("one pixel tall", 1f, up[3], 0.001f);
        assertEquals("sitting on the bottom edge", 15f, up[1], 0.001f);

        float[] down = FluidSlot.fillBox(FluidSlot.FillDirection.TOP_DOWN, 0f, 0f, 16f, 16f, trace);
        assertEquals(1f, down[3], 0.001f);
        assertEquals(0f, down[1], 0.001f);

        float[] right = FluidSlot.fillBox(FluidSlot.FillDirection.LEFT_RIGHT, 0f, 0f, 16f, 16f, trace);
        assertEquals(1f, right[2], 0.001f);
        assertEquals(0f, right[0], 0.001f);

        float[] left = FluidSlot.fillBox(FluidSlot.FillDirection.RIGHT_LEFT, 0f, 0f, 16f, 16f, trace);
        assertEquals(1f, left[2], 0.001f);
        assertEquals("against the right edge", 15f, left[0], 0.001f);
    }

    /**
     * The counter-assertion, and it is not a formality: a floor written as an unconditional
     * {@code Math.max(1, ...)} passes the test above and makes every empty tank draw a pixel of fluid.
     * {@code applyFill} skips a zero-sized box, so zero is how "nothing" is spelled.
     */
    @Test
    public void anEmptyTankDrawsNothing() {
        for (FluidSlot.FillDirection direction : FluidSlot.FillDirection.values()) {
            float[] box = FluidSlot.fillBox(direction, 0f, 0f, 16f, 16f, 0f);
            assertEquals(direction + " width", 0f, box[2] * box[3], 0.001f);
        }
    }

    /**
     * The tile grid pins to the edge that {@link FluidSlot#fillBox} slides, per direction.
     *
     * <p>Asserted as a table because the whole content of the mapping is four one-line answers, and
     * every one of them is invisible in a screenshot until a tank sits at a level that is not a whole
     * number of tiles — which is exactly how the {@code BOTTOM_UP} case survived a release.</p>
     */
    @Test
    public void theTileGridPinsToTheEdgeThatMoves() {
        assertSame("a tank's waterline is its top edge",
                NativeAnchor.TOP_LEFT, FluidSlot.anchorFor(FluidSlot.FillDirection.BOTTOM_UP));
        assertSame("a draining reservoir's moving edge is its bottom",
                NativeAnchor.BOTTOM_LEFT, FluidSlot.anchorFor(FluidSlot.FillDirection.TOP_DOWN));
        assertSame("a gauge growing rightwards moves its right edge",
                NativeAnchor.TOP_RIGHT, FluidSlot.anchorFor(FluidSlot.FillDirection.LEFT_RIGHT));
        assertSame("...and one growing leftwards moves its left edge, which is already the near end",
                NativeAnchor.TOP_LEFT, FluidSlot.anchorFor(FluidSlot.FillDirection.RIGHT_LEFT));
    }

    /**
     * The counter-assertion: the anchored corner has to be on the axis {@code fillBox} actually narrows,
     * or the fix is pinning the wrong edge and looks identical for every fill that happens to be a whole
     * number of tiles. Derived from {@code fillBox} rather than restated, so the two cannot drift.
     */
    @Test
    public void theAnchoredAxisIsTheOneThatNarrows() {
        for (FluidSlot.FillDirection direction : FluidSlot.FillDirection.values()) {
            float[] half = FluidSlot.fillBox(direction, 0f, 0f, 64f, 64f, 0.5f);
            boolean narrowsHorizontally = half[2] < 64f;
            NativeAnchor anchor = FluidSlot.anchorFor(direction);
            if (narrowsHorizontally) {
                assertEquals(direction + " narrows width, so only the vertical anchor may be the default",
                        false, anchor.fromBottom());
            } else {
                assertEquals(direction + " narrows height, so only the horizontal anchor may be the default",
                        false, anchor.fromRight());
            }
        }
    }

    /** The floor is a floor, not an override — a slot thinner than a pixel is not grown out of itself. */
    @Test
    public void theOnePixelFloorNeverOverflowsASmallerBox() {
        float[] box = FluidSlot.fillBox(FluidSlot.FillDirection.BOTTOM_UP, 0f, 0f, 16f, 0.5f, 0.5f);
        assertEquals(0.5f, box[3], 0.001f);
        assertEquals("still inside the box it was given", 0f, box[1], 0.001f);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static StateMap<Object> write(UIElement element) {
        StateMap<Object> out = new StateMap<>(PlainOps.INSTANCE);
        element.writeStateTo(out);
        return out;
    }

    private static NativeContent descriptor(String descriptor) {
        return new NativeContent() {
            @Override public String descriptor() { return descriptor; }
            @Override public NativeProfile profile() { return NativeProfile.MODEL; }
            @Override public boolean isEmpty() { return false; }
        };
    }

    /** A service that is available and resolves anything, so "did it reach the service" is observable. */
    private static final class RecordingService implements NativeContentService {
        @Override public boolean isAvailable() { return true; }
        @Override public NativeContent resolve(String d) { return d.isEmpty() ? NativeContent.EMPTY : descriptor(d); }
        @Override public void draw(NativeSurface surface, NativeContent content) { }
        @Override public void drawTooltip(NativeContent c, float x, float y, int w, int h) { }
    }
}
