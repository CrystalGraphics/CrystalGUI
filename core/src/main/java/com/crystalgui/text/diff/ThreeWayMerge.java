package com.crystalgui.text.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A three-way merge over {@link LineDiff} — Phase 6.7. <b>The thing that makes a merger not a viewer.</b>
 *
 * <h3>Why three-way and not two</h3>
 *
 * <p>A two-way diff can only ever ask "which of these two do you want", once per difference. Over a file
 * where the two sides have each moved on a little, that is dozens of questions, every one of which a person
 * has to answer by reading and reasoning — and the answer to nearly all of them is mechanical.</p>
 *
 * <p>With a <b>common ancestor</b> most of them stop being questions at all. A region only one side touched
 * has exactly one sensible answer: take that side. A region both sides changed <em>the same way</em> has one
 * too. What is left — regions both sides changed <em>differently</em> — is the only thing a person genuinely
 * has to decide, and it is usually a handful. That reduction is the entire value of a merger.</p>
 *
 * <p>The base is not hypothetical here: {@code WorkspaceClient} already retains the bytes it last read from
 * the server as {@code cachedContent}. That is precisely the common ancestor of the editor's buffer and
 * whatever the server now holds, so the conflict case has a base for free and never falls back to two-way.</p>
 *
 * <h3>The unit is a REGION, not a hunk</h3>
 *
 * <p>A conflict is not "my third hunk against their third hunk" — the two sides have no hunks in common,
 * only a base. So the walk groups every hunk from either side touching <b>the same span of base lines</b>
 * into one region and classifies that. Two small edits of mine against one spanning edit of theirs is
 * <em>one</em> conflict, not three.</p>
 *
 * <p>Getting this wrong does not merely misreport the count. Overlapping regions cannot be assembled into an
 * output at all — two resolutions would each claim the same base lines — so the failure is a corrupted merge
 * rather than a confusing one.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p>Line granularity. Two people editing different words of one line is a conflict here, and a word-level
 * merge would resolve it silently — which is how a merge tool produces a line neither author wrote and
 * neither would accept. Git draws the line in the same place.</p>
 */
public final class ThreeWayMerge {

    /**
     * What the analysis <em>found</em> about a region — a fact about the three texts, never a decision.
     *
     * <p>Kept apart from {@link Resolution}, which is what the region currently resolves to and which a
     * person may change. Folding them together loses the ability to say "this was a conflict and you chose
     * mine": a UI could then never mark a resolved conflict differently from an auto-merge, and re-running
     * the analysis would silently discard every choice already made.</p>
     */
    public enum Kind {
        /** Only the local side touched these lines. */
        MINE_ONLY,
        /** Only the remote side touched them. */
        THEIRS_ONLY,
        /** Both sides changed them and arrived at the same text. */
        BOTH_SAME,
        /** Both sides changed them, differently. The only kind that needs a person. */
        CONFLICT;

        /** Whether this region resolves itself. */
        public boolean isAutomatic() {
            return this != CONFLICT;
        }
    }

    /**
     * One span of base lines that at least one side changed, and how it currently resolves.
     *
     * <p>Ranges are half-open and each is in <b>its own text's</b> coordinates — a region is the one place
     * the three coordinate systems are lined up against each other, which is exactly what a side-by-side
     * view needs to paint panes that agree.</p>
     */
    public static final class Region {

        private final Kind kind;
        private final int baseFrom, baseTo;
        private final int mineFrom, mineTo;
        private final int theirsFrom, theirsTo;

        private final List<String> baseLines, mineLines, theirsLines;

        private RegionState state;

        /**
         * Whether a person has settled this region.
         *
         * <p>An automatic region is settled on arrival; a conflict is settled only once somebody has chosen,
         * <b>including choosing the side it already defaulted to</b>. That distinction is why {@link #accept}
         * is not simply a field write: a conflict pre-pointed at MINE and a conflict deliberately resolved to
         * MINE produce identical output and must not look identical, or the merge reports itself finished
         * before anybody has read it.</p>
         */
        private boolean settled;

        private Region(Kind kind, int baseFrom, int baseTo, int mineFrom, int mineTo, int theirsFrom,
                int theirsTo, List<String> base, List<String> mine, List<String> theirs) {
            this.kind = kind;
            this.baseFrom = baseFrom;
            this.baseTo = baseTo;
            this.mineFrom = mineFrom;
            this.mineTo = mineTo;
            this.theirsFrom = theirsFrom;
            this.theirsTo = theirsTo;
            this.baseLines = Collections.unmodifiableList(new ArrayList<>(base.subList(baseFrom, baseTo)));
            this.mineLines = Collections.unmodifiableList(new ArrayList<>(mine.subList(mineFrom, mineTo)));
            this.theirsLines =
                    Collections.unmodifiableList(new ArrayList<>(theirs.subList(theirsFrom, theirsTo)));
            this.state = defaultState(kind);
        }

        private static RegionState defaultState(Kind kind) {
            if (kind == Kind.THEIRS_ONLY) return new RegionState.Theirs();
            // A conflict also starts on Mine, so an unresolved merge never silently discards local work.
            // That is a starting point and not an answer -- isResolved() stays false.
            return new RegionState.Mine();
        }

        public Kind kind() {
            return kind;
        }

        public int baseFrom() {
            return baseFrom;
        }

        public int baseTo() {
            return baseTo;
        }

        public int mineFrom() {
            return mineFrom;
        }

        public int mineTo() {
            return mineTo;
        }

        public int theirsFrom() {
            return theirsFrom;
        }

        public int theirsTo() {
            return theirsTo;
        }

        public List<String> baseLines() {
            return baseLines;
        }

        public List<String> mineLines() {
            return mineLines;
        }

        public List<String> theirsLines() {
            return theirsLines;
        }

        public RegionState state() {
            return state;
        }

        /** Whether this region is settled. */
        public boolean isResolved() {
            return kind.isAutomatic() || settled;
        }

        /** Cached magic-resolve answer: null means unattempted, an empty Optional means it clashed. */
        private java.util.Optional<List<String>> suggestion;

        /**
         * The text this conflict would resolve to if the two sides are only <em>apparently</em> in
         * conflict — see {@link MagicResolve}. Empty when they genuinely clash, and for a region that was
         * never a conflict at all.
         *
         * <p>Computed once and cached, because it runs a whole character-level three-way merge and a UI
         * asks per frame.</p>
         */
        public java.util.Optional<List<String>> suggestedResolution() {
            if (kind != Kind.CONFLICT) return java.util.Optional.empty();
            if (suggestion == null) {
                List<String> resolved = MagicResolve.tryResolve(baseLines, mineLines, theirsLines);
                suggestion = java.util.Optional.ofNullable(resolved);
            }
            return suggestion;
        }

        /** Choose a state. Marks a conflict settled. */
        public void accept(RegionState choice) {
            this.state = Objects.requireNonNull(choice, "choice");
            this.settled = true;
        }

        public void acceptMine() {
            accept(new RegionState.Mine());
        }

        public void acceptTheirs() {
            accept(new RegionState.Theirs());
        }

        /** Both sides, interleaved by base position where that is possible. */
        public void acceptBoth() {
            accept(new RegionState.Both(true, true));
        }

        /** Supply text nobody wrote yet - a deliberate decision, unlike {@link #markUnrecognized}. */
        public void acceptCustom(List<String> lines) {
            accept(new RegionState.Custom(lines));
        }

        /**
         * Records that the merged text was edited by hand and no longer matches any choice.
         *
         * <p>Per region, which is the point: a global "something was typed" latch has to disable every
         * control in the view, while knowing <em>which</em> region went unrecognised leaves the rest of the
         * merge working. It counts as settled - somebody decided, by typing.</p>
         */
        public void markUnrecognized(List<String> lines) {
            accept(new RegionState.Unrecognized(lines));
        }

        /** The lines this region contributes to the merged output as it currently stands. */
        public List<String> resolvedLines() {
            return state.linesOf(this);
        }

        /**
         * Both sides' text, interleaved by position in the base when {@code smart} and possible.
         *
         * <p>Ported from {@code ModifiedBaseRange.smartCombineInputs} / {@code dumbCombineInputs}.
         * Concatenation is what "take both" means only when the two edits are adjacent; where they are
         * separated by unchanged text, concatenating <b>duplicates that text</b> - so a region where one
         * side edited the first line and the other the last comes out with the middle twice.</p>
         */
        List<String> combine(boolean mineFirst, boolean smart) {
            if (smart) {
                List<String> interleaved = interleave(mineFirst);
                if (interleaved != null) return interleaved;
            }
            List<String> out = new ArrayList<>(mineLines.size() + theirsLines.size());
            out.addAll(mineFirst ? mineLines : theirsLines);
            out.addAll(mineFirst ? theirsLines : mineLines);
            return out;
        }

        /** Null when the two sides' edits overlap in the base, where interleaving has no meaning. */
        @Nullable
        private List<String> interleave(boolean mineFirst) {
            List<Edit> edits = new ArrayList<>();
            for (DiffRange r : DiffIterable.of(baseLines, mineLines).changed()) {
                edits.add(new Edit(r.start1(), r.end1(), mineLines.subList(r.start2(), r.end2()), true));
            }
            for (DiffRange r : DiffIterable.of(baseLines, theirsLines).changed()) {
                edits.add(new Edit(r.start1(), r.end1(), theirsLines.subList(r.start2(), r.end2()), false));
            }
            edits.sort((a, b) -> a.baseFrom() != b.baseFrom()
                    ? Integer.compare(a.baseFrom(), b.baseFrom())
                    : Boolean.compare(a.mine() != mineFirst, b.mine() != mineFirst));

            List<String> out = new ArrayList<>();
            int at = 0;
            for (Edit edit : edits) {
                if (edit.baseFrom() < at) return null;
                out.addAll(baseLines.subList(at, edit.baseFrom()));
                out.addAll(edit.replacement());
                at = edit.baseTo();
            }
            out.addAll(baseLines.subList(at, baseLines.size()));
            return out;
        }

        /** Whether "take both" is meaningful here - i.e. the two sides' edits do not overlap. */
        public boolean canBeCombined() {
            return kind == Kind.CONFLICT && interleave(true) != null;
        }

        /**
         * Whether which side comes first changes the answer.
         *
         * <p>Worth asking because a view can then <em>say</em> so, rather than offering two buttons that
         * quietly do the same thing - or one that quietly picks.</p>
         */
        public boolean isOrderRelevant() {
            return !combine(true, true).equals(combine(false, true));
        }

        private record Edit(int baseFrom, int baseTo, List<String> replacement, boolean mine) {
        }
    }

    private final List<String> base, mine, theirs;
    private final List<Region> regions;

    private ThreeWayMerge(List<String> base, List<String> mine, List<String> theirs, List<Region> regions) {
        this.base = base;
        this.mine = mine;
        this.theirs = theirs;
        this.regions = regions;
    }

    /** Merge three texts, comparing exactly. */
    public static ThreeWayMerge of(String base, String mine, String theirs) {
        return of(base, mine, theirs, ComparisonPolicy.DEFAULT);
    }

    /** Merge three texts under a {@link ComparisonPolicy}. */
    public static ThreeWayMerge of(String base, String mine, String theirs, ComparisonPolicy policy) {
        return of(LineDiff.lines(base), LineDiff.lines(mine), LineDiff.lines(theirs), policy);
    }

    /** Merge three line lists, comparing exactly. */
    public static ThreeWayMerge of(List<String> base, List<String> mine, List<String> theirs) {
        return of(base, mine, theirs, ComparisonPolicy.DEFAULT);
    }

    /**
     * Merge three line lists, classifying every region either side touched.
     *
     * <p>Both diffs are taken <b>against the base</b>, which is what puts them in a shared coordinate
     * system; diffing mine against theirs directly gives hunks with nothing to align them to.</p>
     *
     * <p>The regions themselves come from {@link MergeRanges}, which intersects the two diffs'
     * <em>agreement</em> rather than grouping their changes — see that class for why. This replaced a
     * hand-written grouping loop whose correctness rested on one boundary comparison.</p>
     */
    public static ThreeWayMerge of(List<String> base, List<String> mine, List<String> theirs,
            ComparisonPolicy policy) {
        DiffIterable mineDiff = DiffIterable.of(base, mine, policy);
        DiffIterable theirsDiff = DiffIterable.of(base, theirs, policy);

        List<Region> regions = new ArrayList<>();
        for (MergeRange range : MergeRanges.build(mineDiff, theirsDiff)) {
            List<String> baseSlice = base.subList(range.baseFrom(), range.baseTo());
            List<String> mineSlice = mine.subList(range.mineFrom(), range.mineTo());
            List<String> theirsSlice = theirs.subList(range.theirsFrom(), range.theirsTo());

            boolean mineChanged = !policy.equal(mineSlice, baseSlice);
            boolean theirsChanged = !policy.equal(theirsSlice, baseSlice);

            Kind kind;
            if (!theirsChanged) {
                // Covers the both-unchanged case too, which is rare and real: the two diffs may agree on
                // the TEXT of a span while disagreeing about where the agreement starts, leaving a region
                // whose three sides are all equal. Resolving it to mine is then correct by construction,
                // since mine == base == theirs.
                kind = Kind.MINE_ONLY;
            } else if (!mineChanged) {
                kind = Kind.THEIRS_ONLY;
            } else {
                kind = policy.equal(mineSlice, theirsSlice) ? Kind.BOTH_SAME : Kind.CONFLICT;
            }

            regions.add(new Region(kind, range.baseFrom(), range.baseTo(), range.mineFrom(), range.mineTo(),
                    range.theirsFrom(), range.theirsTo(), base, mine, theirs));
        }

        return new ThreeWayMerge(base, mine, theirs, Collections.unmodifiableList(regions));
    }

    public List<Region> regions() {
        return regions;
    }

    public List<String> baseLines() {
        return base;
    }

    public List<String> mineLines() {
        return mine;
    }

    public List<String> theirsLines() {
        return theirs;
    }

    /** How many regions need a person — what a merge UI shows and gates its OK button on. */
    public int conflictCount() {
        int count = 0;
        for (Region region : regions) {
            if (region.kind() == Kind.CONFLICT) count++;
        }
        return count;
    }

    /** Whether every conflict has been decided. */
    public boolean isResolved() {
        for (Region region : regions) {
            if (!region.isResolved()) return false;
        }
        return true;
    }

    /** Conflicts in the order a "next conflict" button visits them. */
    public List<Region> conflicts() {
        List<Region> conflicts = new ArrayList<>();
        for (Region region : regions) {
            if (region.kind() == Kind.CONFLICT) conflicts.add(region);
        }
        return conflicts;
    }

    /**
     * Applies {@link MagicResolve} to every conflict that has an unambiguous answer.
     *
     * <p><b>Offered, never imposed.</b> A conflict resolved this way is still marked as having been a
     * conflict — the {@link Kind} does not change — so a view can show that a decision was made on the
     * user's behalf and let them look at it. Silently downgrading it to an auto-merge would hide the one
     * place where this stack guesses.</p>
     *
     * @return how many conflicts it settled
     */
    public int resolveConflictsAutomatically() {
        int resolved = 0;
        for (Region region : regions) {
            if (region.kind() != Kind.CONFLICT || region.isResolved()) continue;
            java.util.Optional<List<String>> suggestion = region.suggestedResolution();
            if (suggestion.isPresent()) {
                region.acceptCustom(suggestion.get());
                resolved++;
            }
        }
        return resolved;
    }

    /** Resolve every outstanding conflict one way — the "take everything from X" buttons. */
    public void acceptAll(RegionState choice) {
        for (Region region : regions) {
            if (region.kind() == Kind.CONFLICT) region.accept(choice);
        }
    }

    /**
     * The merged text as it currently stands.
     *
     * <p>Answerable at any time, conflicts outstanding or not — a merge UI has to paint the result while it
     * is still being decided, and refusing until everything is resolved would leave the centre pane with
     * nothing to show until the work was already finished.</p>
     */
    public List<String> mergedLines() {
        List<String> out = new ArrayList<>();
        int baseAt = 0;
        for (Region region : regions) {
            for (int line = baseAt; line < region.baseFrom(); line++) out.add(base.get(line));
            out.addAll(region.resolvedLines());
            baseAt = region.baseTo();
        }
        for (int line = baseAt; line < base.size(); line++) out.add(base.get(line));
        return out;
    }

    /** Which text a line number belongs to. @see #mapLine */
    public enum Side {
        MINE, BASE, THEIRS
    }

    /**
     * Translates a line number from one side's numbering into another's.
     *
     * <p><b>What keeps three panes looking at the same thing.</b> The three texts have different line
     * counts, so scrolling them to the same pixel offset - or even the same line - drifts them apart the
     * moment anything is inserted or deleted above. Region 5 of the merge is line 15 in one pane and line
     * 14 in another, and after a few edits it is line 40 against line 31.</p>
     *
     * <p>The regions are the anchors: outside them the three texts run in step, so a line is mapped by
     * finding the last region that starts at or before it and carrying the offset across. A line
     * <em>inside</em> a region maps to that region's start on the other side, because a region is where
     * the texts disagree and there is no finer correspondence to offer - which is honest, and is also why
     * a merge view cannot line up perfectly through a conflict however it scrolls.</p>
     */
    public int mapLine(int line, Side from, Side to) {
        if (from == to) return line;

        int fromStart = 0;
        int toStart = 0;
        for (Region region : regions) {
            int regionFrom = startOf(region, from);
            if (regionFrom > line) break;
            fromStart = regionFrom;
            toStart = startOf(region, to);
            // Past this region entirely: both sides advance to its end and keep running in step.
            if (endOf(region, from) <= line) {
                fromStart = endOf(region, from);
                toStart = endOf(region, to);
            } else {
                // Inside it. There is no finer correspondence than "this region".
                return toStart;
            }
        }
        return Math.max(0, toStart + (line - fromStart));
    }

    private static int startOf(Region region, Side side) {
        switch (side) {
            case MINE:
                return region.mineFrom();
            case THEIRS:
                return region.theirsFrom();
            default:
                return region.baseFrom();
        }
    }

    private static int endOf(Region region, Side side) {
        switch (side) {
            case MINE:
                return region.mineTo();
            case THEIRS:
                return region.theirsTo();
            default:
                return region.baseTo();
        }
    }

    /**
     * Where each region's contribution lands in {@link #mergedLines()}, parallel to {@link #regions()}.
     *
     * <p>What makes a hand edit attributable. Without it the only thing a view can say about somebody
     * typing into the result is "something changed somewhere", which forces a global latch that disables
     * every control; with it, the edit is charged to the regions it actually overlapped and the rest of
     * the merge keeps working.</p>
     */
    public List<int[]> resultRanges() {
        List<int[]> ranges = new ArrayList<>(regions.size());
        int outAt = 0;
        int baseAt = 0;
        for (Region region : regions) {
            outAt += region.baseFrom() - baseAt;
            int size = region.resolvedLines().size();
            ranges.add(new int[] {outAt, outAt + size});
            outAt += size;
            baseAt = region.baseTo();
        }
        return ranges;
    }

    /**
     * Charges a hand-edited result to the regions it touched, marking those {@code Unrecognized}.
     *
     * <p>Ported in behaviour from {@code MergeEditorModel}'s handling of an edited result. The edit is
     * located by diffing what the merge <em>expected</em> to produce against what is actually there, so it
     * needs no cursor position and no edit event - which matters, because paste and undo must count too.</p>
     *
     * @return how many regions stopped corresponding to a choice
     */
    public int attributeHandEdit(List<String> actual) {
        List<String> expected = mergedLines();
        if (expected.equals(actual)) return 0;

        List<int[]> ranges = resultRanges();
        List<DiffRange> changes = DiffIterable.of(expected, actual).changed();

        int touched = 0;
        for (int i = 0; i < regions.size(); i++) {
            int[] range = ranges.get(i);
            int shift = 0;
            boolean overlaps = false;
            for (DiffRange change : changes) {
                if (change.start1() < range[1] && change.end1() > range[0]
                        || (change.isEmpty1() && change.start1() >= range[0] && change.start1() <= range[1])) {
                    overlaps = true;
                }
                if (change.end1() <= range[0]) shift += change.length2() - change.length1();
            }
            if (!overlaps) continue;

            // The region's span in the ACTUAL text, shifted by every edit that landed before it.
            int from = Math.max(0, Math.min(range[0] + shift, actual.size()));
            int to = Math.max(from, Math.min(range[1] + shift + lengthDelta(changes, range), actual.size()));
            regions.get(i).markUnrecognized(new ArrayList<>(actual.subList(from, to)));
            touched++;
        }
        return touched;
    }

    private static int lengthDelta(List<DiffRange> changes, int[] range) {
        int delta = 0;
        for (DiffRange change : changes) {
            if (change.start1() >= range[0] && change.end1() <= range[1]) {
                delta += change.length2() - change.length1();
            }
        }
        return delta;
    }

    /** The merged text, newline-terminated per line. */
    public String merged() {
        StringBuilder text = new StringBuilder();
        for (String line : mergedLines()) text.append(line).append('\n');
        return text.toString();
    }
}
