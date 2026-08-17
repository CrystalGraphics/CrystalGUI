package com.crystalgui.language.js.rhino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The keywords this band's Rhino actually accepts — offered in open code, and nowhere else.
 *
 * <h3>Why the list is measured rather than written down</h3>
 *
 * <p>The grammar parses modern JavaScript and the <em>engine</em> is what refuses it, differently per band:
 * {@code class}, {@code import}/{@code export} and {@code async}/{@code await} are refused by both Rhinos we
 * ship, while {@code ?.} and {@code ??} work on Java 11+ and not on Java 8. Offering a keyword the engine
 * will refuse is worse than omitting it — the completion list is the most authoritative-looking surface in
 * the editor, and a row there is a promise that accepting it produces something that runs.</p>
 *
 * <p>So each keyword whose support has <em>ever</em> depended on the version is put to the parser once and
 * kept if it survives. The rest — {@code var}, {@code function}, {@code if}, {@code return} and the other
 * ES3 spellings — are unconditional: no Rhino has lacked them, and probing forty of them to learn that
 * would be ceremony. Measured once per process and cached, because the answer cannot change while the
 * process lives.</p>
 *
 * <h3>What is deliberately not here</h3>
 *
 * <p>Operators. {@code ?.}, {@code ??} and {@code **} are the interesting band divergences and none of
 * them is something a completion list offers — you do not complete punctuation. They belong to the
 * compatibility band (10.3b), which warns about them in source you have already written.</p>
 */
final class JsKeywords {

    /**
     * Accepted by every Rhino, so never probed.
     *
     * <p>Statement and declaration starters only, which is where a completion popup is actually open.
     * {@code case} and {@code default} are in because a {@code switch} body is ordinary code to type in;
     * {@code get}/{@code set} are out because they are contextual and offering them everywhere would put
     * two rows nobody wants above every real name beginning with g or s.</p>
     */
    private static final String[] UNCONDITIONAL = {
            "break", "case", "catch", "continue", "debugger", "default", "delete", "do", "else",
            "false", "finally", "for", "function", "if", "in", "instanceof", "new", "null", "return",
            "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
    };

    /**
     * Keyword → the smallest program that uses it, for the ones a band may refuse.
     *
     * <p>Each snippet is the shortest thing that <em>only</em> parses if the keyword is supported, so a
     * failure means the keyword and not the surrounding syntax. Insertion-ordered so the offered list is
     * stable between runs — a completion list whose rows move between sessions is its own bug report.</p>
     */
    private static final Map<String, String> PROBES = new LinkedHashMap<>();

    static {
        PROBES.put("let", "let probeA = 1;");
        PROBES.put("const", "const probeB = 1;");
        PROBES.put("class", "class ProbeC {}");
        PROBES.put("extends", "class ProbeD extends Object {}");
        PROBES.put("super", "class ProbeE extends Object { constructor() { super(); } }");
        PROBES.put("yield", "function* probeF() { yield 1; }");
        PROBES.put("async", "async function probeG() { return 1; }");
        PROBES.put("await", "async function probeH() { await 1; }");
        PROBES.put("import", "import { probeI } from 'x';");
        PROBES.put("export", "export var probeJ = 1;");
        PROBES.put("of", "for (var probeL of []) {}");
        // NOT A KEYWORD, and it is here anyway. A template literal is punctuation, so it can never be
        // OFFERED in a completion list -- but the "change to a template literal" intention must not be
        // offered on a band that would refuse the result, and this is the one place that measures what the
        // engine takes. @see #NOT_OFFERED, which is what keeps it out of the popup.
        PROBES.put("template", "var probeM = `x`;");
    }

    private JsKeywords() {
    }

    /** Measured once; the answer is a property of the loaded engine and cannot change. */
    private static volatile List<String> cached;

    /**
     * Every keyword this engine accepts, in a stable order.
     *
     * @param parses whether the engine parses a snippet without error — the engine's own answer, since
     *               this is a question about the engine and not about the language
     */
    /**
     * Measured, but not a keyword — so never a completion row.
     *
     * <p>The two lists have different consumers and one of them is a promise to the user: a row in the
     * popup says "accepting this produces something that runs", and {@code template} is not something that
     * can be accepted at all. The fix catalog asks the other question — "would this band take it" — and
     * needs the full set.</p>
     */
    private static final Set<String> NOT_OFFERED = Set.of("template");

    /** Everything the engine takes, keyword or construct — what the fix catalog gates on. */
    static List<String> measuredBy(Predicate<String> parses) {
        return supportedBy(parses);
    }

    /** What a completion list may offer: the measured set, minus what is not a keyword. */
    static List<String> offerableBy(Predicate<String> parses) {
        List<String> offerable = new ArrayList<>(supportedBy(parses));
        offerable.removeAll(NOT_OFFERED);
        return Collections.unmodifiableList(offerable);
    }

    private static List<String> supportedBy(Predicate<String> parses) {
        List<String> known = cached;
        if (known != null) return known;
        synchronized (JsKeywords.class) {
            if (cached != null) return cached;
            List<String> supported = new ArrayList<>(UNCONDITIONAL.length + PROBES.size());
            Collections.addAll(supported, UNCONDITIONAL);
            for (Map.Entry<String, String> probe : PROBES.entrySet()) {
                if (parses == null || parses.test(probe.getValue())) supported.add(probe.getKey());
            }
            Collections.sort(supported);
            cached = Collections.unmodifiableList(supported);
            return cached;
        }
    }

    /** For a test that needs the measurement redone against a different engine. */
    static void forgetMeasurement() {
        synchronized (JsKeywords.class) {
            cached = null;
        }
    }
}
