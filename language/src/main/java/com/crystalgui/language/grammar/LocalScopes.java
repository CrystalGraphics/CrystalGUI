package com.crystalgui.language.grammar;

import com.crystalgui.text.syntax.SyntaxToken;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code locals.scm} — telling a parameter from a field from a plain local, with no engine at all.
 *
 * <h3>Why this is the tier that matters for five of the six languages</h3>
 *
 * <p>A grammar sees an identifier. It cannot tell {@code count} the parameter from {@code count} the
 * field from {@code count} the local, because nothing in the shape of the name says which — that needs
 * the <em>scopes</em>, and resolving them is precisely what an engine does. Java has one and gets these
 * three colours from ECJ through {@code SemanticTokenProvider}. <b>JavaScript, GLSL, CSS, HTML and XML
 * have no engine and never will</b>, so this is the only thing that will ever separate them there — which
 * is why {@code islands-dark.css} names {@code --syntax-variable}, {@code --syntax-variable-parameter}
 * and {@code --syntax-variable-member} and, until now, left all three the same grey.</p>
 *
 * <h3>The capture names are fixed, unlike the other two families</h3>
 *
 * <p>{@code folds.scm} and {@code indents.scm} are editor conventions and we pick a dialect.
 * {@code locals.scm} is part of tree-sitter proper: {@code @local.scope},
 * {@code @local.definition.<kind>} and {@code @local.reference} are the library's own vocabulary, so
 * there is no fork to choose and nothing to normalise.</p>
 *
 * <h3>Where the output lands, and why that is the subtle part</h3>
 *
 * <p>These are <b>grammar-tier</b> tokens. Semantic tokens replace grammar tokens where they overlap —
 * they do not layer, and both names resolve to real colours, so a resolution answer landing above an
 * engine's would read as a colour-scheme bug rather than as an ordering one. So this is merged into the
 * tokenizer's own output, which puts it below anything an engine reports by construction: the editor
 * clears every grammar token under a semantic one before adding any.</p>
 *
 * <h3>What a reference is coloured as</h3>
 *
 * <p>Whatever its <em>definition</em> was. That is the whole mechanism: {@code count} in an expression is
 * a parameter because the nearest enclosing scope that declares it declared it as one. A reference that
 * resolves to nothing is left alone rather than guessed at — the grammar's own answer for it is already
 * on screen, and replacing it with a worse one is the one outcome that is not an improvement.</p>
 */
final class LocalScopes {

    private LocalScopes() {
    }

    /**
     * The definition kinds, mapped to this engine's capture vocabulary.
     *
     * <p>Mapped rather than passed through, for the reason {@code Queries.normalizeCaptureDialect} exists:
     * a scheme is written against one closed vocabulary (§10.1), and {@code local.definition.var} is not
     * in it. The mapping is the interesting half of this file — it is where "a definition of kind X is
     * drawn as Y" is decided, and every entry is a name a scheme already styles.</p>
     */
    private static String captureFor(String definitionKind) {
        switch (definitionKind) {
            case "parameter":
                return "variable.parameter";
            case "field":
                // `variable.member` rather than `property`: the scheme names it that way, and it is the
                // one of these three the plan calls out as grey today.
                return "variable.member";
            case "function":
            case "method":
                return "function";
            case "type":
            case "enum":
            case "struct":
            case "class":
            case "interface":
                return "type";
            case "namespace":
            case "import":
                return "module";
            case "macro":
                // A `#define`'s name IS a compile-time constant, which is how every shader editor draws
                // it and what the GLSL highlight query is already adjusted to say.
                return "constant";
            case "constant":
                return "constant";
            case "var":
            case "":
                return "variable";
            default:
                // A KIND THIS VOCABULARY DOES NOT HAVE IS NOT GUESSED AT. Folding it onto a near-miss
                // would invent a meaning; leaving it null keeps the grammar's own answer, which is what
                // is on screen today.
                return null;
        }
    }

    /**
     * Tokens for every definition and resolved reference overlapping {@code [fromByte, toByte)}.
     *
     * <p><b>The query runs over the whole tree and the output is clipped</b>, which is the opposite of
     * what highlighting does and is forced: a reference in the viewport is defined by a declaration that
     * is usually not, so a query bounded to the visible bytes would resolve almost nothing and would
     * resolve <em>differently</em> depending on where the file was scrolled to. Scoping is a whole-file
     * question or it is not scoping.</p>
     */
    static List<SyntaxToken> tokensIn(TSTree tree, TSQuery query, String[] captureNames,
                                      TSQueryCursor cursor, Utf8Offsets offsets,
                                      int fromByte, int toByte) {
        List<Scope> scopes = new ArrayList<>();
        List<Occurrence> definitions = new ArrayList<>();
        List<Occurrence> references = new ArrayList<>();

        cursor.setByteRange(0, Integer.MAX_VALUE);
        cursor.exec(query, tree.getRootNode());
        TSQueryMatch match = new TSQueryMatch();
        while (cursor.nextMatch(match)) {
            for (TSQueryCapture capture : match.getCaptures()) {
                String name = nameOf(captureNames, capture.getIndex());
                TSNode node = capture.getNode();
                if (name == null || node == null || node.isNull()) continue;

                if (name.equals("local.scope")) {
                    scopes.add(new Scope(node.getStartByte(), node.getEndByte()));
                } else if (name.equals("local.reference")) {
                    references.add(new Occurrence(node, null));
                } else if (name.startsWith("local.definition")) {
                    String kind = name.length() > "local.definition".length()
                            ? name.substring("local.definition".length() + 1) : "";
                    definitions.add(new Occurrence(node, kind));
                }
            }
        }
        if (definitions.isEmpty()) return List.of();

        // INNERMOST FIRST. A name declared in a block shadows one declared in the method around it, so a
        // reference must find the nearest scope that declares it -- which is the narrowest one containing
        // both. Sorting by width once beats searching for the minimum per reference.
        scopes.sort((a, b) -> Integer.compare(a.width(), b.width()));

        // (scope, name) -> kind. A definition belongs to the narrowest scope that contains it, which is
        // the language's own rule and is what makes two `i` loop counters two different names.
        Map<String, String> declared = new HashMap<>();
        for (Occurrence definition : definitions) {
            Scope owner = innermostContaining(scopes, definition.node);
            declared.putIfAbsent(key(owner, textOf(definition.node, offsets)), definition.kind);
        }

        List<SyntaxToken> tokens = new ArrayList<>();
        for (Occurrence definition : definitions) {
            add(tokens, definition.node, captureFor(definition.kind), offsets, fromByte, toByte);
        }
        for (Occurrence reference : references) {
            String name = textOf(reference.node, offsets);
            String kind = resolve(scopes, declared, reference.node, name);
            if (kind == null) continue;
            add(tokens, reference.node, captureFor(kind), offsets, fromByte, toByte);
        }
        return tokens;
    }

    /**
     * The kind a reference resolves to, walking outwards from the scope it sits in.
     *
     * <p>Null when nothing declares it, and that is a real answer rather than a failure: a free name in
     * JavaScript is a global, a bare identifier in GLSL is a builtin, and the grammar has already
     * coloured both. Replacing that with {@code variable} would be losing information to look busy.</p>
     */
    @Nullable
    private static String resolve(List<Scope> scopes, Map<String, String> declared, TSNode at,
                                  String name) {
        int start = at.getStartByte();
        int end = at.getEndByte();
        for (Scope scope : scopes) {
            if (!scope.contains(start, end)) continue;
            String kind = declared.get(key(scope, name));
            if (kind != null) return kind;
        }
        return declared.get(key(null, name));
    }

    @Nullable
    private static Scope innermostContaining(List<Scope> scopes, TSNode node) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        for (Scope scope : scopes) {
            if (scope.contains(start, end)) return scope;
        }
        return null;
    }

    private static String key(@Nullable Scope scope, String name) {
        return (scope == null ? -1 : scope.from) + ":" + (scope == null ? -1 : scope.to) + ":" + name;
    }

    private static void add(List<SyntaxToken> tokens, TSNode node, @Nullable String capture,
                            Utf8Offsets offsets, int fromByte, int toByte) {
        if (capture == null) return;
        int startByte = node.getStartByte();
        int endByte = node.getEndByte();
        // CLIPPED TO WHAT WAS ASKED FOR. The query deliberately ran over the whole file; handing back
        // tokens outside the requested span would have the editor cache colours for rows it did not ask
        // about, and its per-row cache keys on having asked.
        if (endByte <= fromByte || startByte >= toByte) return;
        int start = offsets.toUtf16(startByte);
        int end = offsets.toUtf16(endByte);
        if (end > start) tokens.add(new SyntaxToken(start, end, capture));
    }

    @Nullable
    private static String nameOf(String[] captureNames, int index) {
        return index >= 0 && index < captureNames.length ? captureNames[index] : null;
    }

    /** The identifier a node covers, as UTF-16 text — what a scope map is keyed by. */
    private static String textOf(TSNode node, Utf8Offsets offsets) {
        return offsets.textBetween(node.getStartByte(), node.getEndByte());
    }

    /** One {@code @local.scope} node, as the byte range it covers. */
    private record Scope(int from, int to) {
        boolean contains(int start, int end) {
            return start >= from && end <= to;
        }

        int width() {
            return to - from;
        }
    }

    /** One captured definition or reference. */
    private record Occurrence(TSNode node, @Nullable String kind) {
    }
}
