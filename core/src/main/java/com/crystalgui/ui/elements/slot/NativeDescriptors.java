package com.crystalgui.ui.elements.slot;

/**
 * <b>The descriptor grammar — the one part of native content every platform must agree on.</b>
 *
 * <p>A {@link NativeContent#descriptor() descriptor} is what a {@link NativeContentSlot} serialises,
 * so it is the string a dedicated server authors, a description content-hashes, and a client of
 * <em>any</em> Minecraft version resolves. Two loaders that spell it differently make the same
 * serialised UI description mean different things on different clients — which is why the grammar
 * lives here, in {@code core}, and a loader implements against it rather than inventing its own.
 * The harness's stand-in service parses the same grammar, so there are always at least two
 * independent implementations keeping it honest.</p>
 *
 * <h3>The three forms</h3>
 *
 * <pre>
 * slot:&lt;index&gt;                                 a container slot BINDING — a location, resolved live
 * item:&lt;namespace&gt;:&lt;path&gt;[:damage[:count]]     a display item,  e.g. item:minecraft:stone:0:64
 * fluid:&lt;name&gt;:&lt;amount&gt;:&lt;capacity&gt;             a display fluid, e.g. fluid:water:620:1000
 * </pre>
 *
 * <h3>Segments are counted from the RIGHT</h3>
 *
 * <p>The id itself contains a colon ({@code minecraft:stone}), so a forward split cannot tell the
 * path from the first numeric field. Instead the trailing integer segments are consumed from the
 * right — damage then count for an item, amount then capacity for a fluid — and whatever remains is
 * the id. This also absorbs the version split for fluids without a second grammar: a 1.7.10
 * {@code FluidRegistry} name is bare ({@code water}), a modern one is {@code ns:path}, and both are
 * simply "whatever is left of the numerics".</p>
 *
 * <p>An item id is <b>exactly</b> {@code namespace:path} — a registry path cannot contain a colon on
 * any version — which settles the one ambiguous spelling: in {@code item:minecraft:5} the {@code 5}
 * is a <em>path</em>, not a damage value, because consuming it would leave a one-segment id. Anything
 * left over after the numerics that is not exactly two segments is refused rather than guessed at.
 * A fluid name keeps at least one segment (bare on 1.7.10, namespaced on modern versions), so it
 * takes whatever the two required numerics leave.</p>
 *
 * <h3>Formatting is canonical; parsing is tolerant</h3>
 *
 * <p>{@link #item(String, int, int)} always writes damage and count, even at their defaults, because
 * a description is content-addressed: two spellings of the same stack would hash as two different
 * UIs. (It also keeps every descriptor already written by {@code Mc1710Content} byte-identical.)
 * The parser accepts the short forms, because leniency on the way <em>in</em> costs nothing.</p>
 *
 * <p>Malformed input parses to <b>null, never a throw</b>. A descriptor is wire data, and the
 * service contract for the unresolvable is already {@link NativeContent#EMPTY} — a bare well on
 * screen — not an exception on the paint path.</p>
 *
 * <h3>NBT is deliberately outside the grammar</h3>
 *
 * <p>It has no bounded text form, and a description is content-addressed, so a large tag would be
 * re-hashed on every change of a value nothing here reads. A display stack that needs NBT is
 * authored through a loader-side handle ({@code Mc1710Content.DisplayItem} and its future
 * siblings); that is a feature boundary, not a gap.</p>
 */
public final class NativeDescriptors {

    private NativeDescriptors() {
    }

    /** {@code slot:} — a live view onto the player's open container. */
    public static final String SLOT_PREFIX = "slot:";
    /** {@code item:} — a standalone display stack. */
    public static final String ITEM_PREFIX = "item:";
    /** {@code fluid:} — a standalone display fluid plus its tank capacity. */
    public static final String FLUID_PREFIX = "fluid:";

    /**
     * The descriptor's <b>claimed</b> kind, from its prefix alone.
     *
     * <p>Prefix rather than full parse on purpose: kind is UI-dispatch intent — "which element shape
     * does this belong in" — and validity stays resolution's job. A malformed {@code item:xyz} still
     * <em>means</em> item, and resolving it to {@link NativeContent#EMPTY} is a separate, later
     * answer. {@code slot:} claims {@link NativeContentKind#ITEM}, because a container slot holds
     * items — whether content is a <em>binding</em> is the other axis, {@link NativeContent#isBinding()}.</p>
     */
    public static NativeContentKind kindOf(String descriptor) {
        if (descriptor == null) return NativeContentKind.NONE;
        if (descriptor.startsWith(SLOT_PREFIX) || descriptor.startsWith(ITEM_PREFIX)) {
            return NativeContentKind.ITEM;
        }
        if (descriptor.startsWith(FLUID_PREFIX)) return NativeContentKind.FLUID;
        return NativeContentKind.NONE;
    }

    // ── Formatting — the canonical spellings ────────────────────────────────

    /** {@code slot:<index>}. */
    public static String slot(int index) {
        if (index < 0) throw new IllegalArgumentException("slot index must be >= 0, was " + index);
        return SLOT_PREFIX + index;
    }

    /** {@code item:<id>:<damage>:<count>}, always the full form — see the class note on hashing. */
    public static String item(String id, int damage, int count) {
        requireId(id, "item id");
        if (damage < 0) throw new IllegalArgumentException("damage must be >= 0, was " + damage);
        if (count < 0) throw new IllegalArgumentException("count must be >= 0, was " + count);
        return ITEM_PREFIX + id + ":" + damage + ":" + count;
    }

    /** {@code fluid:<name>:<amount>:<capacity>}. */
    public static String fluid(String name, int amount, int capacity) {
        requireId(name, "fluid name");
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0, was " + amount);
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        return FLUID_PREFIX + name + ":" + amount + ":" + capacity;
    }

    // ── Parsing — tolerant, null for anything malformed ─────────────────────

    /** A parsed {@code slot:} descriptor. */
    public record SlotRef(int index) {
    }

    /** A parsed {@code item:} descriptor. Damage defaults to 0, count to 1. */
    public record ItemRef(String id, int damage, int count) {
    }

    /** A parsed {@code fluid:} descriptor. Both numerics are required by the grammar. */
    public record FluidRef(String name, int amount, int capacity) {
    }

    /** Parses {@code slot:<index>}, or null. */
    public static SlotRef parseSlot(String descriptor) {
        String body = bodyOf(descriptor, SLOT_PREFIX);
        if (body == null) return null;
        Integer index = parseNonNegative(body);
        return index == null ? null : new SlotRef(index);
    }

    /** Parses {@code item:<ns>:<path>[:damage[:count]]}, or null. The id is exactly two segments. */
    public static ItemRef parseItem(String descriptor) {
        String body = bodyOf(descriptor, ITEM_PREFIX);
        if (body == null) return null;
        String[] segments = body.split(":", -1);
        // Consume up to two trailing integers from the right, but never into the ns:path pair itself --
        // which is what keeps `item:minecraft:5` a path rather than a damage value.
        int idEnd = segments.length;
        int[] numerics = new int[2];
        int taken = 0;
        while (taken < 2 && idEnd > 2) {
            Integer value = parseNonNegative(segments[idEnd - 1]);
            if (value == null) break;
            numerics[taken++] = value;
            idEnd--;
        }
        // Exactly ns:path must remain. More means a malformed spelling (`item:a:b:c:1:2`), and
        // refusing beats guessing which of the leftovers was meant to be the path.
        if (idEnd != 2) return null;
        String id = joinId(segments, idEnd);
        if (id == null) return null;
        // numerics were consumed right-to-left, so with two taken the FIRST consumed is the count.
        int damage = taken == 2 ? numerics[1] : taken == 1 ? numerics[0] : 0;
        int count = taken == 2 ? numerics[0] : 1;
        return new ItemRef(id, damage, count);
    }

    /** Parses {@code fluid:<name>:<amount>:<capacity>}, or null. Both numerics are required. */
    public static FluidRef parseFluid(String descriptor) {
        String body = bodyOf(descriptor, FLUID_PREFIX);
        if (body == null) return null;
        String[] segments = body.split(":", -1);
        if (segments.length < 3) return null;
        Integer capacity = parseNonNegative(segments[segments.length - 1]);
        Integer amount = parseNonNegative(segments[segments.length - 2]);
        if (capacity == null || amount == null || capacity < 1) return null;
        String name = joinId(segments, segments.length - 2);
        if (name == null) return null;
        return new FluidRef(name, amount, capacity);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** The text after {@code prefix}, or null when the descriptor is not that kind or is empty. */
    private static String bodyOf(String descriptor, String prefix) {
        if (descriptor == null || !descriptor.startsWith(prefix)) return null;
        String body = descriptor.substring(prefix.length());
        return body.isEmpty() ? null : body;
    }

    /**
     * A strict non-negative decimal integer, or null. {@code Integer.parseInt} alone would accept
     * {@code +5} and {@code -0}, and a signed segment in wire data is malformed, not a number.
     */
    private static Integer parseNonNegative(String segment) {
        if (segment.isEmpty() || segment.length() > 10) return null;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        long value = Long.parseLong(segment);
        return value > Integer.MAX_VALUE ? null : (int) value;
    }

    /** Segments {@code [0, end)} re-joined with colons, or null when any is empty ({@code a::b}). */
    private static String joinId(String[] segments, int end) {
        if (end < 1) return null;
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (segments[i].isEmpty()) return null;
            if (i > 0) id.append(':');
            id.append(segments[i]);
        }
        return id.toString();
    }

    private static void requireId(String id, String what) {
        if (id == null || id.isEmpty() || id.contains(" ")) {
            throw new IllegalArgumentException(what + " must be non-empty with no spaces, was '" + id + "'");
        }
    }
}
