package com.crystalgui.text.diff;

import java.util.Collections;
import java.util.List;

/**
 * What a merge region currently contributes to the result.
 *
 * <p>Ported from {@code ModifiedBaseRangeState} and its subclasses in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>
 * ({@code .../mergeEditor/browser/model/modifiedBaseRange.ts}), MIT. <b>Modified:</b> expressed as a sealed
 * interface over records rather than an abstract class hierarchy, and {@code Custom} is added — upstream
 * keeps hand-written text in the result document instead of on the state.</p>
 *
 * <h3>Why this is not an enum</h3>
 *
 * <p>It was one here, and the enum could not say two things it needs to.</p>
 *
 * <p><b>"Take both" is four answers, not one.</b> Which side comes first changes the result, and whether the
 * two sides are <em>interleaved</em> by their position in the base or simply concatenated changes it again.
 * As enum constants that is a combinatorial fan-out; as a record with two booleans it is one case.</p>
 *
 * <p><b>{@link Unrecognized} is a state, not an absence.</b> When somebody edits the merged text by hand it
 * stops corresponding to any choice — and that is a fact worth carrying, because it is per <em>region</em>.
 * A single global "the user typed something" flag has to disable every control in the view; knowing which
 * region went unrecognised leaves the others working.</p>
 */
public sealed interface RegionState {

    /** The lines this state contributes, given the region's three sides. */
    List<String> linesOf(ThreeWayMerge.Region region);

    /** The ancestor's lines — i.e. neither side's change. */
    record Base() implements RegionState {
        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return region.baseLines();
        }
    }

    /** The local side. */
    record Mine() implements RegionState {
        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return region.mineLines();
        }
    }

    /** The remote side. */
    record Theirs() implements RegionState {
        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return region.theirsLines();
        }
    }

    /**
     * Both sides.
     *
     * @param mineFirst which side leads. Only observable when the two changes cannot be interleaved, or
     *                  when they land at the same place in the base — see
     *                  {@link ThreeWayMerge.Region#isOrderRelevant()}
     * @param smart     whether to interleave the two sides' edits <b>by their position in the base</b>
     *                  rather than concatenating one region after the other. Concatenation is what a person
     *                  means by "take both" only when the two edits are adjacent; where they are separated
     *                  by unchanged text, concatenating duplicates that text
     */
    record Both(boolean mineFirst, boolean smart) implements RegionState {
        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return region.combine(mineFirst, smart);
        }
    }

    /** Lines somebody typed for this region deliberately. */
    record Custom(List<String> lines) implements RegionState {
        public Custom {
            lines = Collections.unmodifiableList(List.copyOf(lines));
        }

        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return lines;
        }
    }

    /**
     * The result was edited and no longer corresponds to any choice.
     *
     * <p>Distinct from {@link Custom}, which is a decision. This is the absence of one — the text is
     * whatever is there, and the view should say so rather than implying a side was picked.</p>
     */
    record Unrecognized(List<String> lines) implements RegionState {
        public Unrecognized {
            lines = Collections.unmodifiableList(List.copyOf(lines));
        }

        @Override
        public List<String> linesOf(ThreeWayMerge.Region region) {
            return lines;
        }
    }
}
