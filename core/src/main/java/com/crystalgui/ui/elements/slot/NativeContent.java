package com.crystalgui.ui.elements.slot;

/**
 * <b>What a slot shows — an opaque handle minted by the platform, never a value {@code core} understands.</b>
 *
 * <p>A handle is either a <em>binding</em> onto something the host owns (an inventory slot, a tank) or a
 * standalone display value. The distinction matters and is deliberately invisible from here: a bound
 * handle reads through to live contents every frame, so the host's own synchronisation keeps it current
 * and CrystalGUI never carries item data over its own wire.</p>
 *
 * <h3>Binding rather than owning is the whole design</h3>
 *
 * <p>The obvious alternative is to make a slot hold an item value and serialise it through
 * {@code UIDescriptionCodec} like any other widget state. That builds a second, competing mechanism for a
 * fact Minecraft's container protocol already synchronises — and the two would disagree the moment a
 * hopper moved something. Container UIs are the primary consumer here, so the slot is a <em>view</em>:
 * {@link #isEmpty()} and everything else is re-read from the binding at paint time, never cached.</p>
 *
 * <p>The same choice is what makes a dedicated server able to describe a slot at all. It ships
 * {@link #descriptor()} — which names <em>where to look</em>, not what is there — and the client resolves
 * it against its own world.</p>
 */
public interface NativeContent {

    /** A handle that shows nothing. What a slot holds until something binds it. */
    NativeContent EMPTY = new NativeContent() {
        @Override public String descriptor() { return ""; }
        @Override public NativeProfile profile() { return NativeProfile.FLAT; }
        @Override public boolean isEmpty() { return true; }
        @Override public String toString() { return "NativeContent.EMPTY"; }
    };

    /**
     * A stable, platform-defined string this handle can be rebuilt from via
     * {@link NativeContentService#resolve(String)}.
     *
     * <p>This is the slot's entire serialised state, and it names a <em>location</em> rather than a value
     * — {@code "slot:12"}, not the stack in it. Opaque to {@code core} by construction: the moment this
     * layer starts parsing it, it has learned what an item is.</p>
     *
     * <p>Must round-trip: {@code resolve(x.descriptor()).descriptor().equals(x.descriptor())}. The
     * element retains the raw string even when nothing can resolve it, so a description written by a
     * server and read on a client with no renderer still survives re-encoding intact.</p>
     */
    String descriptor();

    /** The GL contract this content needs. @see NativeProfile */
    NativeProfile profile();

    /**
     * Whether there is nothing to draw right now.
     *
     * <p>Re-read every frame for a bound handle — an emptied inventory slot answers {@code true} without
     * anything having touched the element.</p>
     */
    boolean isEmpty();

    /**
     * How much of the slot this content occupies, {@code 0..1}. Defaults to fully.
     *
     * <p>For content whose quantity is <em>spatial</em> — a tank that is a third full draws a third of a
     * fluid — the element needs this to size the draw box, and sizing is the element's job rather than
     * the renderer's. An item's count is not spatial (a stack of 64 is drawn the same size as one and
     * says "64" over it), so it leaves this at 1 and lets the host draw its own decorations.</p>
     */
    default float fillFraction() {
        return 1f;
    }
}
