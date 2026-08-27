package com.crystalgui.ui.elements.slot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * The descriptor grammar, pinned.
 *
 * <p>This is a <em>contract</em> test rather than a unit test: two independent implementations parse
 * these strings (the 1.7.10 service and the harness stand-in, with 1.20.x to follow), and a
 * serialised UI description written by any of them must mean the same thing to all of them. A
 * change that fails this file is a wire-format change and needs to be treated as one.</p>
 *
 * <p>It lives in {@code headlessTest} deliberately: a dedicated server authors these strings, and
 * this classpath — CrystalGraphics core absent by construction — is the proof it can.</p>
 */
public class NativeDescriptorsTest {

    // ── Round trips — format and parse agree ────────────────────────────────

    @Test
    public void aSlotRoundTrips() {
        assertEquals("slot:12", NativeDescriptors.slot(12));
        assertEquals(new NativeDescriptors.SlotRef(12), NativeDescriptors.parseSlot("slot:12"));
        assertEquals(new NativeDescriptors.SlotRef(0), NativeDescriptors.parseSlot(NativeDescriptors.slot(0)));
    }

    @Test
    public void anItemRoundTrips() {
        assertEquals("item:minecraft:stone:0:64", NativeDescriptors.item("minecraft:stone", 0, 64));
        assertEquals(new NativeDescriptors.ItemRef("minecraft:stone", 0, 64),
                NativeDescriptors.parseItem("item:minecraft:stone:0:64"));
    }

    @Test
    public void aFluidRoundTrips() {
        assertEquals("fluid:water:620:1000", NativeDescriptors.fluid("water", 620, 1000));
        assertEquals(new NativeDescriptors.FluidRef("water", 620, 1000),
                NativeDescriptors.parseFluid("fluid:water:620:1000"));
    }

    /**
     * The canonical item form always writes damage and count, even at their defaults. A description
     * is content-addressed, so two spellings of the same stack would hash as two different UIs — and
     * this is also what keeps every descriptor {@code Mc1710Content} has already written
     * byte-identical.
     */
    @Test
    public void formattingIsCanonicalEvenAtDefaults() {
        assertEquals("item:minecraft:stick:0:1", NativeDescriptors.item("minecraft:stick", 0, 1));
    }

    // ── The right-to-left segment rule ──────────────────────────────────────

    /** The id contains a colon, so numerics are consumed from the right and the id is what remains. */
    @Test
    public void trailingNumericsAreDamageThenCount() {
        assertEquals(new NativeDescriptors.ItemRef("minecraft:stone", 0, 1),
                NativeDescriptors.parseItem("item:minecraft:stone"));
        assertEquals(new NativeDescriptors.ItemRef("minecraft:stone", 3, 1),
                NativeDescriptors.parseItem("item:minecraft:stone:3"));
        assertEquals(new NativeDescriptors.ItemRef("minecraft:stone", 3, 64),
                NativeDescriptors.parseItem("item:minecraft:stone:3:64"));
    }

    /**
     * The one ambiguous spelling, settled by the at-least-two-segments rule: in {@code item:minecraft:5}
     * the {@code 5} is a PATH, because consuming it would leave a one-segment id. A registry path may
     * legally be numeric, and an id can never legally be one bare word — so the tie always breaks the
     * same way.
     */
    @Test
    public void aNumericPathIsNotEatenAsDamage() {
        assertEquals(new NativeDescriptors.ItemRef("minecraft:5", 0, 1),
                NativeDescriptors.parseItem("item:minecraft:5"));
        // With explicit numerics after it, the same path parses under the same rule.
        assertEquals(new NativeDescriptors.ItemRef("minecraft:5", 2, 16),
                NativeDescriptors.parseItem("item:minecraft:5:2:16"));
    }

    /**
     * The version split for fluids, absorbed without a second grammar: a 1.7.10 registry name is bare,
     * a modern one is namespaced, and both are "whatever is left of the two numerics".
     */
    @Test
    public void aFluidNameMayBeBareOrNamespaced() {
        assertEquals(new NativeDescriptors.FluidRef("water", 620, 1000),
                NativeDescriptors.parseFluid("fluid:water:620:1000"));
        assertEquals(new NativeDescriptors.FluidRef("minecraft:water", 620, 1000),
                NativeDescriptors.parseFluid("fluid:minecraft:water:620:1000"));
    }

    // ── kindOf — the claimed kind, from the prefix alone ────────────────────

    /**
     * `slot:` claims ITEM (a container slot holds items); whether content is a BINDING is the other
     * axis and deliberately not a kind — see NativeContentKind's javadoc for why folding them breaks
     * on the first tank binding.
     */
    @Test
    public void kindIsClaimedByThePrefix() {
        assertSame(NativeContentKind.ITEM, NativeDescriptors.kindOf("slot:12"));
        assertSame(NativeContentKind.ITEM, NativeDescriptors.kindOf("item:minecraft:stone:0:64"));
        assertSame(NativeContentKind.FLUID, NativeDescriptors.kindOf("fluid:water:620:1000"));
        assertSame(NativeContentKind.NONE, NativeDescriptors.kindOf(""));
        assertSame(NativeContentKind.NONE, NativeDescriptors.kindOf(null));
        assertSame(NativeContentKind.NONE, NativeDescriptors.kindOf("entity:minecraft:creeper"));
    }

    /**
     * CLAIMED, not validated: a malformed `item:xyz` still means item — kind is UI-dispatch intent
     * ("which element shape does this belong in"), and resolving it to EMPTY is a separate, later
     * answer. A kindOf that ran the full parser would flicker a slot's shape on a typo.
     */
    @Test
    public void aMalformedDescriptorStillClaimsItsKind() {
        assertSame(NativeContentKind.ITEM, NativeDescriptors.kindOf("item:xyz"));
        assertSame(NativeContentKind.FLUID, NativeDescriptors.kindOf("fluid:water"));
        assertSame(NativeContentKind.ITEM, NativeDescriptors.kindOf("slot:notanumber"));
    }

    // ── Malformed input answers null, never a throw ─────────────────────────

    /**
     * A descriptor is wire data and the service contract for the unresolvable is already
     * {@link NativeContent#EMPTY} — so the parser's job on garbage is to say "not mine", not to take
     * the paint path down. Every case here is a shape that a stale description, a missing mod or a
     * hand-edited config could genuinely produce.
     */
    @Test
    public void malformedInputParsesToNull() {
        String[] garbage = {
                null, "", "slot:", "slot:x", "slot:-1", "slot:12:extra",
                "item:", "item:minecraft", "item:minecraft:stone:-1:1", "item:minecraft::0:1",
                "item:minecraft:stone:1:2:3",
                "fluid:", "fluid:water", "fluid:water:620", "fluid:water:x:1000",
                "fluid:water:620:0", "fluid::620:1000",
                "entity:minecraft:creeper",
        };
        for (String descriptor : garbage) {
            assertNull("parseSlot(" + descriptor + ")", NativeDescriptors.parseSlot(descriptor));
            assertNull("parseItem(" + descriptor + ")", NativeDescriptors.parseItem(descriptor));
            assertNull("parseFluid(" + descriptor + ")", NativeDescriptors.parseFluid(descriptor));
        }
    }

    /** Each parser answers only its own prefix — the dispatcher in a service relies on that. */
    @Test
    public void aParserRefusesTheOtherKinds() {
        assertNull(NativeDescriptors.parseSlot("item:minecraft:stone:0:1"));
        assertNull(NativeDescriptors.parseItem("slot:12"));
        assertNull(NativeDescriptors.parseFluid("item:minecraft:stone:0:1"));
    }

    /**
     * A signed segment in wire data is malformed, not a number — {@code Integer.parseInt} alone would
     * accept {@code +5}, and an overflowing digit string must not wrap into a plausible value.
     */
    @Test
    public void signsAndOverflowAreRefused() {
        assertNull(NativeDescriptors.parseSlot("slot:+5"));
        assertNull(NativeDescriptors.parseSlot("slot:99999999999"));
        assertEquals("a huge trailing segment is not a count either — the whole descriptor is refused, "
                        + "because a five-billion count is garbage, not an id segment",
                null, NativeDescriptors.parseItem("item:minecraft:stone:0:99999999999"));
    }

    // ── The formatters refuse to write garbage ──────────────────────────────

    /** Formatting throws where parsing answers null: authored code is not wire data. */
    @Test
    public void formattersValidateTheirArguments() {
        try {
            NativeDescriptors.slot(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // A negative index is a programming error at the authoring site, not a resolvable state.
        }
        try {
            NativeDescriptors.fluid("water", 500, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Capacity zero makes fillFraction meaningless.
        }
        try {
            NativeDescriptors.item("", 0, 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // An empty id would format a descriptor its own parser refuses.
        }
    }
}
