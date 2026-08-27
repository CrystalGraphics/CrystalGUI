package com.crystalgui.ui.elements.slot;

/**
 * <b>What a {@link NativeContent} displays</b> — the question abstract code asks when deciding which
 * element shape content belongs in, which drop targets accept it, or which branch of a renderer
 * draws it.
 *
 * <h3>Deliberately not the same axis as WHERE the content lives</h3>
 *
 * <p>There is no {@code SLOT} constant, although {@code slot:} is a descriptor prefix: a
 * {@code slot:12} binding <em>contains an item</em>, so its kind is {@link #ITEM} and its
 * binding-ness is {@link NativeContent#isBinding()} — a separate question with a separate answer.
 * Folding the two into one enum breaks the first time a second binding kind exists: a fluid-tank
 * binding would be {@link #FLUID} + bound, not a fifth constant, and an entity display adds exactly
 * one constant here rather than one per place it can live.</p>
 *
 * <h3>...and not the same axis as {@link NativeProfile} either</h3>
 *
 * <p>{@code profile()} is the GL contract a draw needs (depth or not) and merely <em>correlates</em>
 * with kind today — an entity will also be {@code MODEL}, so anything dispatching item-vs-fluid on
 * the profile rots the day a third kind arrives. Kind is the honest discriminator; profile is what
 * the paint context sets up.</p>
 */
public enum NativeContentKind {

    /** An item stack — held directly, or behind a {@code slot:} binding. */
    ITEM,

    /** A fluid and how full its tank is. */
    FLUID,

    /** Nothing displayable — {@link NativeContent#EMPTY}, or a descriptor of no known kind. */
    NONE
}
