package com.crystalgui.style;

import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.lang.SymbolKind;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The anti-rot machinery ({@code plan_styling.md} §4.2) — the styling contracts that used to live
 * in comments, promoted to build failures. default.css rotted to 161 unrelated colours precisely
 * because breaking its rules broke no build; each test here is one of those rules with teeth.
 *
 * <p>Plain text analysis over the shipped sheets (loaded from the classpath, so it runs wherever
 * the resources do). Deliberately no engine involvement: these are contracts about the FILES.</p>
 */
public class StyleGovernanceTest {

    private static final String STYLES = "/assets/crystalgui/ui/styles/";
    private static final String THEMES = "/assets/crystalgui/ui/themes/";
    private static final String SCHEMES = "/assets/crystalgui/ui/schemes/";

    /** What a SCHEME may define — the editor's tokens and nothing else. The split that makes
     * "Crystal Dark + a different scheme" a legal pair (plan_styling.md §3.6). */
    private static final List<String> SCHEME_TOKEN_PREFIXES =
            List.of("--editor-", "--syntax-", "--find-match-", "--search-excluded-");

    /**
     * <b>Palette tokens: defined by NO theme, on purpose.</b> A language's brand colour, a graph
     * port type, a VCS status — these mean the same thing in every theme, so pinning them in one
     * would force every other theme to restate them (and a light theme that forgot would silently
     * lose Java's orange). They live as their sheet's {@code var()} fallback and nowhere else.
     *
     * <p>Named here rather than left to look like a typo, and deliberately by PREFIX with an
     * exception list: {@code --graph-port-vec3} is palette, {@code --graph-port-label-hover-fg} is
     * the band's own chrome and must derive like any other surface — a prefix rule alone captured
     * it once and quietly deleted its derivation.</p>
     */
    private static final List<String> FALLBACK_ONLY_PREFIXES =
            List.of("--filetype-", "--graph-port-");
    private static final Set<String> FALLBACK_ONLY_EXCEPTIONS =
            Set.of("--graph-port-label-hover-fg");
    private static final Set<String> FALLBACK_ONLY_EXACT = Set.of(
            "--decoration-added", "--decoration-renamed", "--decoration-untracked",
            "--decoration-deleted", "--decoration-conflict");

    /**
     * <b>Offered hooks: defined by no theme, and that is the point.</b> Distinct from a palette —
     * these have a <em>no-op</em> fallback (transparent, none, zero), so the engine's default is "do
     * nothing" and a theme that wants the effect opts in by defining the token.
     *
     * <p>{@code --tree-bg} is the shape of it: the file tree paints no surface of its own now, so
     * the island's fill draws the panel's rounded corners instead of being squared off by it — but a
     * theme that genuinely wants a recessed well behind its file list still has a name to say so
     * with. An undefined reference with a no-op fallback is an offer, not a typo.</p>
     */
    private static final Set<String> OPTIONAL_HOOK_TOKENS = Set.of(
            "--tree-bg",
            // The active-group ring: off at 0px, because a permanent rectangle round the pane you
            // are working in says what its own tab already says. The token survives so a theme that
            // wants the affordance back has a name for it — deleting the rule would take the hook.
            "--dock-active-ring-width");

    private static boolean isFallbackOnly(String token) {
        if (FALLBACK_ONLY_EXCEPTIONS.contains(token)) return false;
        if (FALLBACK_ONLY_EXACT.contains(token) || OPTIONAL_HOOK_TOKENS.contains(token)) return true;
        return FALLBACK_ONLY_PREFIXES.stream().anyMatch(token::startsWith);
    }

    /** The engine's structure sheets — every colour in them must be a token reference. The
     * user-agent parts come from {@link StyleSheetRegistry#DEFAULT_SHEET_PARTS}, the one manifest,
     * so renaming a part without updating it fails here rather than silently shrinking coverage.
     * ore.css is deliberately absent: it is a full sprite SKIN awaiting its move to {@code themes/}
     * (plan_styling.md §3.2), where hex is legal — holding it to the token rule now would demand
     * tokenizing a file whose whole content is theme-side. */
    private static final List<String> STRUCTURE_SHEETS = structureSheets();

    private static List<String> structureSheets() {
        List<String> sheets = new ArrayList<>(userAgentParts());
        sheets.add("graph.css");
        sheets.add("decorations.css");
        return sheets;
    }

    /** {@code "crystalgui:ua/core"} → {@code "ua/core.css"}, relative to {@link #STYLES}. */
    private static List<String> userAgentParts() {
        List<String> parts = new ArrayList<>();
        for (String key : StyleSheetRegistry.DEFAULT_SHEET_PARTS) {
            parts.add(key.substring(key.indexOf(':') + 1) + ".css");
        }
        return parts;
    }

    /**
     * <b>The system vocabulary, pinned</b> — plan_styling.md §3.1, decided 2026-08-10. This list
     * IS the spec: base.css may derive only into these names, and adding a name here is an API
     * decision (append-mostly; renames go through the manager's alias table), not a convenience.
     */
    private static final Set<String> SYSTEM_VOCABULARY = Set.of(
            "surface-base", "surface-panel", "surface-raised", "surface-recessed",
            "surface-editor", "surface-overlay",
            // A SEVENTH SURFACE, for what the workspace does not own -- a library class opened
            // read-only. Appended for the same reason `success-icon` was: the set was incomplete rather
            // than closed. Every other surface here is a DEPTH, and this one is a PROVENANCE, which is
            // why it could not be derived from one -- "borrowed" is not a distance from the reader.
            "surface-borrowed", "surface-borrowed-raised",
            "border-base", "border-strong", "border-field", "divider",
            "fg", "fg-secondary", "fg-hint", "fg-disabled", "fg-on-accent",
            "accent", "accent-hover", "accent-soft",
            "hover-bg", "pressed-bg", "selection-bg", "selection-inactive-bg", "focus-ring",
            "error", "warning", "info", "success", "modified", "link",
            // The saturated counterparts, for a filled MARK rather than for a word. The pair above is
            // darkened until a sentence in it reads on the theme's surface -- which is why light's
            // --warning is an olive -- and a severity ICON wants the amber the artwork used to carry.
            // Two roles rather than one because the two jobs genuinely disagree, most visibly in light.
            // success-icon appended when the Run panel needed a live mark: the same argument as the three
            // beside it, and the set was simply incomplete -- --success is the body colour for a finished
            // thing and too dark to read as a 10px dot.
            "error-icon", "warning-icon", "info-icon", "success-icon",
            // Non-colour, and themeable for the same reason IntelliJ's themes set arcs and insets:
            // "Islands" IS these three plus a palette, and a flat theme is them zeroed.
            "radius-panel", "radius-control", "panel-gap");

    /**
     * Raw hexes still permitted OUTSIDE a var() fallback, each one a named decision.
     * {@code #00000000} — the none-fill, a transparent constant rather than a colour — is exempted
     * in the test body; everything else goes through a token. <b>Empty, and it stays empty:</b>
     * the step-5 migration drained it, and adding an entry here is the five-mid-greys bug asking
     * to be reborn.
     */
    private static final Set<String> BARE_HEX_ALLOWLIST = Set.of();

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
    private static final Pattern VAR_WITH_FALLBACK = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*,[^)]*\\)");
    private static final Pattern VAR_ANY = Pattern.compile("var\\(\\s*(--[\\w-]+)");
    private static final Pattern VAR_DEF = Pattern.compile("(--[\\w-]+)\\s*:\\s*([^;}]+)");
    private static final Pattern TOKEN_NAME = Pattern.compile("--[a-z0-9]+(-[a-z0-9]+)*");

    // ── rule 1: no raw hex outside a var() fallback ─────────────────────────────────────────────

    /**
     * <b>Every colour in a structure sheet is a token reference.</b> A hex may appear only as a
     * {@code var(--token, #hex)} fallback (the themeless resting value — VS Code's
     * default-in-registry pattern) or on the shrinking allowlist above. This is the single
     * strongest anti-rot rule: the compile-time answer to "just this one grey".
     */
    @Test
    public void structureSheetsCarryNoBareHex() {
        List<String> offences = new ArrayList<>();
        for (String sheet : STRUCTURE_SHEETS) {
            String css = stripComments(load(STYLES + sheet));
            // remove every var(...) span (fallbacks are sanctioned), then look for what remains
            String withoutVars = removeVarSpans(css);
            Matcher hex = HEX.matcher(withoutVars);
            while (hex.find()) {
                String found = hex.group();
                if (found.equalsIgnoreCase("#00000000")) continue;      // the none-fill
                if (BARE_HEX_ALLOWLIST.contains(sheet + "|" + found)) continue;
                offences.add(sheet + ": bare " + found);
            }
        }
        assertTrue("Bare hex outside a var() fallback — route it through a token:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    // ── rule 2: every reference resolves ────────────────────────────────────────────────────────

    /**
     * Every {@code var(--name)} referenced by a shipped sheet is defined somewhere the engine will
     * actually look: the sheet's own locals, base.css, or crystal-dark.css. Undefined references
     * only warn at runtime — this makes a typo'd token a build failure for SHIPPED files while
     * staying lenient for user themes.
     */
    @Test
    public void everyTokenReferenceIsDefined() {
        Set<String> defined = new HashSet<>();
        definitionsOf(load(THEMES + "base.css")).forEach((k, v) -> defined.add(k));
        definitionsOf(load(THEMES + "crystal-dark.css")).forEach((k, v) -> defined.add(k));
        definitionsOf(load(SCHEMES + "dark-plus.css")).forEach((k, v) -> defined.add(k));

        // The ua/ parts are ONE sheet — one parse, one local-variable scope — so a --cfg-* defined
        // in config-kit.css legitimately serves a use in inspector.css. Locals are therefore
        // collected across the whole concatenation for the parts, per-file for standalone sheets.
        Set<String> uaLocals = new HashSet<>();
        for (String part : userAgentParts()) {
            uaLocals.addAll(definitionsOf(load(STYLES + part)).keySet());
        }

        List<String> offences = new ArrayList<>();
        for (String sheet : STRUCTURE_SHEETS) {
            String css = stripComments(load(STYLES + sheet));
            Set<String> local = sheet.startsWith("ua/") ? uaLocals : definitionsOf(css).keySet();
            Matcher ref = VAR_ANY.matcher(css);
            while (ref.find()) {
                String name = ref.group(1);
                if (isFallbackOnly(name)) continue;
                if (!defined.contains(name) && !local.contains(name)) {
                    offences.add(sheet + ": " + name);
                }
            }
        }
        assertTrue("var() references no shipped table defines (typo, or a token deleted without its uses).\n"
                + "If it is a theme-independent palette value, add it to FALLBACK_ONLY_* above — deliberately,\n"
                + "since a light theme then inherits the dark file's value:\n"
                + String.join("\n", offences), offences.isEmpty());
    }

    // ── rule 3: naming lint ─────────────────────────────────────────────────────────────────────

    /** Token names are kebab-case, lower, no value words enforced socially — the shape at least
     * is mechanical: {@code --[a-z0-9]+(-[a-z0-9]+)*}. */
    @Test
    public void tokenNamesFollowTheConvention() {
        List<String> offences = new ArrayList<>();
        for (String file : List.of(THEMES + "base.css", THEMES + "crystal-dark.css")) {
            for (String name : definitionsOf(load(file)).keySet()) {
                if (!TOKEN_NAME.matcher(name).matches()) offences.add(file + ": " + name);
            }
        }
        assertTrue("Token names off-convention:\n" + String.join("\n", offences), offences.isEmpty());
    }

    // ── rule 4: base.css is derivation-only ─────────────────────────────────────────────────────

    /**
     * <b>Every value in base.css is a reference into the system vocabulary.</b> A component token
     * holding a hex literal here is the five-mid-greys bug being reborn — exact current values
     * that match no system role live in crystal-dark's fine-tune block instead, where they are
     * visibly a theme's choice and step 11's shrink target.
     */
    @Test
    public void baseIsDerivationOnly() {
        List<String> offences = new ArrayList<>();
        definitionsOf(load(THEMES + "base.css")).forEach((name, value) -> {
            // THE ONE LITERAL ALLOWED HERE, and it is not a colour: fully transparent means "this
            // component paints NOTHING", which no system role can express — every one of them names
            // a colour. It is what an element filling an island edge to edge must say so the island's
            // own fill draws the rounded corner instead of being squared off by it.
            if (value.trim().equalsIgnoreCase("#00000000")) return;
            Matcher ref = VAR_ANY.matcher(value.trim());
            if (!ref.matches() && !value.trim().matches("var\\(\\s*--[\\w-]+\\s*\\)")) {
                offences.add(name + ": " + value);
            } else {
                Matcher m = VAR_ANY.matcher(value);
                if (m.find() && !SYSTEM_VOCABULARY.contains(m.group(1).substring(2))) {
                    offences.add(name + " derives from a non-system token: " + value);
                }
            }
        });
        assertTrue("base.css must derive into the system vocabulary only:\n" + String.join("\n", offences),
                offences.isEmpty());
    }

    // ── rule 5: the shipped theme is complete ───────────────────────────────────────────────────

    /** Each shipped theme defines the whole system vocabulary — the completeness a sparse
     * third-party theme relies on through {@code @extends}. */
    @Test
    public void everyShippedThemeDefinesTheFullSystemVocabulary() {
        for (String theme : List.of("crystal-dark.css", "crystal-light.css")) {
            Set<String> defined = new HashSet<>();
            definitionsOf(load(THEMES + theme)).forEach((k, v) -> defined.add(k.substring(2)));
            List<String> missing = new ArrayList<>();
            for (String name : SYSTEM_VOCABULARY) {
                if (!defined.contains(name)) missing.add(name);
            }
            assertTrue(theme + " is missing system tokens:\n" + String.join("\n", missing), missing.isEmpty());
        }
    }

    /**
     * <b>Each light/dark pair defines the IDENTICAL key set.</b> The test that keeps light mode from
     * rotting the moment attention moves on: a token added to one alone leaves the other resolving
     * it from the sheet's fallback — a dark value on a light surface, invisible to anyone not
     * running the light side, which is nearly everyone.
     */
    @Test
    public void eachThemeAndSchemePairDefinesTheSameKeys() {
        assertSameKeys(THEMES + "crystal-dark.css", THEMES + "crystal-light.css");
        assertSameKeys(SCHEMES + "dark-plus.css", SCHEMES + "light-plus.css");
        assertSameKeys(SCHEMES + "islands-dark.css", SCHEMES + "islands-light.css");
    }

    /**
     * <b>Every shipped scheme defines every key, not merely every pair.</b>
     *
     * <p>Pairing alone is not enough once there is more than one pair: two schemes could each be
     * internally consistent and disagree with each other, and switching between them would leave whichever
     * token only one of them names resolving to its {@code var()} fallback — i.e. silently reverting to
     * Dark+'s colour in the middle of an IntelliJ palette. The fallback is there so an editor with NO
     * scheme still reads, not so a scheme can be half-written.</p>
     */
    @Test
    public void everySchemeDefinesTheSameKeysAsEveryOther() {
        List<String> schemes = shippedSchemes();
        assertTrue("expected several schemes to compare, found " + schemes, schemes.size() > 1);

        String reference = schemes.get(0);
        for (String scheme : schemes.subList(1, schemes.size())) {
            assertSameKeys(SCHEMES + reference, SCHEMES + scheme);
        }
    }

    /**
     * The schemes actually on disk — <b>discovered, never listed</b>, so adding one cannot skip these
     * checks by being forgotten in a constant.
     *
     * <p>A hardcoded list is exactly what {@code StylePropertyRegistry} demonstrates going stale
     * silently: the new entry is a one-line addition somewhere else and nothing links the two. Three of
     * its properties were missing for a full release cycle that way.</p>
     */
    private static List<String> shippedSchemes() {
        Path dir = schemesDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".css"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("cannot list " + dir, e);
        }
    }

    private static Path schemesDir() {
        String relative = "src/main/resources/assets/crystalgui/ui/schemes";
        Path fromModule = Path.of(relative);
        if (Files.isDirectory(fromModule)) return fromModule;
        Path fromRoot = Path.of("core").resolve(relative);
        assertTrue("cannot locate the schemes directory from " + Path.of("").toAbsolutePath(),
                Files.isDirectory(fromRoot));
        return fromRoot;
    }

    private static void assertSameKeys(String darkFile, String lightFile) {
        Set<String> dark = definitionsOf(load(darkFile)).keySet();
        Set<String> light = definitionsOf(load(lightFile)).keySet();

        Set<String> darkOnly = new TreeSet<>(dark);
        darkOnly.removeAll(light);
        Set<String> lightOnly = new TreeSet<>(light);
        lightOnly.removeAll(dark);

        assertTrue("in " + darkFile + " but not its light pair: " + darkOnly
                + "\nin " + lightFile + " but not its dark pair: " + lightOnly,
                darkOnly.isEmpty() && lightOnly.isEmpty());
    }

    // ── rules 6+7: the two axes stay apart ──────────────────────────────────────────────────────

    /**
     * <b>A scheme defines editor tokens only; the theme defines everything but.</b> Both directions
     * enforced, because either drift breaks the axes' independence: a scheme carrying a chrome
     * token would restyle panels when the user only asked for different syntax colours, and a theme
     * carrying editor tokens would fight whichever scheme is active for them (the theme would lose
     * — schemes merge later — but silently, which reads as a broken theme).
     */
    @Test
    public void theSchemeAndThemeAxesStayApart() {
        List<String> offences = new ArrayList<>();
        for (String scheme : shippedSchemes()) {
            definitionsOf(load(SCHEMES + scheme)).forEach((name, value) -> {
                if (SCHEME_TOKEN_PREFIXES.stream().noneMatch(name::startsWith)) {
                    offences.add(scheme + " defines a non-scheme token: " + name);
                }
            });
        }
        definitionsOf(load(THEMES + "crystal-dark.css")).forEach((name, value) -> {
            if (SCHEME_TOKEN_PREFIXES.stream().anyMatch(name::startsWith)) {
                offences.add("crystal-dark.css defines a scheme-owned token: " + name);
            }
        });
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    // ── rule 7b: every capture a grammar can emit has a colour ──────────────────────────────────

    /**
     * <b>Every {@code --syntax-*} token the UA sheet reads is defined by every scheme.</b>
     *
     * <p>This is the highest-value check here because the failure it catches is <em>invisible</em>. A
     * capture with no colour renders as body text, which looks exactly like a capture the grammar never
     * produced — so "my Java has no operators highlighted" and "the grammar does not capture operators"
     * are indistinguishable on screen, and only one of them is a bug.</p>
     *
     * <p>It caught six on the day it was written: {@code operator}, {@code attribute}, {@code variable},
     * {@code constant}, {@code property} and {@code tag}. Six rules had been thought sufficient because
     * {@code generalName()} folds a specialisation onto its stem — true, but only for names that HAVE a
     * stem, and none of those six do.</p>
     */
    @Test
    public void everySyntaxTokenTheSheetReadsIsDefinedByEveryScheme() {
        Set<String> read = new TreeSet<>();
        for (String part : userAgentParts()) {
            Matcher matcher = Pattern.compile("var\\((--syntax-[a-z-]+)")
                    .matcher(stripComments(load(STYLES + part)));
            while (matcher.find()) read.add(matcher.group(1));
        }
        assertFalse("the sheet reads no --syntax-* tokens at all; the query is wrong", read.isEmpty());

        List<String> offences = new ArrayList<>();
        for (String scheme : shippedSchemes()) {
            Set<String> defined = definitionsOf(load(SCHEMES + scheme)).keySet();
            for (String token : read) {
                if (!defined.contains(token)) {
                    offences.add(scheme + " never defines " + token + ", which the UA sheet reads");
                }
            }
        }
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    /**
     * <b>A capture drawn in the keyword colour is drawn at the keyword weight.</b>
     *
     * <p>A scheme bolds "the keyword family" as one gesture, not once per capture. Eclipse Dark paints
     * ten captures in its keyword orange and sets {@code --syntax-keyword-weight: bold}; only
     * {@code keyword} and {@code type.builtin} read that weight, so {@code console}, {@code null},
     * {@code true}, a GLSL {@code uniform} and an HTML tag came out orange and thin beside a bold
     * {@code var} on the same line — the palette looking half-applied rather than deliberate.</p>
     *
     * <p>That is the defect {@code type.builtin}'s own comment already records from the other direction
     * ("the whole line was bold except its return type, which reads as the one word having failed"). It
     * was fixed for the capture somebody noticed rather than for the channel, which is exactly the shape
     * that comes back. Stated as a rule over the shipped schemes so the eleventh capture cannot be added
     * without it: <b>orange and bold, or neither.</b></p>
     *
     * <p>Keyed on the resolved COLOUR rather than on a list of capture names, because the list is the
     * thing that goes stale — a scheme that gives {@code function.builtin} a green of its own (Eclipse
     * Dark does) is correctly silent here, and one that later repaints it keyword-orange is caught
     * without anybody remembering to add a row.</p>
     */
    @Test
    public void everyCaptureDrawnInTheKeywordColourAlsoReadsTheKeywordWeight() {
        Pattern rule = Pattern.compile("::highlight\\(([a-z][a-z.]*)\\)\\s*\\{([^}]*)}", Pattern.DOTALL);
        Pattern colour = Pattern.compile("color:\\s*var\\((--syntax-[a-z-]+)");

        // capture name -> (colour token, does the rule read the weight)
        Map<String, String> colourOf = new LinkedHashMap<>();
        Set<String> readsWeight = new LinkedHashSet<>();
        for (String part : userAgentParts()) {
            Matcher rules = rule.matcher(stripComments(load(STYLES + part)));
            while (rules.find()) {
                String name = rules.group(1);
                String body = rules.group(2);
                Matcher paint = colour.matcher(body);
                if (paint.find()) colourOf.putIfAbsent(name, paint.group(1));
                if (body.contains("--syntax-keyword-weight")) readsWeight.add(name);
            }
        }
        assertFalse("no ::highlight rules were parsed at all; the query is wrong", colourOf.isEmpty());
        assertTrue("the keyword rule itself must read the weight -- otherwise this test is vacuous",
                readsWeight.contains("keyword"));

        List<String> offences = new ArrayList<>();
        for (String scheme : shippedSchemes()) {
            Map<String, String> defined = definitionsOf(load(SCHEMES + scheme));
            String keywordColour = defined.get("--syntax-keyword");
            if (keywordColour == null) continue;
            // ONLY WHERE THE SCHEME ACTUALLY USES THE CHANNEL. A scheme that leaves the weight `normal`
            // has no inconsistency to see, and enforcing there would legislate palette COINCIDENCE as
            // family membership: Islands paints `string.escape` and `function.builtin` in the same warm
            // orange as its keywords, which is a palette with few hues rather than a claim that an escape
            // sequence is a keyword. Written the other way round this test demanded they bold together
            // the moment anyone set the key.
            String weight = defined.get("--syntax-keyword-weight");
            if (weight == null || "normal".equalsIgnoreCase(weight.trim())) continue;
            for (Map.Entry<String, String> entry : colourOf.entrySet()) {
                String value = defined.get(entry.getValue());
                if (value == null || !value.equalsIgnoreCase(keywordColour)) continue;
                if (!readsWeight.contains(entry.getKey())) {
                    offences.add(scheme + " draws ::highlight(" + entry.getKey() + ") in the keyword"
                            + " colour " + keywordColour + " via " + entry.getValue()
                            + ", but that rule does not read --syntax-keyword-weight");
                }
            }
        }
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    /**
     * <b>Every capture name in a shipped {@code highlights.scm} is styled.</b>
     *
     * <p>The other half of the rule above, from the grammar's end rather than the sheet's: a new grammar
     * introducing a capture nobody has coloured is the same invisible failure, arriving from the opposite
     * direction. Satisfied either by a rule for the name itself or by one for its dotted general form,
     * which is what {@code SyntaxToken.generalName()} falls back to.</p>
     */
    @Test
    public void everyCaptureInAShippedGrammarHasAColour() {
        Path queries = syntaxQueriesDir();
        if (!Files.isDirectory(queries)) return;      // the grammar module is not in this build

        Set<String> styled = new TreeSet<>();
        for (String part : userAgentParts()) {
            Matcher matcher = Pattern.compile("::highlight\\(([a-z.]+)\\)")
                    .matcher(stripComments(load(STYLES + part)));
            while (matcher.find()) styled.add(matcher.group(1));
        }

        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(queries)) {
            for (Path file : files.filter(p -> p.getFileName().toString().equals("highlights.scm")).toList()) {
                // QUOTED STRINGS STRIPPED FIRST. A query matches literal tokens by writing them out, and
                // CSS's at-rules are literally at-signs: `"@media" @keyword` names one capture and one
                // keyword of the language. Scanning the raw text reports @media, @charset, @import,
                // @keyframes, @namespace and @supports as captures nobody has coloured — six failures
                // that are all the same misreading.
                String scm = Files.readString(file, StandardCharsets.UTF_8)
                        .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
                Matcher matcher = Pattern.compile("@([a-z][a-z.]*)").matcher(scm);
                while (matcher.find()) {
                    String capture = CAPTURE_DIALECT.getOrDefault(matcher.group(1), matcher.group(1));
                    if (styled.contains(capture) || styled.contains(generalNameOf(capture))) continue;
                    offences.add(file.getFileName() + " emits @" + capture
                            + " (" + file.getParent().getFileName() + "), which nothing styles");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(String.join("\n", new TreeSet<>(offences)), offences.isEmpty());
    }

    /**
     * <b>Every {@link SymbolKind} an engine can report is a capture something colours.</b>
     *
     * <p>The third direction into the same rule, and the one that arrives without a grammar. A semantic
     * token provider names its colours through {@link SymbolKind#captureName()} rather than spelling them,
     * so that bridge is a capture producer exactly like a {@code highlights.scm} — and it is not covered by
     * the test above, because there is no file to scan. A kind whose capture nothing styles renders the
     * resolved symbol as body text: the engine ran, the answer was right, and the screen is unchanged.</p>
     *
     * <p>Runs with no engine and no grammar module present, because both sides of it are in {@code core/}.
     * {@code LanguageSpiTest} asserts the other half — that no kind answers with an empty or malformed
     * name — from {@code headlessTest}, where the schemes are unreachable.</p>
     */
    @Test
    public void everySymbolKindNamesACaptureTheSheetColours() {
        Set<String> styled = new TreeSet<>();
        for (String part : userAgentParts()) {
            Matcher matcher = Pattern.compile("::highlight\\(([a-z.]+)\\)")
                    .matcher(stripComments(load(STYLES + part)));
            while (matcher.find()) styled.add(matcher.group(1));
        }
        assertFalse("the sheet styles no highlights at all; the query is wrong", styled.isEmpty());

        List<String> offences = new ArrayList<>();
        for (SymbolKind kind : SymbolKind.values()) {
            String capture = kind.captureName();
            if (styled.contains(capture) || styled.contains(generalNameOf(capture))) continue;
            offences.add("SymbolKind." + kind + " colours as @" + capture + ", which nothing styles");
        }
        assertTrue(String.join("\n", offences), offences.isEmpty());
    }

    /**
     * Capture synonyms folded before the query reaches a scheme.
     *
     * <p><b>Mirrors {@code Queries.normalizeCaptureDialect} in the syntax module</b>, which is not on this
     * source set's classpath — `core/` must not depend on the grammar module, which is the same rule that
     * keeps natives out of a dedicated server. The duplication is two entries and is preferable to the
     * alternative: giving the synonym a `--syntax-*` token of its own, which every scheme would then have
     * to define and keep identical to the name it is a synonym for, forever.</p>
     *
     * <p>If this map and that method disagree, this test reports a capture as uncoloured that the engine
     * colours fine — a false alarm rather than a missed one, which is the right way round.</p>
     */
    private static final Map<String, String> CAPTURE_DIALECT =
            Map.of("delimiter", "punctuation.delimiter");

    /** Mirrors {@code SyntaxToken.generalName()} — the dotted fallback a theme relies on. */
    private static String generalNameOf(String capture) {
        int dot = capture.lastIndexOf('.');
        return dot <= 0 ? "" : capture.substring(0, dot);
    }

    private static Path syntaxQueriesDir() {
        String relative = "src/main/resources/assets/crystalgui/syntax";
        Path fromModule = Path.of("../language").resolve(relative);
        if (Files.isDirectory(fromModule)) return fromModule;
        return Path.of("language").resolve(relative);
    }

    // ── rule 8: the UA sheet's own three rules ──────────────────────────────────────────────────

    /** The user-agent sheet's opening contract, finally with teeth, over every part: no
     * {@code !important} (it escalates above every author sheet), no {@code asset()} (must stand
     * alone with no pack loaded). */
    @Test
    public void theUserAgentSheetKeepsItsContract() {
        for (String part : userAgentParts()) {
            String css = stripComments(load(STYLES + part));
            assertTrue(part + " must not use !important", !css.contains("!important"));
            assertTrue(part + " must not reference asset()", !css.contains("asset("));
        }
    }

    // ── rule 9: the doc cannot go stale ─────────────────────────────────────────────────────────

    /**
     * <b>The token table in {@code docs/CGUI_THEMING.md} regenerates from the css and matches.</b>
     * The StylePropertyRegistry lesson, automated instead of remembered: a hand-maintained list
     * "goes stale silently", so this one is generated — and on failure the message IS the fresh
     * table, ready to paste between the markers.
     */
    @Test
    public void theDocumentedTokenTableIsCurrent() throws IOException {
        Map<String, String> rows = new TreeMap<>();
        for (String[] source : new String[][]{
                {THEMES + "base.css", "base.css"},
                {THEMES + "crystal-dark.css", "crystal-dark.css"},
                {SCHEMES + "dark-plus.css", "dark-plus.css"}}) {
            definitionsOf(load(source[0])).forEach((name, value) ->
                    rows.putIfAbsent(name, "| `" + name + "` | `" + value + "` | " + source[1] + " |"));
        }
        String expected = String.join("\n", rows.values());

        Path doc = docPath();
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        int begin = text.indexOf("<!-- TOKENS:BEGIN -->");
        int end = text.indexOf("<!-- TOKENS:END -->");
        assertTrue("docs/CGUI_THEMING.md is missing its TOKENS markers", begin >= 0 && end > begin);

        // drop the marker line and the table header, keep the data rows
        String documented = text.substring(begin, end).lines()
                .filter(line -> line.startsWith("| `"))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        if (!documented.equals(expected)) {
            fail("The generated token table in docs/CGUI_THEMING.md is stale. Paste this between the markers"
                    + " (keeping the header row):\n| Token | Value | Defined in |\n|---|---|---|\n" + expected);
        }
    }

    /** The doc, found from the test's working directory — Gradle runs tests from the module dir,
     * IDEs sometimes from the repo root; try both rather than encoding one runner's habit. */
    private static Path docPath() {
        Path fromModule = Path.of("../docs/CGUI_THEMING.md");
        if (Files.exists(fromModule)) return fromModule;
        Path fromRoot = Path.of("docs/CGUI_THEMING.md");
        assertTrue("cannot locate docs/CGUI_THEMING.md from " + Path.of("").toAbsolutePath(),
                Files.exists(fromRoot));
        return fromRoot;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Every {@code font-family} entry in a shipped sheet must actually load.</b>
     *
     * <p>{@code font-family} here is a list of RESOURCE PATHS resolved through {@code CgIO} — not CSS
     * family names. A stack that looks like ordinary CSS ({@code "JetBrains Mono", monospace}) therefore
     * resolves to nothing, and {@code FontFamilyCache.build} <b>throws</b> rather than falling back:
     * <i>"no font-family source could be loaded"</i>.</p>
     *
     * <p>Which makes it a crash rather than a cosmetic defect, and a <em>latent</em> one — the family is
     * resolved the first time a widget carrying that rule is measured, so the sheet parses, every test
     * passes, and the application dies the first time somebody opens that one panel. It shipped exactly
     * that way on the Run console's input row: fine until a script asked for input, then the whole
     * harness went down.</p>
     */
    @Test
    public void everyShippedFontFamilyResolves() {
        Pattern declaration = Pattern.compile("font-family\\s*:\\s*([^;}]+)");
        List<String> broken = new ArrayList<>();
        for (String sheet : STRUCTURE_SHEETS) {
            String css = stripComments(load(STYLES + sheet));
            Matcher declarations = declaration.matcher(css);
            while (declarations.find()) {
                for (String entry : declarations.group(1).split(",")) {
                    String path = entry.trim().replaceAll("^[\"']|[\"']$", "").trim();
                    if (path.isEmpty() || path.startsWith("var(")) continue;
                    if (resolvesAsFont(path)) continue;
                    broken.add(sheet + ": " + path);
                }
            }
        }
        assertTrue("font-family entries that load nothing — FontFamilyCache THROWS on these, so the first"
                + " widget to be measured with one takes the application down:\n" + String.join("\n", broken),
                broken.isEmpty());
    }

    /** {@code "namespace:path"} -> {@code /assets/namespace/path}, the way {@code CgIO} resolves one. */
    private static boolean resolvesAsFont(String path) {
        int colon = path.indexOf(':');
        if (colon <= 0) return false;
        String resource = "/assets/" + path.substring(0, colon) + "/" + path.substring(colon + 1);
        try (InputStream in = StyleGovernanceTest.class.getResourceAsStream(resource)) {
            return in != null;
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static String load(String resource) {
        try (InputStream in = StyleGovernanceTest.class.getResourceAsStream(resource)) {
            if (in == null) fail("shipped resource missing: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    // ── rule 12: a line is never the colour of what it is drawn on ──────────────────────────────

    /**
     * <b>Within one component's token group, nothing that MARKS may resolve to the value of what it is
     * marked on.</b>
     *
     * <p>This is the single most repeated defect in the styling work, and it never once looked like a
     * colour mistake. Four separate times a token was derived to a role whose value happened to equal the
     * surface it sits on, and each time the result was a control that resolved correctly, cascaded
     * correctly, painted correctly, and was invisible:</p>
     *
     * <ul>
     *   <li>{@code --menu-separator} came from {@code --divider}, which equals {@code --surface-overlay} —
     *       so every section break in every menu was drawn in the menu's own fill.</li>
     *   <li>{@code --editorfind-bg} came from {@code --surface-panel}, which equals {@code --surface-editor}
     *       — a bar whose own comment says it "has to paint its own ground" painting it in the document's.</li>
     *   <li>{@code --findbar-action-disabled-bg} was that same value, so disabled actions vanished into the
     *       bar and read as three grey words.</li>
     *   <li>{@code --statusbar-sep} came from {@code --divider}, ~5 points from {@code --surface-base} —
     *       painted, and effectively absent.</li>
     * </ul>
     *
     * <h3>Pairing by name prefix, which is what makes it checkable at all</h3>
     *
     * <p>The general question — "is this element's line the colour of its parent's fill?" — needs the DOM,
     * and a stylesheet has no DOM. But component tokens are already grouped by prefix ({@code --menu-*},
     * {@code --statusbar-*}, {@code --editorfind-*}), and a group's {@code -bg} IS the surface its own
     * {@code -fg}/{@code -sep}/{@code -border} are drawn on. That convention is not enforced anywhere and
     * does not need to be: where it holds, this catches the bug; where a group has no {@code -bg}, there
     * is simply nothing to compare and the test says nothing.</p>
     *
     * <p>Equality only, deliberately — no perceptual threshold. A near-miss is a judgement call about how
     * visible is visible enough, and a test that fails on a judgement call gets tuned until it stops
     * failing. Exact equality is never intentional, so it never needs arguing about.</p>
     */
    @Test
    public void nothingIsDrawnInTheColourOfWhatItSitsOn() {
        for (String theme : List.of("crystal-dark.css", "crystal-light.css")) {
            Map<String, String> defs = new HashMap<>(definitionsOf(load(THEMES + "base.css")));
            defs.putAll(definitionsOf(load(THEMES + theme)));

            List<String> clashes = new ArrayList<>();
            for (String name : defs.keySet()) {
                String suffix = markSuffixOf(name);
                if (suffix == null) continue;
                String background = name.substring(0, name.length() - suffix.length()) + "-bg";
                String mark = resolveToken(name, defs);
                String surface = resolveToken(background, defs);
                if (mark == null || surface == null) continue;
                // A deliberate no-fill is not a clash: it is the absence of a mark, not a mark that
                // happens to match. Same constant the derivation rule already exempts.
                if (mark.equalsIgnoreCase("#00000000")) continue;
                if (mark.equalsIgnoreCase(surface)) {
                    clashes.add(name + " resolves to " + mark + ", the same as " + background);
                }
            }
            assertTrue(theme + " draws something in the colour of what it sits on:" + NL
                    + String.join(NL, clashes), clashes.isEmpty());
        }
    }

    private static final String NL = System.lineSeparator();
    private static final Pattern VAR_REF_ONE = Pattern.compile("var\\(\\s*(--[\\w-]+)");

    /** The part of a token name that says "this MARKS a surface", or null if it does not. */

    private static String markSuffixOf(String name) {
        for (String suffix : List.of("-fg", "-sep", "-separator", "-border")) {
            if (name.endsWith(suffix)) return suffix;
        }
        return null;
    }

    /** Follows {@code var(--x)} chains to a literal, or null if the name is undefined or cyclic. */

    private static String resolveToken(String name, Map<String, String> defs) {
        String value = defs.get(name);
        for (int hops = 0; value != null && hops < 8; hops++) {
            Matcher ref = VAR_REF_ONE.matcher(value);
            if (!ref.find()) return value.trim();
            value = defs.get(ref.group(1));
        }
        return null;
    }

    private static Map<String, String> definitionsOf(String css) {
        Map<String, String> defs = new HashMap<>();
        Matcher def = VAR_DEF.matcher(stripComments(css));
        while (def.find()) defs.put(def.group(1), def.group(2).trim());
        return defs;
    }

    /** Removes every {@code var(...)} span, nested parens handled, leaving the rest of the text. */
    private static String removeVarSpans(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            int start = css.indexOf("var(", i);
            if (start < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, start);
            int depth = 0;
            int j = start + 3;
            for (; j < css.length(); j++) {
                char c = css.charAt(j);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) break;
            }
            i = j + 1;
        }
        return out.toString();
    }
}
