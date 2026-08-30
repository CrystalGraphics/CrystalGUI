package com.crystalgui.style;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.property.FontRelative;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.style.transition.TransitionEngine;
import com.crystalgui.core.async.FrameProfile;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * One instance per {@link UIWindow}. Owns the registered stylesheets, the dirty-rematch queue
 * (selector re-matching on id/class/pseudo-class change), and — via {@link TransitionEngine} — the
 * active state-transition driver. Driven once per frame from {@link UIWindow#paintFrame()}, before
 * layout.
 */
public final class StyleEngine {
    /** Every styleable in the tree, for the whole-tree invalidation a sheet change is. */
    private final Supplier<? extends Collection<? extends Styleable>> elements;

    /** A sheet and the subtree it is installed for; a null root is the whole tree. */
    private record Installed(StyleSheet sheet, @Nullable Styleable root) {
    }

    @Getter
    private final TransitionEngine transitionEngine = new TransitionEngine();

    private final List<Installed> sheets = new ArrayList<>();
    private final Set<Styleable> dirtyMatch = new HashSet<>();

    /** Exactly the STYLESHEET/IMPORTANT-origin slots this engine last applied to each element — kept
     * so a re-match can remove precisely what it added, without guessing by origin (an IMPORTANT-
     * origin slot might equally have come from user Java code via StyleGroup.setImportant()).
     *
     * <p><b>WEAK, and it SURVIVES A DETACH.</b> It used to be dropped in {@link #onElementDetached},
     * which is correct only for an element that never comes back — and this record is the only thing
     * that knows which candidates were the sheet's. An element detached and re-attached somewhere else
     * therefore re-matched with {@code previouslyApplied == null} and simply <b>added</b> its new slots
     * on top of the old ones, which kept their specificity and kept winning, permanently, with nothing
     * in either rule looking wrong. Reparenting does not invalidate a match on its own, so anything
     * moved out from under a descendant selector depends on this: a tool window's header adopted into a
     * window's caption and then docked back came home still carrying the caption's {@code padding-left:
     * 0}, and was broken only <em>after a round trip</em>. Hide-as-detach makes that shape routine.</p>
     *
     * <p>Withdrawing the slots at detach time instead is the obvious repair and it does not work: the
     * removal re-resolves every touched property, a layout property's listener calls into
     * {@code TaffyBridge}, and by then {@code unregisterElement} has already freed the Taffy node —
     * {@code NullPointerException} out of {@code markDirtyRecursive}. The record has to outlive the
     * detach and be spent on the next match.</p>
     *
     * <p>Weak keys are what keep that from being a leak: an element that really is gone takes its entry
     * with it, and one that comes back still has it.</p> */
    private final Map<Styleable, List<StyleSlot<?>>> appliedByElement = new WeakHashMap<>();

    /**
     * Resolved {@code ::highlight(name)} styles, per element, per highlight name.
     *
     * <p>Kept apart from the element's own cascade because a highlight pseudo-element is not an element:
     * it has no box, no children and no transitions, and folding its declarations into
     * {@link ElementStyle} would recolour the whole paragraph. Rebuilt wholesale in {@link #rematch},
     * so it stays in step with the ordinary cascade by construction rather than by a second
     * invalidation path.</p>
     */
    private final Map<Styleable, Map<String, HighlightStyle>> highlightsByElement = new HashMap<>();

    /**
     * Every live engine, weakly held — what {@link #reloadStylesheets()} restyles.
     *
     * <p>Weak because a window that goes out of scope must not be kept alive by a debug facility, and
     * there is no {@code UIWindow.close()} to unregister from. A discarded window simply falls out of the
     * set when it is collected; until then it is restyled harmlessly.</p>
     */
    private static final Set<StyleEngine> LIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public StyleEngine(Supplier<? extends Collection<? extends Styleable>> elements) {
        this.elements = elements;
        LIVE.add(this);
    }

    /**
     * Re-reads every stylesheet from disk and restyles every live window — <b>CSS hot reload</b>.
     *
     * <p>The one call a host needs. {@code StyleSheetRegistry.reloadAll()} refills the sheets in place so
     * existing registrations stay valid, and every engine then re-matches; see
     * {@link #invalidateAllMatches()} for why re-matching alone is sufficient to drop deleted rules.</p>
     *
     * <p><b>Global rather than per-window on purpose.</b> Stylesheets are a global cache, so a per-window
     * call would re-read every file once per window and still leave the other windows stale unless the
     * caller remembered all of them. The harness binds this to a key; {@code CgAssetReloader}'s F3+T path
     * is the same shape.</p>
     *
     * @return how many stylesheets were re-read successfully
     */
    public static int reloadStylesheets() {
        int reloaded = StyleSheetRegistry.reloadAll();
        restyleAllWindows();
        return reloaded;
    }

    /**
     * Re-matches every element of every live window against the sheets' <b>current</b> contents —
     * the second half of any in-place sheet mutation. {@code StyleSheetRegistry.reloadAll()} and
     * {@code bindVariables()} both change what the sheets say without changing any engine's sheet
     * list, so no per-window hook fires; whoever mutated the sheets calls this next.
     */
    public static int restyleAllWindows() {
        synchronized (LIVE) {
            for (StyleEngine engine : LIVE) engine.invalidateAllMatches();
            // COUNTED, because zero is a real answer and looks exactly like success from the caller.
            // A sheet mutation that restyles nothing has done everything right and changed no pixel.
            return LIVE.size();
        }
    }

    public void addStylesheet(StyleSheet sheet) {
        addStylesheet(sheet, null);
    }

    /**
     * Installs for a SUBTREE — CSS {@code @scope}. Only elements at or under {@code root} can match
     * its rules, and among candidates of equal specificity a closer scope root wins (scoping
     * proximity, ranked between specificity and order of appearance). A shadow root as the scope is
     * how a composite's own sheet reaches its parts and nothing outside them; an unscoped sheet
     * reaches into no shadow tree at all, except through {@code ::part()}.
     */
    public void addStylesheet(StyleSheet sheet, @Nullable Styleable root) {
        sheets.add(new Installed(sheet, root));
        markAllDirty();
    }

    public void removeStylesheet(StyleSheet sheet) {
        if (sheets.removeIf(installed -> installed.sheet() == sheet)) {
            markAllDirty();
        }
    }

    /**
     * Marks every element in this window for re-matching — what a stylesheet <b>hot-reload</b> needs.
     *
     * <p>The same thing {@link #addStylesheet} does inline, named so a caller that changed a sheet's
     * contents rather than the sheet list can ask for it. {@code StyleSheetRegistry.reloadAll()} refills
     * sheets in place precisely so existing registrations stay valid, which means nothing about the sheet
     * <em>list</em> changes and no existing hook fires.</p>
     *
     * <p>Re-matching is enough on its own: {@code rematch} remembers the slots it last applied per element
     * and replaces that whole set atomically, so a declaration deleted from the file is dropped rather
     * than left behind as a winning candidate nothing overwrites.</p>
     */
    public void invalidateAllMatches() {
        markAllDirty();
    }

    /**
     * Marks the WHOLE WINDOW — the three bulk entries, so a profile can tell them from a targeted one.
     *
     * <p>Routed through one method purely so it can be blamed. A frame reported 243 elements re-matched
     * with <b>no</b> {@code markDirty} caller at all, which is only possible from here: an invalidation
     * that names one element and one that names every element are indistinguishable downstream, and the
     * second is the one that costs a frame. @see FrameProfile</p>
     */
    private void markAllDirty() {
        if (FrameProfile.ENABLED) {
            FrameProfile.blame("markAllDirty", "com.crystalgui.style");
            FrameProfile.count("whole-window-invalidations", 1);
        }
        dirtyMatch.addAll(elements.get());
    }

    /**
     * Whether a STATE change on an ancestor could alter {@code descendant}'s match. @see StyleSheet
     *
     * <p>The question {@code Styleable.invalidateStyleMatch()} asks before re-matching a subtree.
     * Answering it honestly is what turns a hover into a walk instead of hundreds of re-matches — in a
     * running client one hover change re-matched <b>291</b> elements and a focus change <b>402 to 713</b>,
     * every one of them at 20-25µs.</p>
     *
     * <p>Yes for anything at all when a sheet carries a rule whose subject cannot be keyed, which is the
     * conservative answer and the pre-existing behaviour.</p>
     */
    /**
     * What a state change on {@code ancestor} can reach — the keys, or null for <b>nothing at all</b>.
     *
     * <h3>Resolved once per invalidation, not once per descendant</h3>
     *
     * <p>{@link #stateReaches} asks the flat question — "could any state rule reach an element carrying
     * this key" — and the answer is the same for every ancestor, so a hover anywhere marked every element
     * whose tag or class appeared in any such rule. {@code text} is one of those, so every label in the
     * window re-matched on every mouse move.</p>
     *
     * <p>This asks the narrow one, and it is answered <em>before</em> the walk starts: a null return means
     * the subtree is not walked at all, which is the second half of the win — the old walk visited every
     * descendant unconditionally and only narrowed what it MARKED.</p>
     *
     * @return the reachable descendant keys, or null when a state change here can reach nothing
     */
    @Nullable
    public Set<String> stateDescendantKeysFrom(Styleable ancestor) {
        Set<String> reachable = null;
        for (int i = 0; i < sheets.size(); i++) {
            StyleSheet sheet = sheets.get(i).sheet();
            // An unkeyable subject (`foo:hover *`) means anything could match, so nothing can be narrowed.
            if (sheet.hasUnboundedStateDescendants()) return EVERYTHING;
            reachable = addAll(reachable, sheet.stateDescendantsFromAnyAncestor());
            reachable = addAll(reachable, sheet.stateDescendantsFrom(ancestor.tagName()));
            for (String cls : ancestor.getClasses()) {
                reachable = addAll(reachable, sheet.stateDescendantsFrom(cls));
            }
            if (!ancestor.getId().isEmpty()) {
                reachable = addAll(reachable, sheet.stateDescendantsFrom(ancestor.getId()));
            }
        }
        return reachable;
    }

    /** Allocates only when there is something to add — the common answer is "nothing". */
    @Nullable
    private static Set<String> addAll(@Nullable Set<String> into, Set<String> more) {
        if (more.isEmpty()) return into;
        if (into == null) into = new HashSet<>(more.size() * 2);
        into.addAll(more);
        return into;
    }

    /** The sentinel for "narrowing is off" — an unkeyable subject somewhere in a sheet. */
    public static final Set<String> EVERYTHING = Collections.unmodifiableSet(new HashSet<>());

    /** Whether {@code descendant} carries any of {@code reachable}. @see #stateDescendantKeysFrom */
    public static boolean carriesAny(Styleable descendant, Set<String> reachable) {
        if (reachable == EVERYTHING) return true;
        if (reachable.contains(descendant.tagName())) return true;
        for (String cls : descendant.getClasses()) {
            if (reachable.contains(cls)) return true;
        }
        return !descendant.getId().isEmpty() && reachable.contains(descendant.getId());
    }

    public boolean stateReaches(Styleable descendant) {
        for (int i = 0; i < sheets.size(); i++) {
            StyleSheet sheet = sheets.get(i).sheet();
            if (sheet.hasUnboundedStateDescendants()) return true;
            Set<String> keys = sheet.stateDescendantKeys();
            if (keys.isEmpty()) continue;
            if (keys.contains(descendant.tagName())) return true;
            for (String cls : descendant.getClasses()) {
                if (keys.contains(cls)) return true;
            }
            if (!descendant.getId().isEmpty() && keys.contains(descendant.getId())) return true;
        }
        return false;
    }

    /** Called from {@link Styleable#invalidateStyleMatch()} — marks an element for re-matching. */
    public void markDirty(Styleable element) {
        // BLAMED WHILE PROFILING. A count says three hundred elements were re-matched; only the caller
        // says why, and "why" is the whole question when nothing on screen is moving. @see FrameProfile
        if (FrameProfile.ENABLED && dirtyMatch.add(element)) {
            FrameProfile.blame("markDirty", "com.crystalgui.style", "com.crystalgui.ui.UIElement");
            return;
        }
        dirtyMatch.add(element);
    }

    /**
     * Called when an element leaves the tree — stops tracking it for matching and animation.
     *
     * <p>What it does <b>not</b> do is forget which slots it applied — see {@link #appliedByElement}.</p>
     */
    public void onElementDetached(Styleable element) {
        dirtyMatch.remove(element);
        // appliedByElement is deliberately NOT cleared here — see its own note. It is what the next
        // match spends to withdraw the rules that stopped applying, and an element that never returns
        // takes its entry with it through the weak key.
        highlightsByElement.remove(element);
        transitionEngine.onElementDetached(element);
    }

    /** Called from {@link UIWindow#paintFrame()}, before layout is recomputed — and again afterwards for
     * as long as {@link #hasPendingMatches()} reports work that layout itself created. */
    public void calculateStyle(float deltaSeconds) {
        // THE TWO HALVES REPORTED APART. They cost differently and for unrelated reasons -- the drain is
        // proportional to how many elements were invalidated, the tick to how many transitions are in
        // flight -- so one `style` bucket cannot say which of them a slow frame is.
        long timed = FrameProfile.begin();
        drainDirtyMatch();
        FrameProfile.end(timed, "style:drainDirtyMatch");
        timed = FrameProfile.begin();
        transitionEngine.tick(deltaSeconds);
        FrameProfile.end(timed, "style:transitions");
    }

    /**
     * Whether any element is waiting to be re-matched.
     *
     * <p>Exists so {@code UIWindow} can tell whether <em>layout</em> dirtied the cascade — a ticker or an
     * {@code onLayoutChanged} hook that sets a class runs after this frame's {@link #calculateStyle}, so
     * without a second pass the class is set and its computed style is a frame behind it. Reported rather
     * than acted on here because only the window knows that re-cascading means re-laying-out too.</p>
     */
    public boolean hasPendingMatches() {
        return !dirtyMatch.isEmpty();
    }

    /** Exactly what the last {@link #resetRematchCountForTesting} onwards re-matched. Tests only. */
    private final List<Styleable> rematchedForTesting = new ArrayList<>();

    /**
     * Whether to record at all — <b>off until a test asks</b>.
     *
     * <p>An always-on list of every element ever re-matched is an unbounded leak in a running
     * application, and one that would grow fastest in exactly the situation this class was just
     * optimised for. Recording starts when a test resets it and never starts otherwise.</p>
     */
    private boolean recordRematches;

    /** @see #rematchedForTesting */
    public void resetRematchCountForTesting() {
        recordRematches = true;
        rematchedForTesting.clear();
    }

    /** @see #rematchedForTesting */
    public int rematchCountForTesting() {
        return rematchedForTesting.size();
    }

    /**
     * Which elements were re-matched — the only way to assert that a narrowing SAVED anything.
     *
     * <p>Re-matching an element whose answer has not changed is by construction invisible from the
     * outside, so a test for "and it no longer does that" has nothing else to look at.</p>
     */
    public List<Styleable> rematchedForTesting() {
        return rematchedForTesting;
    }

    /** How many times one pass may re-drain before it gives up on settling. */
    private static final int MAX_SETTLE_ROUNDS = 8;

    private void drainDirtyMatch() {
        if (dirtyMatch.isEmpty()) return;
        // PARENTS FIRST, AND UNTIL SETTLED. The dirty set is a hash set, so a child could be matched
        // before its parent -- and an `em` on the child then resolved against a font size the parent
        // had not computed yet, which is the wrong number until the next frame. Depth order is what a
        // top-down style recalc is. And a match that dirties descendants (a font-size change; the
        // reentrant invalidateStyleMatch() calls style-change listeners make) is drained again in the
        // same pass, bounded, so one calculateStyle() answers for the whole tree. Snapshot-and-clear
        // per round, so those reentrant calls land in the cleared set rather than throwing a
        // ConcurrentModificationException.
        long timed = FrameProfile.begin();
        int total = 0;
        for (int round = 0; round < MAX_SETTLE_ROUNDS && !dirtyMatch.isEmpty(); round++) {
            var batch = new ArrayList<>(dirtyMatch);
            dirtyMatch.clear();
            batch.sort((a, b) -> Integer.compare(depthOf(a), depthOf(b)));
            // NAMES WHAT IS CHURNING, not merely how much. "Style is slow" is not actionable; "2,143
            // elements re-matched, 2,000 of them .__error-stripe__" is a fix. Off unless asked for.
            if (FrameProfile.ENABLED) profileBatch(batch);
            if (recordRematches) rematchedForTesting.addAll(batch);
            for (var element : batch) {
                rematch(element);
            }
            total += batch.size();
        }
        if (!dirtyMatch.isEmpty()) {
            CrystalGuiCore.LOGGER.warn("Style matching did not settle in {} rounds; {} element(s) carry to the next pass",
                    MAX_SETTLE_ROUNDS, dirtyMatch.size());
        }
        var batch = new ArrayList<Styleable>();   // for the step label below
        for (int i = 0; i < total; i++) batch.add(null);
        // THE PER-ELEMENT COST, stated rather than assumed. Everything about narrowing invalidation rests
        // on "a rematch costs ~20-25us", which was a comment in this file and never a measurement -- and
        // the trade between marking fewer elements and walking a smaller tree is decided by that number.
        FrameProfile.step(timed, "style:rematch x" + batch.size());
    }

    /** Counts the batch by the most specific class each element carries, for the frame report. */
    private static int depthOf(Styleable element) {
        int depth = 0;
        for (Styleable at = element.getParent(); at != null; at = at.getParent()) depth++;
        return depth;
    }

    private static void profileBatch(List<Styleable> batch) {
        FrameProfile.count("rematched", batch.size());
        Map<String, Integer> byName = new HashMap<>();
        for (Styleable element : batch) {
            String name = element.getClasses().isEmpty()
                    ? element.tagName() : element.getClasses().iterator().next();
            byName.merge(name, 1, Integer::sum);
        }
        byName.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .forEach(entry -> FrameProfile.count("~" + entry.getKey(), entry.getValue()));
    }

    /** Declarations within one rule share the rule's own {@code sourceOrder} — this multiplier
     * folds each declaration's position within the rule into that same int, so a later declaration
     * for the same target property (e.g. an explicit {@code margin-left:} after a {@code margin:}
     * shorthand expansion in the same rule) still correctly outranks the earlier one via ordinary
     * {@link StyleSlot#compare}, instead of tying and falling back to insertion order. No realistic
     * rule has anywhere near this many declarations. */
    private static final int DECLARATION_ORDER_MULTIPLIER = 100_000;

    /** Stride between registered stylesheets in the packed {@code sourceOrder}. Sized to clear any
     * realistic sheet (a sheet would need ~10 million rules to reach it) and comfortably within a
     * {@code long} — which is exactly why {@link StyleSlot#sourceOrder()} is a {@code long}: an
     * {@code int} would cap this at roughly twenty sheets before wrapping. */
    private static final long SHEET_ORDER_STRIDE = 1_000_000_000_000L;

    /**
     * The resolved style of {@code ::highlight(name)} on {@code element}, or
     * {@link HighlightStyle#EMPTY} when no rule matched.
     *
     * <p>Never null, because "no theme styles this highlight name" is an ordinary, expected state — a
     * highlighter emits names without knowing which of them a given theme cares about.</p>
     */
    public HighlightStyle highlightStyle(Styleable element, String name) {
        var forElement = highlightsByElement.get(element);
        if (forElement == null) return HighlightStyle.EMPTY;
        return forElement.getOrDefault(name, HighlightStyle.EMPTY);
    }

    /**
     * Re-runs the cascade for one element.
     *
     * <h3>A second pass, but only for an element that actually uses {@code em}</h3>
     *
     * <p>An {@code em} needs the element's <b>computed</b> {@code font-size}, and that is not known until
     * this pass has applied the rules that might set it — nor is it enough to pick the winning
     * {@code font-size} declaration out of the sheets, because a widget writing its own size at INLINE or
     * IMPORTANT beats every sheet and does not appear here at all. So the first pass resolves against
     * whatever the element's font size was, and if applying the pass changed it, the whole thing runs
     * again against the settled value.</p>
     *
     * <p>Bounded at two passes rather than looped to a fixpoint. A third could only differ if a
     * {@code font-size} were itself authored in {@code em} <em>and</em> the change moved the winner — and
     * a self-referential font size is the one case where a fixpoint loop genuinely might not terminate.
     * {@code replaceOrPutCandidate} no-ops on unchanged values, so the second pass costs a walk and
     * nothing else.</p>
     */
    private void rematch(Styleable element) {
        float fontSize = element.getStyle().getGeneralGroup().fontSize();
        boolean fontRelative = rematchAgainst(element, fontSize);
        if (fontRelative) {
            float settled = element.getStyle().getGeneralGroup().fontSize();
            if (settled != fontSize) rematchAgainst(element, settled);
        }
        // WHETHER A LATER FONT-SIZE CHANGE HAS TO COME BACK HERE. Nothing else re-runs this pass, so an
        // element whose size is written after its rules matched -- a widget imposing its own at IMPORTANT,
        // which is what TextEditor does to its gutter on every zoom -- would keep the em pixels it was
        // given when the sheet last matched. @see Styleable#invalidateFontRelativeStyles
        element.setHasFontRelativeStyles(fontRelative);
    }

    /**
     * One cascade pass, with {@code em} resolved against {@code fontSize}.
     *
     * @return whether any matched declaration was font-relative, i.e. whether the answer depends on
     *         {@code fontSize} at all
     */
    private boolean rematchAgainst(Styleable element, float fontSize) {
        var previouslyApplied = appliedByElement.get(element);
        boolean sawFontRelative = false;

        List<StyleSlot<?>> newSlots = new ArrayList<>();
        // name -> property -> winning slot. Built alongside the element's own cascade rather than in a
        // second pass, so the two cannot disagree about which rules matched.
        Map<String, Map<StyleProperty<?>, StyleSlot<?>>> highlightSlots = new HashMap<>();

        // SPIKE S2. Null for an ordinary element, which is every element in the engine today, so the
        // whole shadow path below costs one map lookup per rematch until something attaches a root.
        Styleable shadowHost = element.shadowHost();

        for (int sheetIndex = 0; sheetIndex < sheets.size(); sheetIndex++) {
            var installed = sheets.get(sheetIndex);
            var sheet = installed.sheet();
            // WHICH RULES CAN REACH THIS ELEMENT. An unscoped sheet reaches everything outside a shadow
            // tree, and a shadow tree's parts only through ::part(). A scoped sheet reaches what is at
            // or under its root -- the shadow root itself, for a composite's own sheet -- and its
            // candidates rank by how close that root is.
            int proximity;
            int hostProximity;
            boolean ordinaryRulesReach;
            if (installed.root() == null) {
                proximity = StyleSlot.UNSCOPED;
                hostProximity = StyleSlot.UNSCOPED;
                ordinaryRulesReach = shadowHost == null;
            } else {
                proximity = proximityOf(element, installed.root());
                hostProximity = shadowHost == null ? -1 : proximityOf(shadowHost, installed.root());
                ordinaryRulesReach = proximity >= 0;
            }
            boolean partRulesReach = shadowHost != null && hostProximity >= 0;
            if (!ordinaryRulesReach && !partRulesReach) continue;
            List<StyleRule> candidates = ordinaryRulesReach ? sheet.candidatesFor(element) : List.of();
            if (partRulesReach) {
                // A ::part rule is indexed under the HOST's type, id and classes -- `button::part(label)`
                // lives in the `button` bucket -- so it is unreachable from the element it applies to.
                // This is the cost S2 set out to measure: styling a shadow descendant means asking the
                // index twice, once for the element and once for its host.
                candidates = new ArrayList<>(candidates);
                candidates.addAll(sheet.candidatesFor(shadowHost));
            }
            for (var rule : candidates) {
                var pseudo = rule.selector().pseudoElement();
                if (pseudo != null) {
                    if (rule.selector().selectsShadowPart()) {
                        // ::part selects a REAL element, unlike ::highlight, so it contributes to this
                        // element's own cascade rather than to a side table. It applies when this element
                        // is exposed under that part name AND the compound describes its host.
                        if (partRulesReach
                                && pseudo.argument().equals(element.partName())
                                && rule.selector().matchesOriginating(shadowHost)) {
                            int partSpecificity = rule.selector().specificity();
                            var partDecls = rule.declarations();
                            for (int i = 0; i < partDecls.size(); i++) {
                                var decl = partDecls.get(i);
                                var origin = decl.important() ? StyleOrigin.IMPORTANT : sheet.getOrigin();
                                long order = (long) sheetIndex * SHEET_ORDER_STRIDE
                                        + (long) rule.sourceOrder() * DECLARATION_ORDER_MULTIPLIER + i;
                                if (isFontRelative(decl)) sawFontRelative = true;
                                var slot = toSlot(decl, origin, partSpecificity, hostProximity, order, fontSize);
                                if (slot != null) newSlots.add(slot);
                            }
                        }
                        continue;
                    }
                    if (ordinaryRulesReach && rule.selector().matchesOriginating(element)) {
                        collectHighlight(highlightSlots, pseudo.argument(), rule, sheet, sheetIndex, proximity);
                    }
                    continue;
                }
                // ENCAPSULATION. An ordinary rule may not reach into a shadow tree -- that is the whole
                // proposition, and the reason ::part has to exist at all. Note it is checked HERE and not
                // by pruning the index: a rule that matches this element by class is a legitimate match
                // for an element in the LIGHT tree with the same class, so the scope is a property of the
                // pairing rather than of the rule.
                if (!ordinaryRulesReach) continue;
                if (!rule.selector().matches(element)) continue;
                int specificity = rule.selector().specificity();
                var decls = rule.declarations();
                for (int i = 0; i < decls.size(); i++) {
                    var decl = decls.get(i);
                    // The sheet's own origin, so a USER_AGENT sheet (StyleSheet.DEFAULT) can never
                    // out-rank an author one. `!important` still escalates to IMPORTANT regardless —
                    // which is why default.css must not use it: doing so would jump it above every
                    // author sheet and defeat the whole point.
                    var origin = decl.important() ? StyleOrigin.IMPORTANT : sheet.getOrigin();
                    // Registration index packed ABOVE the rule index, so a later-registered sheet
                    // outranks an earlier one at equal specificity — CSS's "later sheet wins".
                    // StyleSheet.parse restarts sourceOrder at 0 for every sheet, so without this a
                    // big sheet's rule #40 beat a later sheet's rule #2 purely by rule count.
                    long sourceOrder = (long) sheetIndex * SHEET_ORDER_STRIDE
                            + (long) rule.sourceOrder() * DECLARATION_ORDER_MULTIPLIER + i;
                    if (isFontRelative(decl)) sawFontRelative = true;
                    var slot = toSlot(decl, origin, specificity, proximity, sourceOrder, fontSize);
                    if (slot != null) newSlots.add(slot);
                }
            }
        }

        if (highlightSlots.isEmpty()) {
            highlightsByElement.remove(element);
        } else {
            Map<String, HighlightStyle> resolved = new HashMap<>();
            highlightSlots.forEach((name, slots) -> {
                Map<StyleProperty<?>, Object> values = new HashMap<>();
                slots.forEach((property, slot) -> values.put(property, slot.value()));
                resolved.put(name, new HighlightStyle(values));
            });
            highlightsByElement.put(element, resolved);
        }

        if (newSlots.isEmpty()) {
            appliedByElement.remove(element);
            if (previouslyApplied != null && !previouslyApplied.isEmpty()) {
                element.getStyle().removeCandidates(previouslyApplied::contains);
            }
        } else {
            appliedByElement.put(element, newSlots);
            if (previouslyApplied != null && !previouslyApplied.isEmpty()) {
                // Atomic remove+add — see ElementStyle.replaceCandidates()'s doc for why this must
                // not be two separate calls (would defeat transitions via a spurious null passthrough).
                element.getStyle().replaceCandidates(previouslyApplied::contains, newSlots);
            } else {
                element.getStyle().putCandidates(newSlots);
            }
        }
        return sawFontRelative;
    }

    /**
     * Folds one matched {@code ::highlight(name)} rule into the per-name winner map.
     *
     * <p>Resolves by ordinary {@link StyleSlot#compare} rather than by a bespoke rule, so a highlight
     * obeys origin, specificity and source order exactly as any other declaration does — a theme can
     * override the user-agent sheet's {@code ::highlight()} colours the same way it overrides anything
     * else.</p>
     *
     * <p><b>Declarations outside the allowed set are dropped with a warning.</b> CSS restricts highlight
     * pseudo-elements to properties that cannot affect layout, and the restriction is the feature: a
     * {@code font-size} here would reflow the text as you typed in a search box. Silently ignoring it
     * would leave an author with a rule that looks right and does nothing.</p>
     */
    private void collectHighlight(Map<String, Map<StyleProperty<?>, StyleSlot<?>>> out, String name,
                                  StyleRule rule, StyleSheet sheet, int sheetIndex, int proximity) {
        int specificity = rule.selector().specificity();
        var decls = rule.declarations();
        for (int i = 0; i < decls.size(); i++) {
            var decl = decls.get(i);
            if (!HighlightStyle.ALLOWED.contains(decl.property())) {
                // Two different failures, deliberately worded differently: one is the author asking for
                // something CSS itself forbids, the other is the author asking for something CSS allows
                // and we cannot draw yet. Collapsing them would tell the first author to go and check a
                // spec that agrees with them.
                if (HighlightStyle.NOT_YET_PAINTABLE.contains(decl.property())) {
                    CrystalGuiCore.LOGGER.warn(
                            "'{}' is valid on ::highlight({}) per CSS but is NOT IMPLEMENTED here yet, so it"
                                    + " was ignored. It needs per-range geometry from the text layout, which"
                                    + " CgStyleSpan cannot express. Recolour or underline the range instead.",
                            decl.property().name, name);
                } else {
                    CrystalGuiCore.LOGGER.warn(
                            "'{}' is not allowed on ::highlight({}) and was ignored. Highlight"
                                    + " pseudo-elements accept only properties that cannot affect layout —"
                                    + " CSS Pseudo-Elements 4 — because a highlight must never reflow the text"
                                    + " it highlights.",
                            decl.property().name, name);
                }
                continue;
            }
            var origin = decl.important() ? StyleOrigin.IMPORTANT : sheet.getOrigin();
            long sourceOrder = (long) sheetIndex * SHEET_ORDER_STRIDE
                    + (long) rule.sourceOrder() * DECLARATION_ORDER_MULTIPLIER + i;
            var slot = toSlot(decl, origin, specificity, proximity, sourceOrder, Float.NaN);
            if (slot == null) continue;
            var forName = out.computeIfAbsent(name, ignored -> new HashMap<>());
            var existing = forName.get(decl.property());
            if (existing == null || existing.compareTo(slot) < 0) {
                forName.put(decl.property(), slot);
            }
        }
    }

    /**
     * Builds a {@link StyleSlot} from a matched declaration, or {@code null} if the declaration's
     * value failed to parse — a malformed value (e.g. {@code color: notacolor;}) must never become a
     * cascade winner with a bogus null value, silently overriding a real lower-priority one.
     */
    @Nullable
    static <T> StyleSlot<T> toSlot(StyleRule.Declaration decl, StyleOrigin origin, int specificity, long sourceOrder) {
        return toSlot(decl, origin, specificity, StyleSlot.UNSCOPED, sourceOrder, Float.NaN);
    }

    /**
     * @param fontSize the element's computed {@code font-size}, for resolving {@code em}. {@code NaN}
     *                 means "not known", which leaves a font-relative value at its reference size — see
     *                 {@link FontRelative}.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    static <T> StyleSlot<T> toSlot(StyleRule.Declaration decl, StyleOrigin origin, int specificity,
                                   int proximity, long sourceOrder, float fontSize) {
        var property = (StyleProperty<T>) decl.property();
        T value;
        // THE ONE PLACE AN `em` BECOMES A NUMBER. A StyleValue is parsed once and shared by every element
        // its rule matches, so it cannot hold the answer -- this is the first point where a declaration
        // and an element are both in hand. @see FontRelative
        if (decl.value() instanceof FontRelative<?> relative && relative.isFontRelative()
                && Float.isFinite(fontSize)) {
            value = (T) relative.resolveAgainst(fontSize);
        } else {
            value = (T) decl.value().compute();
        }
        if (value == null) {
            CrystalGuiCore.LOGGER.warn("Stylesheet declaration '{}: {}' failed to parse — skipping",
                    property.name, decl.value().rawValue);
            return null;
        }
        return StyleSlot.of(property, origin, specificity, proximity, sourceOrder, value);
    }

    /** Whether a matched declaration's value is an {@code em} and so needs the element's font size. */
    private static boolean isFontRelative(StyleRule.Declaration decl) {
        return decl.value() instanceof FontRelative<?> relative && relative.isFontRelative();
    }

    public List<StyleSheet> getSheets() {
        List<StyleSheet> out = new ArrayList<>(sheets.size());
        for (Installed installed : sheets) out.add(installed.sheet());
        return out;
    }

    /** Hops from {@code element} up to {@code root} inclusive, or -1 when {@code root} is not above it. */
    private static int proximityOf(Styleable element, Styleable root) {
        int hops = 0;
        for (Styleable at = element; at != null; at = at.getParent(), hops++) {
            if (at == root) return hops;
        }
        return -1;
    }
}
