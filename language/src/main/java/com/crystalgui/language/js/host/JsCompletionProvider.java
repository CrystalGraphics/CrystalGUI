package com.crystalgui.language.js.host;

import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.engine.bridge.LiveScopeSnapshot;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.language.run.ScriptPolicy;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What could go here, in JavaScript — the twin of {@code JavaCompletionProvider}, over the same seam.
 *
 * <h3>Two questions, told apart from the text</h3>
 *
 * <p>After a {@code .} the only sensible answer is the members of what precedes it; in open code it is
 * everything in scope plus the keywords the engine accepts plus the Java package roots. Read from the
 * <b>text</b> rather than from {@code TriggerKind}, for the reason the Java one records: Ctrl+Space
 * pressed straight after a dot arrives as {@code EXPLICIT}, and that is exactly the gesture people use
 * when the automatic popup did not appear.</p>
 *
 * <h3>What is different from Java, and why</h3>
 *
 * <p><b>A trailing dot is re-parsed with a probe name, exactly as Java does.</b> {@code list.} on its own
 * is not a parseable expression in any language: there is no node at that offset and no receiver to
 * resolve, which is why typing one more character makes the list appear and makes this look like a timing
 * problem. So a copy is parsed with an unlikely name inserted at the caret and the receiver is resolved
 * from that. It was left out on the argument that a dynamic language can fall back to the live scope
 * instead — which was wrong, and wrong in the way that hides itself: the fallback answered, so a list
 * appeared, and it was the <em>wrong list</em> for every statically typed receiver in the file.</p>
 *
 * <p><b>Then a receiver that still resolves to nothing opens a list anyway.</b> That case is real here in
 * a way it is not in Java — {@code make().} may have no knowable type at all — so the last resort is the
 * live scope's names, marked partial. The popup opens and narrows rather than staying shut, which is the
 * honest answer for a dynamic language: "I do not know what this is, here is what exists".</p>
 *
 * <p><b>There are no imports.</b> A Java type accepted from the index inserts {@code Java.type("a.b.C")}
 * as its <em>only</em> edit — one primary edit, so one undo step by construction, and nothing added to
 * the top of the file. Java's equivalent needs a second edit for the import and takes care to make the
 * pair atomic; this needs neither.</p>
 */
final class JsCompletionProvider implements CompletionProvider {

    /** Past this the list is truncated and reported incomplete so the session re-asks. */
    private static final int MAX_ITEMS = 300;

    /** The type whose members every other JavaScript object inherits. Java's is {@code java.lang.Object}. */
    private static final String OBJECT_PROTOTYPE = "Object.prototype";

    /** And Java's, for the member lists that come from the Java engine. @see #itemFor */
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    /**
     * The names a script reaches Java through — offered in open code once something has been typed.
     *
     * <p>The same roots {@code RhinoInference} reads a package chain from, because a list that offered a
     * root the resolver would not then recognise is a row that leads nowhere.</p>
     */
    private static final String[] PACKAGE_ROOTS = {"java", "javax", "org", "com", "edu", "net",
            "Packages"};

    private final TextBuffer buffer;
    private final Supplier<Analysis> analysis;
    private final Supplier<LiveScopeSnapshot> liveScope;
    private final Supplier<List<String>> keywords;

    /**
     * What the engine has without anybody declaring it — <b>asked of the engine</b>.
     *
     * <p>This was a 26-name array in this file, which is the one thing {@code RhinoGlobals} exists to
     * avoid: which globals exist differs per band, and the copy had already drifted past {@code Map},
     * {@code Set}, {@code Symbol}, {@code Promise}, {@code Infinity} and {@code Packages}.</p>
     */
    private final Supplier<List<String>> globals;
    @Nullable private final TypeIndex types;

    /** What a script may reach — so a refused type is never offered inside {@code Java.type("…")}. */
    private final Supplier<ScriptPolicy> policy;

    /**
     * Analyses arbitrary text — only for the probe parse, and only when the ordinary analysis could not
     * resolve a receiver, so the common case where a prefix has been typed pays nothing for it.
     */
    @Nullable private final Function<String, Analysis> reanalyse;

    /** Set when an answer drew on a bounded sample, so the session must ask again as the query narrows. */
    private boolean sampled;

    /**
     * A name inserted at the caret so the parser has something to hang the member access on.
     *
     * <p>Deliberately unlikely, and IntelliJ's own trick — its is {@code IntellijIdeaRulezzz}. The point is
     * only that it cannot collide with a real identifier: it is inserted, parsed against, and thrown away,
     * and if it ever matched something real the resolution would be of the user's symbol instead.</p>
     */
    private static final String COMPLETION_PROBE = "CrystalGuiCompletionProbe";

    JsCompletionProvider(TextBuffer buffer, Supplier<Analysis> analysis,
                         Supplier<LiveScopeSnapshot> liveScope, Supplier<List<String>> keywords,
                         Supplier<List<String>> globals,
                         @Nullable TypeIndex types, Supplier<ScriptPolicy> policy,
                         @Nullable Function<String, Analysis> reanalyse) {
        this.buffer = buffer;
        this.analysis = analysis;
        this.liveScope = liveScope;
        this.keywords = keywords;
        this.globals = globals == null ? List::of : globals;
        this.types = types;
        this.policy = policy == null ? ScriptPolicy::allowAll : policy;
        this.reanalyse = reanalyse;
    }

    @Override
    public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
        Analysis current = analysis.get();
        if (current == null) {
            answer.accept(Versioned.of(buffer.version(), CompletionList.EMPTY));
            return;
        }
        sampled = false;

        int wordStart = Math.max(0, request.offset() - request.prefix().length());
        int dot = receiverEndingAt(wordStart);
        List<CompletionItem> items = dot >= 0
                ? memberItems(current, dot, request.offset())
                : openCodeItems(current, request);

        boolean truncated = items.size() > MAX_ITEMS;
        if (truncated) items = items.subList(0, MAX_ITEMS);
        boolean partial = truncated || sampled;
        answer.accept(Versioned.of(current.version(),
                partial ? CompletionList.partial(items) : CompletionList.complete(items)));
    }

    /**
     * The offset of the {@code .} immediately before {@code wordStart}, or {@code -1}.
     *
     * <p>Whitespace is skipped, because a fluent chain broken across lines is still a member access. A
     * {@code ..} is not one — nothing in JavaScript spells that, so the text is mid-edit and open code is
     * the honest answer. And a dot between <b>digits</b> is a decimal point: {@code 1.} is a number being
     * typed, not a receiver, and offering {@code Number.prototype}'s members there would be a popup over
     * a literal.</p>
     */
    private int receiverEndingAt(int wordStart) {
        String text = buffer.toString();
        int at = Math.min(wordStart, text.length()) - 1;
        while (at >= 0 && Character.isWhitespace(text.charAt(at))) at--;
        if (at < 0 || text.charAt(at) != '.') return -1;
        if (at > 0 && text.charAt(at - 1) == '.') return -1;
        if (at > 0 && Character.isDigit(text.charAt(at - 1))) return -1;
        return at;
    }

    // ── Members after a dot ─────────────────────────────────────────────────────────────────────

    private List<CompletionItem> memberItems(Analysis current, int dotOffset, int caret) {
        String text = buffer.toString();

        // A PACKAGE CHAIN IS NOT AN OBJECT. `java.util.` has no members and never resolves to a type, so
        // it fell through to the live-names sample and offered whatever the last run had left. What it
        // wants is the INDEX -- the sub-packages and classes under that prefix -- which is the other half
        // of the row that only ever served `Java.type("`.
        List<CompletionItem> underPackage = packageMembersAt(text, dotOffset);
        if (underPackage != null) return underPackage;
        int nameEnd = dotOffset;
        while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
        // Mid-identifier, so resolveAt lands on the receiver's own name rather than between two tokens.
        SymbolInfo receiver = current.resolveAt(Math.max(0, nameEnd - 1));

        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        TypeRef type = receiver == null ? null : receiver.type();
        if (type != null) {
            for (SymbolInfo member : current.membersOf(type, caret)) {
                if (seen.add(member.name())) items.add(itemFor(member));
            }
        } else {
            // A TRAILING DOT IS NOT A PARSEABLE EXPRESSION, so ask again of text that is. @see the class
            // note -- this is the ordinary case rather than the exceptional one, because the popup opens
            // on the dot itself and nothing has been typed after it yet.
            for (SymbolInfo member : probedMembers(nameEnd, caret)) {
                if (seen.add(member.name())) items.add(itemFor(member));
            }
        }

        // THE LIVE OBJECT'S OWN PROPERTIES. `membersOf` answers for a Java class; a plain JavaScript
        // object has no type to ask, and what it has is the ids the last run saw on it. This is the
        // whole "post-run completion on a live object" criterion, and it is also why a receiver can be
        // useful with no type at all.
        String receiverName = identifierEndingAt(nameEnd);
        LiveScopeSnapshot snapshot = liveScope.get();
        LiveScopeSnapshot.Entry live = snapshot.get(receiverName);
        if (live != null) {
            for (String id : live.ownIds()) {
                if (seen.add(id)) items.add(liveMemberItem(id));
            }
            // THEN WHAT IT INHERITS, marked as inherited so the popup can sink it below the object's own
            // properties -- which is the entire purpose of `inheritedFromObject`, and the reason the
            // engine reports Object.prototype's ids rather than this class listing them.
            if (live.kind() == LiveScopeSnapshot.Kind.OBJECT) {
                for (String id : snapshot.objectPrototypeIds()) {
                    if (seen.add(id)) items.add(inheritedItem(id));
                }
            }
        }

        if (!items.isEmpty()) return items;

        // NOTHING KNOWN ABOUT THE RECEIVER -- and that has to mean NO TYPE, not "a type with nothing on
        // it". A receiver that resolved to `string` or `Array` and came back empty sent us here, so the
        // popup offered every global the last run left AS MEMBERS OF A STRING. With the prototypes
        // readable that is now a real answer; this stays for the case a dynamic language earns honestly,
        // where `make().` has no knowable type at all.
        if (type != null) return items;
        sampled = true;
        for (String name : liveScope.get().names()) {
            if (seen.add(name)) items.add(globalItem(name));
        }
        return items;
    }

    /**
     * What lives directly under the package chain ending at {@code dotOffset}, or null when it is not one.
     *
     * <p>One level only, which is what a dot asks for: {@code java.} offers {@code util} and {@code lang},
     * not fifty thousand qualified names. A class is offered beside them, since {@code java.util.List} is
     * as legal a continuation as {@code java.util.concurrent}.</p>
     */
    @Nullable
    private List<CompletionItem> packageMembersAt(String text, int dotOffset) {
        if (types == null) return null;
        String chain = packageChainEndingAt(text, dotOffset);
        if (chain == null) return null;

        ScriptPolicy current = policy.get();
        if (!current.allowsPackage(chain)) return List.of();

        // THE SIMPLE NAME OF EVERY CLASS UNDER THE PREFIX, and the next segment of every package under it.
        // Asked of the index by the LAST segment, because that is the only thing it is keyed by -- the
        // qualified name is then filtered here, which is one pass over a bounded answer.
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String prefix = chain + ".";
        for (TypeIndex.Entry entry : types.filtered(current::allowsClass).allUnder(prefix).entries()) {
            String rest = entry.qualifiedName().substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                if (seen.add(rest)) {
                    items.add(CompletionItem.builder(rest, SymbolKind.CLASS)
                            .detail(chain).filterText(rest).insertText(rest).build());
                }
            } else {
                String segment = rest.substring(0, dot);
                if (seen.add(segment)) {
                    items.add(CompletionItem.builder(segment, SymbolKind.PACKAGE)
                            .detail(chain).filterText(segment).insertText(segment)
                            .sortText("~" + segment).build());
                }
            }
        }
        // AN INDEX-BACKED LIST IS A SAMPLE, so the session must ask again as the query narrows.
        sampled = true;
        return items;
    }

    /** {@code java.util} in {@code java.util.|}, or null when what precedes the dot is not a package. */
    @Nullable
    private String packageChainEndingAt(String text, int dotOffset) {
        int start = dotOffset;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') start--;
            else break;
        }
        if (start >= dotOffset) return null;
        String chain = text.substring(start, dotOffset);
        if (chain.isEmpty() || chain.endsWith(".")) return null;
        int firstDot = chain.indexOf('.');
        String root = firstDot < 0 ? chain : chain.substring(0, firstDot);
        boolean isRoot = false;
        for (String known : PACKAGE_ROOTS) isRoot |= known.equals(root);
        if (!isRoot) return null;
        // `Packages` IS THE ESCAPE HATCH AND NOT PART OF THE NAME, exactly as the inference tier reads it.
        if ("Packages".equals(root)) {
            chain = firstDot < 0 ? "" : chain.substring(firstDot + 1);
            if (chain.isEmpty()) return null;
        }
        // A SEGMENT STARTING UPPER CASE IS A CLASS, and a class's members are the Java engine's to list --
        // not this method's. `java.util.ArrayList.` must never come back as a package listing.
        int lastDot = chain.lastIndexOf('.');
        String last = lastDot < 0 ? chain : chain.substring(lastDot + 1);
        if (last.isEmpty() || Character.isUpperCase(last.charAt(0))) return null;
        return chain;
    }

    /**
     * The receiver's members, resolved from a copy of the text with a name inserted at the caret.
     *
     * <p>The receiver is to the left of the dot and the insertion to the right of it, so the offset used to
     * resolve needs no adjustment. The probe analysis is closed rather than retained: it describes text the
     * document does not contain, and keeping it would mean two analyses claiming to be about one file.</p>
     */
    private List<SymbolInfo> probedMembers(int nameEnd, int caret) {
        if (reanalyse == null) return List.of();
        String text = buffer.toString();
        int at = Math.max(0, Math.min(caret, text.length()));
        Analysis probed = reanalyse.apply(text.substring(0, at) + COMPLETION_PROBE + text.substring(at));
        if (probed == null) return List.of();
        try {
            SymbolInfo receiver = probed.resolveAt(Math.max(0, nameEnd - 1));
            if (receiver == null || receiver.type() == null) return List.of();
            List<SymbolInfo> members = probed.membersOf(receiver.type(), at);
            // COPIED OUT BEFORE THE ANALYSIS CLOSES. A SymbolInfo is a parent-first record holding nothing
            // of the engine's, so the copy survives; handing back the engine's own list and closing it
            // underneath is the one shape that fails later and somewhere else.
            return members == null ? List.of() : new ArrayList<>(members);
        } finally {
            probed.close();
        }
    }

    /** The identifier ending at {@code end}, or "" — the receiver's own name, for the live lookup. */
    private String identifierEndingAt(int end) {
        String text = buffer.toString();
        int at = Math.min(end, text.length());
        int start = at;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        return start >= at ? "" : text.substring(start, at);
    }

    /** A property the last run saw on this object. */
    private static CompletionItem liveMemberItem(String id) {
        return CompletionItem.builder(id, SymbolKind.PROPERTY)
                .detail("from last run")
                .filterText(id)
                .insertText(id)
                .build();
    }

    /** Something every object has, so the popup can rank it below what this object actually declares. */
    private static CompletionItem inheritedItem(String id) {
        return CompletionItem.builder(id, SymbolKind.PROPERTY)
                .detail(OBJECT_PROTOTYPE)
                .filterText(id)
                .insertText(id)
                .inheritedFromObject(true)
                .sortText("~" + id)
                .build();
    }

    // ── Open code ───────────────────────────────────────────────────────────────────────────────

    /**
     * Everything nameable here: what is in scope, then the last run's globals, then the language's own,
     * then keywords, then the Java package roots — and, inside a {@code Java.type("…")} string, the
     * classpath's type names.
     *
     * <p>Ordered by how likely each group is to be what was meant, and each later group is pushed below
     * the earlier ones with {@code sortText} so the ranking cannot promote a keyword over a variable of
     * the same prefix. The order is the one §6.2 sets out.</p>
     */
    private List<CompletionItem> openCodeItems(Analysis current, Request request) {
        List<String> typeNames = typeNamesFor(request);
        if (typeNames != null) return typeNames.isEmpty() ? List.of() : typeItems(typeNames);

        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // IN SCOPE FIRST, which already includes the live globals -- `symbolsInScope` merges them and
        // reports a name that is both declared and live exactly once.
        for (SymbolInfo symbol : current.symbolsInScope(request.offset())) {
            if (seen.add(symbol.name())) items.add(itemFor(symbol));
        }
        for (String global : globals.get()) {
            if (seen.add(global)) items.add(globalItem(global));
        }
        for (String keyword : keywords.get()) {
            if (!seen.add(keyword)) continue;
            items.add(CompletionItem.builder(keyword, SymbolKind.KEYWORD)
                    // Below every declared name at equal match quality: a keyword is never what you meant
                    // when a variable of the same prefix is in scope.
                    .sortText("~" + keyword)
                    .build());
        }
        for (String root : PACKAGE_ROOTS) {
            if (!seen.add(root)) continue;
            // A ROOT NOTHING IS REACHABLE THROUGH IS NOT A ROOT. Offering `java` under a policy that admits
            // nothing in it is offering a path to an empty list.
            if (!policy.get().allowsPackage(root) && !"Packages".equals(root)) continue;
            items.add(CompletionItem.builder(root, SymbolKind.PACKAGE)
                    .detail("Java packages")
                    .sortText("~~" + root)
                    .build());
        }
        return items;
    }

    /**
     * The type names to offer, or null when the caret is not inside a {@code Java.type("…")} string.
     *
     * <p>Only there, and that is the point: a Java class name is not a thing you can write bare in
     * JavaScript, so offering the index in open code would fill the popup with fifty thousand rows that
     * are all syntax errors where they would land. Inside the string literal every one of them is
     * exactly right, and needs no edit beyond itself.</p>
     */
    @Nullable
    private List<String> typeNamesFor(Request request) {
        String text = buffer.toString();
        // FROM THE CARET, not from the word start: what has been typed inside the quote IS the query, and
        // measuring to the start of the last word answers the empty string for every non-empty prefix --
        // so the index was asked about nothing and the list came back empty.
        String written = insideJavaTypeString(text, request.offset());
        if (written == null || types == null) return written == null ? null : List.of();
        // THE WHOLE STRING SO FAR is the query, not the completion prefix: `java.util.Arr` filters on
        // `Arr` in the session, and the index is asked about the simple name after the last dot.
        String query = written;
        int lastDot = query.lastIndexOf('.');
        String simple = lastDot < 0 ? query : query.substring(lastDot + 1);
        if (simple.isEmpty()) return List.of();
        // THE FILTERED VIEW, so a refused type is absent from the list rather than offered and then
        // refused when the script runs. The index itself stays shared and unfiltered -- the policy belongs
        // to the asker, not to the classpath.
        TypeIndex.Match matched = types.filtered(policy.get()::allowsClass).matching(simple);
        List<String> names = new ArrayList<>(matched.entries().size());
        for (TypeIndex.Entry entry : matched.entries()) names.add(entry.qualifiedName());
        // ANY index-backed list is a SAMPLE -- the cap is on the answer, not on the question -- so the
        // session must ask again as the query narrows rather than filtering this batch forever.
        sampled = true;
        return names;
    }

    /**
     * What has been typed inside a {@code Java.type("} string at {@code offset}, or null.
     *
     * <p>Read backwards from the caret to the opening quote and then checked for the call in front of it,
     * which is what makes this cheap: the overwhelming majority of positions fail on the first character
     * examined.</p>
     */
    @Nullable
    private static String insideJavaTypeString(String text, int offset) {
        int at = Math.min(offset, text.length());
        int start = at;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '"' || c == '\'') break;
            // A STRING DOES NOT SPAN A LINE, so a newline means the quote we found would have been on
            // another one -- and scanning to the top of the file for an unclosed quote is how a linear
            // reader becomes quadratic.
            if (c == '\n') return null;
            start--;
        }
        if (start == 0) return null;
        int quote = start - 1;
        String before = text.substring(0, quote).stripTrailing();
        if (!before.endsWith("(")) return null;
        before = before.substring(0, before.length() - 1).stripTrailing();
        return before.endsWith("Java.type") ? text.substring(start, at) : null;
    }

    /**
     * A Java class, inserted as the whole qualified name.
     *
     * <p>Inside the string literal, so accepting it needs no import, no second edit and no rewriting of
     * anything above — which is the one place JavaScript's interop is <em>simpler</em> than Java's.</p>
     */
    private List<CompletionItem> typeItems(List<String> qualifiedNames) {
        List<CompletionItem> items = new ArrayList<>(qualifiedNames.size());
        for (String qualified : qualifiedNames) {
            int lastDot = qualified.lastIndexOf('.');
            String simple = lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
            items.add(CompletionItem.builder(simple, SymbolKind.CLASS)
                    .detail(lastDot < 0 ? "" : qualified.substring(0, lastDot))
                    .filterText(simple)
                    // THE QUALIFIED NAME, because the caret is inside `Java.type("` and the class has to
                    // be named in full there -- the simple name alone is what the LABEL shows.
                    .insertText(qualified)
                    .build());
        }
        return items;
    }

    private static CompletionItem globalItem(String name) {
        return CompletionItem.builder(name, SymbolKind.PROPERTY)
                .sortText("~" + name)
                .build();
    }

    /**
     * One row from one symbol — through {@link CompletionItem#builderFrom}, never a second builder.
     *
     * <p>{@code inheritedFromObject} is set from {@code Object.prototype}, which is JavaScript's answer to
     * the question {@code java.lang.Object} answers for Java. That the seam does not know which type is a
     * language's root is the whole reason {@code builderFrom} exists rather than a shared constant.</p>
     */
    private static CompletionItem itemFor(SymbolInfo symbol) {
        // THE BRACKETS ARE THE SHARED BUILDER'S JOB, not this class's. It was written here first and was
        // already worse: `builderFrom` puts the caret BETWEEN the brackets when there is an argument to
        // type and AFTER them when there is not, and the copy here always wrote `name($0)` — so accepting
        // a no-argument method left the caret inside `()` with nothing to put there. One converter means a
        // rule like that is decided once instead of per language.
        return CompletionItem.builderFrom(symbol)
                // BOTH ROOTS, because a member list reached from JavaScript is routinely JAVA's. Marking
                // only `Object.prototype` left `toString`, `hashCode`, `wait` and `notify` at full rank in
                // every `new java.util.ArrayList().` list -- the exact de-emphasis this flag exists for,
                // applied to the language whose objects are less often the ones being completed.
                .inheritedFromObject(OBJECT_PROTOTYPE.equals(symbol.container())
                        || JAVA_LANG_OBJECT.equals(symbol.container()))
                .build();
    }

    /**
     * Documentation for one row.
     *
     * <p>Nothing to add: what a JavaScript symbol's documentation is, JSDoc already said, and
     * {@code SymbolInfo.documentation} carries it across the bridge with the item — so there is no second,
     * expensive lookup of the kind Java's javadoc-from-source would need. Returning the item unchanged is
     * the honest answer and is what {@code needsResolution()} expects.</p>
     */
    @Override
    public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
        answer.accept(item);
    }
}
