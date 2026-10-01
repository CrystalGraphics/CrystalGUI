package com.crystalgui.style.sheet;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.selector.Selector;
import com.crystalgui.style.selector.SelectorType;
import com.crystalgui.style.Styleable;
import javax.annotation.Nullable;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A parsed LSS-subset stylesheet: an ordered list of {@link StyleRule}s, bucket-indexed by the
 * rightmost compound selector's id/class/type for fast per-element candidate lookup.
 *
 * <p>Supported syntax: {@code selector, selector { name: value; name: value !important; }} — see
 * the stylesheet+transitions plan for the documented CSS subset (id/class/type/pseudo-class
 * selectors, descendant/child combinators, comma-separated selector lists, {@code !important}; NOT
 * {@code :not()}, attribute selectors, sibling combinators, pseudo-elements, at-rules).
 *
 * <p>{@code sourceOrder} is local to one sheet — two different sheets that both match the same
 * element+property at identical specificity fall back to sheet registration order in
 * {@code StyleEngine}, not a single global counter. Acceptable v1 simplification: real-world
 * stylesheets rarely need cross-sheet tie-breaking at the same specificity.
 *
 * <h3>Client-side only</h3>
 * <p><b>This class cannot be loaded without CrystalGraphics</b>, and that is deliberate rather than
 * an oversight. {@link #DEFAULT} is a {@code static final}, so merely touching the class reads
 * {@code default.css} through {@code CgIO} — which means even {@link #parse(String)}, an API that
 * reads no file, throws {@code NoClassDefFoundError} on a dedicated server.</p>
 *
 * <p>Nothing server-side needs it: a networked UI ships stylesheets <em>by reference</em> and the
 * client — which always has CrystalGraphics — resolves and parses them. Code that only needs to turn
 * declaration text into values, such as an element's inline {@code style}, uses
 * {@link DeclarationParser} instead, which is free of both this class and CrystalGraphics.</p>
 */
public final class StyleSheet {

    /**
     * The engine's user-agent stylesheet: the {@code assets/crystalgui/ui/styles/ua/*.css} parts,
     * concatenated in {@link StyleSheetRegistry#DEFAULT_SHEET_PARTS} order. (One 6,000-line
     * {@code default.css} until plan/style-overhaul.md step 8 split it at its own section boundaries —
     * a pure move; the concatenation is the old file.)
     *
     * <p>Gives every widget functional (deliberately unthemed) geometry, plus a few generic layout
     * helpers. <b>Not applied automatically</b> — hand it to the engine like any other sheet:</p>
     * <pre>{@code window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);}</pre>
     *
     * <p>It carries {@link StyleOrigin#USER_AGENT}, so any author sheet added alongside it wins at
     * any specificity. Add it before or after a theme — the ordering genuinely doesn't matter.</p>
     */
    public static final StyleSheet DEFAULT = loadUserAgentSheet();

    private final List<StyleRule> rules;
    /** Cascade origin every declaration in this sheet is applied at — see {@link StyleEngine}. 
     * -- GETTER --
     * Cascade origin every non-
     *  declaration in this sheet is applied at. 
     */
    @Getter
    private final StyleOrigin origin;
    private final Map<String, List<StyleRule>> byId = new HashMap<>();
    private final Map<String, List<StyleRule>> byClass = new HashMap<>();
    private final Map<String, List<StyleRule>> byType = new HashMap<>();
    private final List<StyleRule> universal = new ArrayList<>();

    /**
     * Every class, id or tag that some rule reaches <b>through an ancestor's state</b>.
     *
     * <p>What a descendant must carry for an ancestor's {@code :hover}, {@code :checked} or
     * {@code :focus} change to be able to alter its match. @see #indexStateDescendants</p>
     */
    private final Set<String> stateDescendantKeys = new HashSet<>();

    /** Whether some such rule's subject cannot be keyed at all, so every descendant must be assumed. */
    private boolean stateDescendantsUnbounded;

    /**
     * Descendant keys reachable through an ancestor carrying {@code ancestorKey}. @see #indexStateDescendants
     */
    private final Map<String, Set<String>> stateDescendantsByAncestor = new HashMap<>();

    /**
     * Descendant keys reachable through a stateful ancestor that could be <b>anything</b> — the
     * {@code :hover .__icon__} shape, where the ancestor compound carries no tag, class or id.
     */
    private final Set<String> stateDescendantsFromAnyAncestor = new HashSet<>();

    /** @see #indexStateDescendants */
    public Set<String> stateDescendantKeys() {
        return stateDescendantKeys;
    }

    /** @see #indexStateDescendants */
    public Set<String> stateDescendantsFrom(String ancestorKey) {
        return stateDescendantsByAncestor.getOrDefault(ancestorKey, Collections.emptySet());
    }

    /** @see #stateDescendantsFromAnyAncestor */
    public Set<String> stateDescendantsFromAnyAncestor() {
        return stateDescendantsFromAnyAncestor;
    }

    /** @see #indexStateDescendants */
    public boolean hasUnboundedStateDescendants() {
        return stateDescendantsUnbounded;
    }

    /** Descendant keys a rule reaches through an ancestor carrying the class. @see #indexClassDescendants */
    private final Map<String, Set<String>> classDescendantsByClass = new HashMap<>();

    /** Classes some rule reaches THROUGH to a subject nothing can be keyed on, so every descendant is assumed. */
    private final Set<String> classDescendantsUnbounded = new HashSet<>();

    /**
     * What an element gaining or losing {@code cls} can change below it: the keys a descendant must carry, an
     * empty set for nothing, or null when any descendant could be affected.
     */
    @Nullable
    public Set<String> classDescendantsFrom(String cls) {
        if (classDescendantsUnbounded.contains(cls)) return null;
        return classDescendantsByClass.getOrDefault(cls, Collections.emptySet());
    }

    /**
     * The CSS text this sheet was parsed from — retained so the sheet can be <b>re-substituted
     * in place</b> against a different external variable table ({@link #rebind}), which is what a
     * theme swap is. {@code null} only for rule-copy instances ({@link #DEFAULT}), which the
     * registry refills by mirroring the cached source sheet instead.
     *
     * <p>Deliberately kept in memory rather than re-read through {@code CgIO} on demand: a theme
     * swap must not depend on the resource still being readable, and inline sheets
     * ({@code StyleSheet.parse(...)} in a scene or test) have no path to re-read from at all.</p>
     */
    private String rawSource;

    private StyleSheet(List<StyleRule> rules) {
        this(rules, StyleOrigin.STYLESHEET);
    }

    private StyleSheet(List<StyleRule> rules, StyleOrigin origin) {
        // A MUTABLE COPY the sheet owns. Callers hand in whatever they have -- parse() a fresh ArrayList,
        // loadUserAgentSheet() the immutable result of getRules() -- and replaceRules has to be able to
        // empty it. Storing the caller's list meant DEFAULT held a List.copyOf and every hot reload threw
        // UnsupportedOperationException on the one sheet the feature exists for.
        this.rules = new ArrayList<>(rules);
        this.origin = origin;
        for (var rule : this.rules) index(rule);
    }

    /**
     * Parses {@code source} against the registry's currently {@linkplain
     * StyleSheetRegistry#boundVariables() bound variable table} — so a sheet parsed while a theme
     * is active resolves the theme's tokens, regardless of load order. With no theme bound the
     * table is empty and only the sheet's own variables apply.
     */
    public static StyleSheet parse(String source) {
        return parse(source, StyleSheetRegistry.boundVariables());
    }

    /**
     * Parses {@code source}, resolving {@code var()} references against the sheet's own variable
     * definitions merged over {@code externalVariables} — <b>locals win</b>, so a sheet's own
     * domain variables ({@code graph.css}'s {@code --graph-*}) are sovereign and can never be
     * captured by a theme that happens to reuse a name. The merged table is resolved to a fixed
     * point first, so definitions may reference definitions ({@link DeclarationParser#resolveTable}).
     */
    public static StyleSheet parse(String source, Map<String, String> externalVariables) {
        String stripped = DeclarationParser.stripComments(source);
        Map<String, String> locals = DeclarationParser.collectVariables(stripped);
        Map<String, String> variables;
        if (externalVariables.isEmpty()) {
            variables = DeclarationParser.resolveTable(locals);
        } else {
            Map<String, String> merged = new LinkedHashMap<>(externalVariables);
            merged.putAll(locals);
            variables = DeclarationParser.resolveTable(merged);
        }

        List<StyleRule> rules = new ArrayList<>();
        int sourceOrder = 0;
        Matcher ruleMatcher = DeclarationParser.RULE_PATTERN.matcher(stripped);
        while (ruleMatcher.find()) {
            String selectorList = ruleMatcher.group(1).trim();
            if (selectorList.isEmpty()) continue;

            List<StyleRule.Declaration> declarations = DeclarationParser.parseBlock(ruleMatcher.group(2), variables);
            if (declarations.isEmpty()) continue;

            for (String selectorText : selectorList.split(",")) {
                String trimmed = selectorText.trim();
                if (trimmed.isEmpty()) continue;
                // A BAD SELECTOR INVALIDATES ITS RULE, NEVER THE SHEET. One :focus-within used to take
                // six unrelated panels down with it (audit §5 S3); CSS drops the rule and keeps going.
                try {
                    // A BAD SELECTOR INVALIDATES ITS RULE, NEVER THE SHEET. One :focus-within used to take
                // six unrelated panels down with it (audit §5 S3); CSS drops the rule and keeps going.
                try {
                    rules.add(new StyleRule(Selector.parse(trimmed), declarations, sourceOrder));
                } catch (IllegalArgumentException unparseable) {
                    CrystalGuiCore.LOGGER.warn("Dropping the rule for '{}': {}", trimmed, unparseable.getMessage());
                }
                } catch (IllegalArgumentException unparseable) {
                    CrystalGuiCore.LOGGER.warn("Dropping the rule for '{}': {}", trimmed, unparseable.getMessage());
                }
            }
            sourceOrder++;
        }
        StyleSheet sheet = new StyleSheet(rules);
        sheet.rawSource = source;
        return sheet;
    }

    /** Collects the {@code --name: value} definitions in {@code source} (comments stripped, every
     * rule, unresolved) — the public face of variable collection, for the theme layer. */
    public static Map<String, String> variablesOf(String source) {
        return DeclarationParser.collectVariables(DeclarationParser.stripComments(source));
    }

    private void index(StyleRule rule) {
        var compounds = rule.selector().compounds();
        var rightmost = compounds.get(compounds.size() - 1);
        String id = null;
        String cls = null;
        String type = null;
        for (var part : rightmost.parts()) {
            // STOP AT THE PSEUDO-ELEMENT: what follows describes the PART, and the lookup is done with
            // the HOST.
            //
            // `StyleEngine` fetches a `::part()` rule's candidates with `candidatesFor(shadowHost)`,
            // because a part rule is indexed under the host's own type and classes. So a class written
            // after the pseudo-element -- which CSS says describes the part, and which
            // `matchesAfterPseudoElement` correctly tests against the part -- must not choose the
            // bucket: it files the rule under a class the host does not have, and the host lookup can
            // never return it. The rule then matches nothing at all, silently.
            //
            // Only a HOSTLESS part rule could reach this. `taskbar .__entry__::part(pre-icon).__tile-1__`
            // is unaffected because its compound also carries `__entry__`, which the host does have, so
            // the rule lands in a bucket the lookup asks for anyway -- which is why every shipped part
            // rule worked and hid the gap. `::part(pre-icon).__completion-icon__` has nothing before the
            // pseudo-element, so with the class filed as its key it was indexed under a bucket nobody
            // asks the host for; the M6.1 note that `::part(x)` alone is legal was verified on a rule
            // with no trailing compound. On screen: a `SymbolIcon` in a dock tab drew no icon at all,
            // while a tab whose symbol had not resolved kept its file-type glyph and looked correct.
            if (part.type() == SelectorType.PSEUDO_ELEMENT) break;
            switch (part.type()) {
                case ID -> id = part.identity();
                case CLASS -> { if (cls == null) cls = part.identity(); }
                case TYPE -> type = part.identity();
                default -> { /* UNIVERSAL / PSEUDO_CLASS alone can't narrow the bucket */ }
            }
        }
        // ONE BUCKET PER RULE, Blink's: an element the rule matches carries every key of its compound, so any one of
        // them finds it, and a rule in one bucket needs no de-duplication per lookup.
        if (id != null) byId.computeIfAbsent(id, k -> new ArrayList<>()).add(rule);
        else if (cls != null) byClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(rule);
        else if (type != null) byType.computeIfAbsent(type, k -> new ArrayList<>()).add(rule);
        else universal.add(rule);
        indexStateDescendants(rule);
        indexClassDescendants(rule);
    }

    /**
     * Records what a descendant needs re-matching for when an ANCESTOR gains or loses a class.
     *
     * <p>{@link #indexStateDescendants}'s twin for classes: Blink's class invalidation set. A class on a
     * window, a panel or a foldout decides which rules apply below it only through rules that name that class
     * in an ancestor or parent compound, so {@code .__collapsed__ > .__content__} makes {@code __content__}
     * the one key a collapse re-matches.</p>
     *
     * <p>A subject is keyed by what precedes a pseudo-element: the walk marks light descendants, and a marked
     * host re-matches its exposed parts. A subject with nothing to key on -- {@code .x *}, or a part rule
     * with no host -- makes that class reach everything.</p>
     */
    private void indexClassDescendants(StyleRule rule) {
        var compounds = rule.selector().compounds();
        if (compounds.size() < 2) return;
        Set<String> ancestorClasses = new HashSet<>();
        for (int i = 0; i < compounds.size() - 1; i++) {
            for (var part : compounds.get(i).parts()) {
                if (part.type() == SelectorType.CLASS) ancestorClasses.add(part.identity());
            }
        }
        if (ancestorClasses.isEmpty()) return;

        Set<String> subjectKeys = new HashSet<>();
        for (var part : compounds.get(compounds.size() - 1).parts()) {
            if (part.type() == SelectorType.PSEUDO_ELEMENT) break;
            switch (part.type()) {
                case ID, CLASS, TYPE -> subjectKeys.add(part.identity());
                default -> { /* not a key we can narrow on */ }
            }
        }
        for (String cls : ancestorClasses) {
            if (subjectKeys.isEmpty()) {
                classDescendantsUnbounded.add(cls);
            } else {
                classDescendantsByClass.computeIfAbsent(cls, k -> new HashSet<>()).addAll(subjectKeys);
            }
        }
    }

    /**
     * Records what a descendant needs re-matching for when an ANCESTOR's state changes.
     *
     * <h3>The cost this exists to remove</h3>
     *
     * <p>{@code UIElement.invalidateStyleMatch()} recurses into every descendant, and it has to: a
     * descendant selector can key off this element's state, so {@code checkbox:checked .__mark__} means
     * the mark's match depends on the checkbox's. Doing it for the <em>whole subtree</em> is what made
     * hovering expensive — measured in a running client, one hover change re-matched <b>291</b> elements
     * and a focus change <b>402 to 713</b>, at 20-25µs each, which is most of a frame per mouse move.</p>
     *
     * <p>But almost nothing is actually keyed that way. Across every sheet this project ships, the
     * complete set of subjects reachable from an ancestor's state is thirteen — {@code __mark__},
     * {@code __thumb__}, {@code __knob__}, {@code __fill__}, {@code __spacer__} and a few more. So the
     * question "which of my descendants could this change?" has a small answer, and asking it turns a
     * subtree re-match into a subtree WALK with a set lookup per node.</p>
     *
     * <p>This is Blink's {@code RuleFeatureSet} descendant invalidation set, in the one shape this engine
     * needs: keys taken from the <b>subject</b> compound of any rule whose ancestor part carries a
     * pseudo-class. @see #stateDescendantKeys</p>
     */
    private void indexStateDescendants(StyleRule rule) {
        var compounds = rule.selector().compounds();
        if (compounds.size() < 2) return;
        boolean ancestorHasState = false;
        for (int i = 0; i < compounds.size() - 1 && !ancestorHasState; i++) {
            for (var part : compounds.get(i).parts()) {
                if (part.type() == SelectorType.PSEUDO_CLASS) {
                    ancestorHasState = true;
                    break;
                }
            }
        }
        if (!ancestorHasState) return;

        // WHICH ANCESTORS CAN REACH IT, not merely that some ancestor can.
        //
        // The descendant keys alone are a set the whole sheet shares, so ANY state change anywhere marked
        // every element carrying any of them. `text` is one such key -- several rules read
        // `something:hover text` -- so every label in the window re-matched on every hover, and hover
        // changes as fast as the mouse moves. Measured in a client: `rematched=578 ~text=123
        // ~__qp-key__=60 ~__qp-key-sep__=45` on a frame with `style 13526us`, sustained across a third of
        // frames, with the Go to File popup CLOSED and focus in the editor. 300-600 elements at ~20-25us
        // each is the whole 120-to-40.
        //
        // Keying by the stateful ancestor is Blink's RuleFeatureSet: a descendant invalidation set hangs
        // off the thing whose state changed, so `.__quick-pick__:focus-within .__qp-key__` contributes
        // nothing at all unless the element that changed IS a quick-pick.
        Set<String> ancestorKeys = new HashSet<>();
        boolean ancestorKeyed = false;
        for (int i = 0; i < compounds.size() - 1; i++) {
            boolean statefulHere = false;
            for (var part : compounds.get(i).parts()) {
                if (part.type() == SelectorType.PSEUDO_CLASS) statefulHere = true;
            }
            if (!statefulHere) continue;
            for (var part : compounds.get(i).parts()) {
                switch (part.type()) {
                    case ID, CLASS, TYPE -> {
                        ancestorKeys.add(part.identity());
                        ancestorKeyed = true;
                    }
                    default -> { /* a bare pseudo-class ancestor -- see below */ }
                }
            }
        }

        boolean keyed = false;
        Set<String> descendantKeys = new HashSet<>();
        for (var part : compounds.get(compounds.size() - 1).parts()) {
            switch (part.type()) {
                case ID, CLASS, TYPE -> {
                    stateDescendantKeys.add(part.identity());
                    descendantKeys.add(part.identity());
                    keyed = true;
                }
                default -> { /* not a key we can narrow on */ }
            }
        }
        if (keyed) {
            if (ancestorKeyed) {
                for (String ancestorKey : ancestorKeys) {
                    stateDescendantsByAncestor
                            .computeIfAbsent(ancestorKey, k -> new HashSet<>())
                            .addAll(descendantKeys);
                }
            } else {
                // `:hover .__icon__` -- a stateful ancestor nothing can be keyed on, so any element could
                // be that ancestor. These stay in the always-consulted set rather than turning the whole
                // narrowing off, which is the difference between one imprecise RULE and an imprecise SHEET.
                stateDescendantsFromAnyAncestor.addAll(descendantKeys);
            }
        }
        // A SUBJECT NOTHING CAN BE KEYED ON -- `foo:hover *`, or a bare pseudo-class -- means any
        // descendant could match, so the narrowing is off for the whole sheet rather than quietly
        // wrong for that one rule. None of the shipped sheets contains such a rule; the flag is what
        // makes adding one safe rather than a silent stale-style bug.
        if (!keyed) stateDescendantsUnbounded = true;
    }

    /**
     * All rules whose bucket key could plausibly match {@code element} — a bucket hit only narrows
     * the candidate set, callers must still verify with {@link Selector#matches}.
     */
    public List<StyleRule> candidatesFor(Styleable element) {
        // NO DE-DUPLICATION: a rule is in exactly one bucket, and an element asks each of its buckets once.
        List<StyleRule> candidates = new ArrayList<>(universal);
        if (!element.getId().isEmpty()) addAll(candidates, byId.get(element.getId()));
        for (String cls : element.getClasses()) addAll(candidates, byClass.get(cls));
        for (String type : element.typeKeys()) addAll(candidates, byType.get(type));
        return candidates;
    }

    private static void addAll(List<StyleRule> into, @Nullable List<StyleRule> bucket) {
        if (bucket != null) into.addAll(bucket);
    }

    /** Re-reads the {@code ua/} parts at {@link StyleOrigin#USER_AGENT}.
     *
     * <p>Shouts if it comes back empty rather than failing quietly: {@code StyleSheetRegistry.of}
     * returns an empty sheet for a missing resource, and because {@link #DEFAULT} holds the result
     * forever, a packaging slip would otherwise surface only as every widget silently laying out at
     * 0x0 — which is precisely the failure this file exists to prevent. */
    private static StyleSheet loadUserAgentSheet() {
        StyleSheet sheet = StyleSheetRegistry.of(StyleSheetRegistry.DEFAULT_SHEET);
        if (sheet.getRules().isEmpty()) {
            CrystalGuiCore.LOGGER.error(
                    "StyleSheet.DEFAULT is EMPTY — every 'assets/crystalgui/ui/styles/ua/*.css' part is "
                            + "missing or failed to parse. Widgets have no default geometry and will lay "
                            + "out at zero size.");
        }
        return new StyleSheet(sheet.getRules(), StyleOrigin.USER_AGENT);
    }

    public List<StyleRule> getRules() {
        return List.copyOf(rules);
    }

    /**
     * Swaps this sheet's rules for a freshly parsed set, <b>keeping the instance</b>.
     *
     * <p>Identity is the whole point. {@link StyleEngine} holds sheets in a list, {@link #DEFAULT} is a
     * {@code static final}, and every window that has ever called {@code addStylesheet} holds the same
     * reference — so a reload that produced a <em>new</em> sheet would update nothing that is already on
     * screen, and {@code DEFAULT} could not be replaced at all. Refilling in place means every existing
     * holder sees the new rules with no re-registration.</p>
     *
     * <p>The indices are rebuilt too, not merely appended to: {@link #candidatesFor} answers out of them,
     * so a rule deleted from the file has to leave the buckets or it keeps matching. Dropping a stale
     * <em>candidate</em> is then {@code StyleEngine.rematch}'s job, which it already does correctly — it
     * remembers what it last applied per element and swaps the whole set atomically.</p>
     *
     * <p>Package-private, and reached through {@link StyleSheetRegistry#reloadAll()} rather than called
     * directly: a sheet does not know which file it came from, and the registry is what does.</p>
     */
    void replaceRules(List<StyleRule> replacement) {
        rules.clear();
        rules.addAll(replacement);
        byId.clear();
        byClass.clear();
        byType.clear();
        universal.clear();
        // REBUILT WITH THE REST. A hot-reload that dropped a `:hover .__mark__` rule and left its key
        // behind would merely cost a little; one that ADDED such a rule and did not gain the key would
        // leave that mark permanently stale, which is the failure this whole mechanism must not create.
        stateDescendantKeys.clear();
        // CLEARED WITH THEIR SIBLING, or a hot reload leaves the old sheet's ancestor keys behind and the
        // narrowing quietly widens to whatever any previously-loaded sheet said. @see #indexStateDescendants
        stateDescendantsByAncestor.clear();
        stateDescendantsFromAnyAncestor.clear();
        stateDescendantsUnbounded = false;
        classDescendantsByClass.clear();
        classDescendantsUnbounded.clear();
        for (var rule : rules) index(rule);
    }

    /**
     * Re-substitutes this sheet's retained source against {@code externalVariables} and swaps the
     * rules in place — the <b>theme-binding</b> operation. No I/O; identity-stable, so every window
     * holding this sheet sees the new values with no re-registration (the same guarantee
     * {@link #replaceRules} documents). No-op for rule-copy instances with no source
     * ({@link #DEFAULT} — the registry mirrors it from the cached source sheet instead).
     */
    void rebind(Map<String, String> externalVariables) {
        if (rawSource == null) return;
        replaceRules(parse(rawSource, externalVariables).getRules());
    }

    /**
     * Replaces both the retained source and the rules — the <b>hot-reload</b> operation, for when
     * the file's text itself changed. Parses against {@code externalVariables} so a reload while a
     * theme is active keeps the theme's values.
     */
    void refill(String newSource, Map<String, String> externalVariables) {
        this.rawSource = newSource;
        replaceRules(parse(newSource, externalVariables).getRules());
    }

    /**
     * The text this was parsed from, or null for a sheet built any other way.
     *
     * <p>What an editor measures against: {@code CssSourceModel.parse(sheet.source())} gives the rules with
     * their ranges, and every range is an offset into <em>this</em> string.</p>
     */
    @Nullable
    public String source() {
        return rawSource;
    }

    /**
     * Public identity-stable swap: adopts {@code freshlyParsed}'s rules and source, keeping
     * <em>this</em> instance — for holders like {@code UiThemeManager} whose sheets live in engine
     * lists and must never be re-registered. The parsed argument is a carrier, not a peer: parse
     * into a throwaway, hand it here, drop it.
     */
    public void refillFrom(StyleSheet freshlyParsed) {
        this.rawSource = freshlyParsed.rawSource;
        replaceRules(freshlyParsed.getRules());
    }
}
