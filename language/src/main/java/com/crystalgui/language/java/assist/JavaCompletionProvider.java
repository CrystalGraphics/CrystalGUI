package com.crystalgui.language.java.assist;

import com.crystalgui.language.engine.bridge.Analysis;
import com.crystalgui.language.java.classpath.TypeIndex;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.Change;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.Versioned;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What could go here, answered from the last ECJ analysis.
 *
 * <h3>Two questions, and telling them apart is the whole job</h3>
 *
 * <p>After a {@code .} the only sensible answer is the members of what precedes it. In open code it is
 * everything in scope, plus keywords, plus types that are not imported yet. §18.1 names this as the reason
 * {@code TriggerKind} exists, and getting it wrong is visible in both directions: a member list flooded with
 * locals, or an empty popup where a receiver's methods should be.</p>
 *
 * <p>The distinction is drawn from the <b>text</b>, not from the trigger kind. Ctrl+Space pressed straight
 * after a dot must still answer members, and it arrives as {@link TriggerKind#EXPLICIT} — so reading the
 * kind would give the wrong list for the one gesture people use when the automatic popup did not appear.</p>
 *
 * <h3>Synchronous, and the callback shape is still right</h3>
 *
 * <p>The analysis is already in memory; what remains is a tree walk. An asynchronous hop would add a frame
 * of latency to a keystroke for no work saved. The contract stays a callback because the <em>contract</em>
 * is what callers must not assume about — the day this waits on a compile that has not finished, no call
 * site changes. Same reasoning {@code AnalysisResolver} records.</p>
 */
public final class JavaCompletionProvider implements CompletionProvider {

    /**
     * Offered in open code with no receiver.
     *
     * <p>A short list on purpose. These are the ones that begin a statement or a declaration, which is where
     * a completion popup is actually open; {@code extends}, {@code implements} and {@code throws} are
     * omitted because offering them everywhere is worse than not offering them at all — they are only ever
     * valid in positions this cannot yet detect.</p>
     */
    private static final String[] STATEMENT_KEYWORDS = {
            "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue",
            "default", "do", "double", "else", "enum", "final", "finally", "float", "for", "if",
            "instanceof", "int", "interface", "long", "new", "private", "protected", "public",
            "record", "return", "short", "static", "super", "switch", "synchronized", "this",
            "throw", "try", "var", "void", "while",
    };

    /** Past this the list is truncated and reported {@link CompletionList#incomplete} so the session re-asks. */
    private static final int MAX_ITEMS = 300;

    /**
     * A name inserted at the caret so the parser has something to hang the expression on.
     *
     * <p>Deliberately unlikely, and IntelliJ's own trick — its is {@code IntellijIdeaRulezzz}. The point is
     * only that it cannot collide with a real identifier in the file: it is inserted, parsed against, and
     * thrown away, and if it ever matched something real the resolution would be of the user's symbol
     * rather than of the expression being completed.</p>
     */
    private static final String COMPLETION_PROBE = "CrystalGuiCompletionProbe";

    private final TextBuffer buffer;
    private final Supplier<Analysis> analysis;
    private final TypeIndex types;

    /**
     * Analyses arbitrary text — used only for the probe parse below, and only when the ordinary analysis
     * could not resolve a receiver.
     */
    private final java.util.function.Function<String, Analysis> reanalyse;

    /** Set by {@link #openCodeItems} whenever the answer drew on the type index. */
    private boolean typesSampled;

    /** Set by {@link #memberItems} when the receiver did not resolve — see the note there. */
    private boolean unresolvedReceiver;

    public JavaCompletionProvider(TextBuffer buffer, Supplier<Analysis> analysis, TypeIndex types,
                           java.util.function.Function<String, Analysis> reanalyse) {
        this.buffer = buffer;
        this.analysis = analysis;
        this.types = types;
        this.reanalyse = reanalyse;
    }

    @Override
    public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
        Analysis current = analysis.get();
        if (current == null) {
            answer.accept(Versioned.of(buffer.version(), CompletionList.EMPTY));
            return;
        }

        int wordStart = Math.max(0, request.offset() - request.prefix().length());
        // AN IMPORT IS A QUALIFIED NAME, so it cannot be completed on a simple one. The ordinary list
        // matches `request.prefix()` against simple names, which for `import net.mine` is `mine` and
        // matches nothing -- the popup opened with no rows on every classpath, Minecraft or not.
        List<CompletionItem> items = importPrefixAt(request.offset()) != null
                ? importItems(importPrefixAt(request.offset()))
                : receiverEndingAt(wordStart) >= 0
                ? memberItems(current, receiverEndingAt(wordStart), request.offset())
                : openCodeItems(current, request);

        boolean truncated = items.size() > MAX_ITEMS;
        if (truncated) items = items.subList(0, MAX_ITEMS);
        // Either kind of truncation makes this a partial answer: too many items to send, or an index that
        // had more to give. Both mean "ask me again when you know more".
        boolean partial = truncated || typesSampled || unresolvedReceiver;
        typesSampled = false;
        unresolvedReceiver = false;
        answer.accept(Versioned.of(current.version(),
                partial ? CompletionList.partial(items) : CompletionList.complete(items)));
    }

    /**
     * The offset of the {@code .} immediately before {@code wordStart}, or {@code -1}.
     *
     * <p>Whitespace is skipped, because {@code foo.} followed by a newline and an indent is still a member
     * access and is exactly how a fluent chain is written. A {@code ..} is not — that is a range operator in
     * no Java there is, so it means the text is mid-edit and the honest answer is open code.</p>
     */
    private int receiverEndingAt(int wordStart) {
        String text = buffer.toString();
        int at = Math.min(wordStart, text.length()) - 1;
        while (at >= 0 && Character.isWhitespace(text.charAt(at))) at--;
        if (at < 0 || text.charAt(at) != '.') return -1;
        if (at > 0 && text.charAt(at - 1) == '.') return -1;
        return at;
    }

    /**
     * Members of whatever precedes the dot.
     *
     * <p>Resolved by asking the analysis about the identifier before the dot, then asking that symbol's
     * <em>type</em> for its members. A receiver that is itself a call ({@code list.get(0).}) resolves
     * through the same path, because {@code resolveAt} lands on the method name and its type is the return
     * type — which is why this needs no expression parser of its own.</p>
     */
    private List<CompletionItem> memberItems(Analysis current, int dotOffset, int caret) {
        int nameEnd = dotOffset;
        String text = buffer.toString();
        while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
        // Mid-identifier, so resolveAt lands on the receiver's own name rather than between tokens.
        int probe = Math.max(0, nameEnd - 1);

        SymbolInfo receiver = current.resolveAt(probe);
        if (receiver == null || receiver.type() == null) {
            // A TRAILING DOT WITH NOTHING AFTER IT IS NOT A PARSEABLE EXPRESSION.
            //
            // `ctx.` on its own gives recovery nothing to hang the member access on, so there is no node at
            // that offset and no binding -- which is why typing one more character made the list appear and
            // made this look like a timing problem. It is not: the same text parses the same way however
            // long you wait.
            //
            // So parse a copy with a name inserted at the caret. That is IntelliJ's own answer (its probe is
            // literally called IntellijIdeaRulezzz) and it is the only way to ask "what could go HERE" of a
            // parser that answers questions about complete expressions.
            //
            // Only on failure, so the ordinary path -- where a prefix has already been typed -- pays nothing
            // for it.
            return probedMemberItems(caret);
        }
        List<CompletionItem> direct = membersFrom(current, receiver, caret);
        // AND IF THE RECEIVER RESOLVED TO SOMETHING WITH NOTHING ON IT, ask the probe anyway. A type that
        // resolved from a tree the parser had to recover can be plausible and wrong -- the trailing dot is
        // exactly the state where that happens -- and an empty member list is the one outcome that is never
        // a useful answer. Costs one parse, only when the ordinary path produced nothing.
        return direct.isEmpty() ? probedMemberItems(caret) : direct;
    }

    /**
     * Re-parses with {@link #COMPLETION_PROBE} at the caret, and answers from that.
     *
     * <p>The probe offset is <em>before</em> the insertion point, so it needs no adjustment — the receiver
     * is to the left of the dot and the insertion is to the right of it.</p>
     *
     * <p>The analysis is closed here rather than retained: it describes text the document does not contain,
     * and keeping it would mean two analyses claiming to be about one file. Its cost is one parse, paid only
     * when the ordinary one could not answer.</p>
     */
    private List<CompletionItem> probedMemberItems(int caret) {
        if (reanalyse == null) {
            unresolvedReceiver = true;
            return List.of();
        }
        String text = buffer.toString();
        int at = Math.max(0, Math.min(caret, text.length()));
        Analysis probed =
                reanalyse.apply(text.substring(0, at) + COMPLETION_PROBE + text.substring(at));
        if (probed == null) {
            unresolvedReceiver = true;
            return List.of();
        }
        try {
            int dot = receiverEndingAt(at);
            if (dot < 0) {
                unresolvedReceiver = true;
                return List.of();
            }
            int nameEnd = dot;
            while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
            SymbolInfo receiver = probed.resolveAt(Math.max(0, nameEnd - 1));
            if (receiver == null || receiver.type() == null) {
                unresolvedReceiver = true;
                return List.of();
            }
            return membersFrom(probed, receiver, at);
        } finally {
            probed.close();
        }
    }

    /**
     * The members of {@code receiver}, filtered by how it is being reached.
     *
     * <p><b>Static access offers static members.</b> {@code Foo.} is a type name, and an instance method
     * reached through one does not compile — so offering it is offering a mistake, which is worse than
     * offering nothing because the list looks authoritative and the error arrives after acceptance. The
     * same rule {@code membersOf} already applies to accessibility.</p>
     */
    private List<CompletionItem> membersFrom(Analysis from, SymbolInfo receiver, int caret) {
        boolean staticAccess = receiver.kind() != null && isTypeKind(receiver.kind());
        List<CompletionItem> items = new ArrayList<>();
        for (SymbolInfo member : from.membersOf(receiver.type(), caret)) {
            if (staticAccess && !member.is(SymbolModifier.STATIC)) continue;
            items.add(itemFor(member));
        }
        return items;
    }

    /**
     * Whether {@code kind} names a TYPE, which is what makes a member access static.
     *
     * <p>Asked of the kind the engine reported rather than of the text: {@code Foo.} and {@code foo.} are
     * told apart by what {@code Foo} resolved to, not by its first letter. A convention-based test would be
     * wrong for every lower-case type and every upper-case constant.</p>
     */
    private static boolean isTypeKind(SymbolKind kind) {
        switch (kind) {
            case CLASS:
            case INTERFACE:
            case ENUM:
            case RECORD:
            case ANNOTATION:
            case TYPE_PARAMETER:
            case EXCEPTION:
                return true;
            default:
                return false;
        }
    }

    /** Locals, parameters, fields, then keywords, then types that would need an import. */
    private List<CompletionItem> openCodeItems(Analysis current, Request request) {
        List<CompletionItem> items = new ArrayList<>();
        for (SymbolInfo symbol : current.symbolsInScope(request.offset())) items.add(itemFor(symbol));

        for (String keyword : STATEMENT_KEYWORDS) {
            items.add(CompletionItem.builder(keyword, SymbolKind.KEYWORD)
                    // sortText puts every keyword below every declared name at equal match quality. A
                    // keyword is never what you meant when a variable of the same prefix is in scope.
                    .sortText("~" + keyword)
                    .build());
        }

        // UNIMPORTED TYPES LAST, and only once something has been typed. With an empty prefix the index is
        // thousands of names and would bury the handful actually in scope -- IntelliJ gates them the same
        // way, behind a second Ctrl+Space, for the same reason.
        if (!request.prefix().isEmpty()) {
            TypeIndex.Match matched = types.matching(request.prefix());
            for (TypeIndex.Entry type : matched.entries()) items.add(unimportedTypeItem(type));
            // ANY index-backed list is a SAMPLE. matching() caps what it returns whether or not it noticed
            // running out, so "it gave me thirty" does not mean thirty is all there is at a narrower query
            // -- the cap is on the ANSWER, not on the question. Reporting complete here let the session
            // filter a forty-name sample locally for the rest of the session.
            //
            // The cost is one index scan per keystroke, which is a linear pass with no allocation over
            // names already in memory. That is the right price for a list that is actually about what was
            // typed.
            typesSampled = true;
            // THE INDEX HAD MORE, so this list is a sample rather than the answer -- and the session must
            // ask again as the query narrows. Without it the popup, which now opens on the FIRST character
            // typed, asked once for "C", kept the forty shortest names starting with C, and filtered those
            // locally forever: typing CgTex found nothing, because CgTexture was never in the forty.
        }
        return items;
    }

    /**
     * One row from one symbol — through {@link CompletionItem#from}, not a second builder here.
     *
     * <p>It was a second builder, and the duplication was already drifting: this one dropped the symbol's
     * modifiers, which is exactly what the icon's static/abstract axis needs. One converter means a field
     * added to {@link SymbolInfo} reaches every provider rather than the one somebody remembered.</p>
     */
    private static CompletionItem itemFor(SymbolInfo symbol) {
        // THE ONE THING THE SEAM CANNOT KNOW: which type is the language's root. `java.lang.Object` is
        // Java's answer and lives here, not in core -- JavaScript's is `Object.prototype`, and a shared
        // constant would have been one language's fact written into every language's seam.
        return CompletionItem.builderFrom(symbol)
                .inheritedFromObject(OBJECT.equals(symbol.container()))
                .build();
    }

    /** The type whose members every other Java type inherits. */
    private static final String OBJECT = "java.lang.Object";

    /**
     * What has been typed after {@code import} on this line, or null when the caret is not in one.
     *
     * <p>Read off the text rather than the tree, deliberately: an import being typed is <b>incomplete</b>
     * — {@code import net.mine} has no semicolon and no resolvable name — so the parse either recovers it
     * as something else or drops it, and asking the AST what it is answers about a node that reflects the
     * last keystroke that happened to parse. The line is what the author can see.</p>
     *
     * <p>Refuses once a {@code ;} has been passed, so the caret after a finished import is ordinary code
     * again, and refuses a static import, whose tail is a member rather than a type.</p>
     */
    private String importPrefixAt(int offset) {
        // The LINE, through the buffer's own row lookup rather than a scan back through the document —
        // the same answer at a fraction of the cost on a file of any size.
        TextPoint caret = buffer.offsetToPoint(offset);
        String row = buffer.line(caret.row());
        String line = row.substring(0, Math.min(Math.max(caret.column(), 0), row.length()));

        String trimmed = line.trim();
        if (!trimmed.startsWith("import")) return null;
        String tail = trimmed.substring("import".length());
        if (tail.isEmpty() || !Character.isWhitespace(tail.charAt(0))) return null;
        tail = tail.trim();
        // A finished import is not a context any more, and `import static` completes members.
        if (tail.indexOf(';') >= 0 || tail.startsWith("static")) return null;
        return tail;
    }

    /**
     * Packages and types under a qualified prefix — what an import statement can actually take.
     *
     * <h3>Both kinds, because a package is most of what you type</h3>
     *
     * <p>{@code net.mine} is three packages deep before it is a type, and a list that offered only types
     * would show every class under {@code net.minecraft} at the first keystroke and nothing that helps
     * you get there. So a sub-package is a row of its own: accepting it extends the prefix by one segment
     * and leaves the caret ready for the next, which is how every IDE completes a qualified name.</p>
     *
     * <p>Packages are derived from the entries rather than held separately — a package exists exactly
     * when something is in it, so a second structure could only disagree with the first.</p>
     */
    private List<CompletionItem> importItems(String typedPrefix) {
        List<CompletionItem> items = new ArrayList<>();
        if (typedPrefix.isEmpty()) return items;

        int lastDot = typedPrefix.lastIndexOf('.');
        String parent = lastDot < 0 ? "" : typedPrefix.substring(0, lastDot);
        String partial = lastDot < 0 ? typedPrefix : typedPrefix.substring(lastDot + 1);

        // ONE QUERY, and it does the splitting. Deriving packages from a capped list of ENTRIES truncates
        // by alphabet: net.minecraft holds ~4,300 classes, so forty of them are all inside
        // net.minecraft.client and most of the package list never appears. @see TypeIndex#childrenOf
        TypeIndex.Children matched = types.childrenOf(parent, partial);
        for (TypeIndex.Entry entry : matched.types()) items.add(importTypeItem(entry));
        for (String segment : matched.packages()) {
            items.add(CompletionItem.builder(segment, SymbolKind.PACKAGE)
                    .detail(parent.isEmpty() ? segment : parent + "." + segment)
                    .filterText(segment)
                    // ABOVE the types, because a package is a step and a type is a destination: at
                    // `net.mine` the answer is almost always `minecraft`, never one of its thousand classes.
                    .sortText(" " + segment)
                    // NO EXPLICIT RANGE. The session replaces the word under the caret, and a dot is not
                    // a word character -- so inserting `minecraft` over a typed `mine` leaves
                    // `net.minecraft`, which is exactly the step this row means.
                    .insertText(segment)
                    .build());
        }
        typesSampled = matched.truncated();
        return items;
    }

    /** A type row inside an import — the qualified name is already being written, so no import edit. */
    private CompletionItem importTypeItem(TypeIndex.Entry type) {
        TypeIndex.Kind kind = types.kindOf(type);
        return CompletionItem.builder(type.simpleName(), kind.kind())
                .modifiers(kind.isAbstract() ? java.util.Set.of(SymbolModifier.ABSTRACT) : java.util.Set.of())
                .detail(type.packageName())
                .filterText(type.simpleName())
                .sortText("~" + type.simpleName())
                .insertText(type.simpleName())
                .build();
    }

    /**
     * A type that is not imported yet — and the import that accepting it must bring.
     *
     * <p>The import is an {@link CompletionItem#additionalTextEdits() additional edit}, which is what makes
     * accepting it <b>one</b> undo step: the name and its import go together on Ctrl+Z. Two steps for one
     * keystroke is the behaviour every editor that has this feature is criticised for.</p>
     */
    private CompletionItem unimportedTypeItem(TypeIndex.Entry type) {
        Change importEdit = importEditFor(type.qualifiedName());
        // WHAT IT IS, read from the class file rather than assumed. Every row used to draw as a class,
        // because the path a type lives at says nothing about whether it is an interface, an enum, an
        // annotation or a throwable -- that is in the access flags.
        TypeIndex.Kind kind = types.kindOf(type);
        return CompletionItem.builder(type.simpleName(), kind.kind())
                .modifiers(kind.isAbstract() ? java.util.Set.of(SymbolModifier.ABSTRACT) : java.util.Set.of())
                .detail(type.packageName())
                .filterText(type.simpleName())
                // Below everything in scope at equal match quality: it costs an import to accept, so a name
                // already available should always win a tie.
                .sortText("~~" + type.simpleName())
                .insertText(type.simpleName())
                .additionalTextEdits(importEdit == null ? new Change[0] : new Change[] { importEdit })
                .build();
    }

    /**
     * Where an {@code import} for {@code qualifiedName} goes, or null when it is already there.
     *
     * <p>After the last existing import, or after the package declaration, or at the very top — in that
     * order, which is the order that keeps the file compiling whatever it currently contains. Inserted as a
     * whole line including its own newline, so the edit is a pure insertion and cannot disturb what is
     * already on either side of it.</p>
     */
    private Change importEditFor(String qualifiedName) {
        String text = buffer.toString();
        String statement = "import " + qualifiedName + ";";
        if (text.contains(statement)) return null;
        // java.lang is imported implicitly, so writing one is noise the compiler will not thank you for.
        if (qualifiedName.startsWith("java.lang.")
                && qualifiedName.indexOf('.', "java.lang.".length()) < 0) {
            return null;
        }

        int insertAt = 0;
        int lastImport = text.lastIndexOf("\nimport ");
        if (lastImport >= 0) {
            int endOfLine = text.indexOf('\n', lastImport + 1);
            insertAt = endOfLine < 0 ? text.length() : endOfLine + 1;
        } else {
            int packageAt = text.indexOf("package ");
            if (packageAt >= 0) {
                int endOfLine = text.indexOf('\n', packageAt);
                // A blank line after the package declaration, because there is one there already and
                // landing the import directly against it reads as part of the declaration.
                insertAt = endOfLine < 0 ? text.length() : endOfLine + 1;
                return new Change(insertAt, insertAt, "\n" + statement + "\n");
            }
        }
        return new Change(insertAt, insertAt, statement + "\n");
    }

    /**
     * Documentation for one row.
     *
     * <p>Nothing to add yet: {@code SymbolInfo.documentation} is null across the bridge because JDT only
     * attaches javadoc when the AST was built with source for the declaring type, which is true for this
     * file and false for everything on the classpath. Returning the item unchanged is the honest answer and
     * is what {@link CompletionItem#needsResolution()} expects — the pane shows nothing rather than
     * "loading" forever.</p>
     */
    @Override
    public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
        answer.accept(item);
    }
}
