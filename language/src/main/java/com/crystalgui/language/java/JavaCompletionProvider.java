package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.Change;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
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
final class JavaCompletionProvider implements CompletionProvider {

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

    private final TextBuffer buffer;
    private final Supplier<SourceAnalyzer.Analysis> analysis;
    private final TypeIndex types;

    /** Set by {@link #openCodeItems} whenever the answer drew on the type index. */
    private boolean typesSampled;

    /** Set by {@link #memberItems} when the receiver did not resolve — see the note there. */
    private boolean unresolvedReceiver;

    JavaCompletionProvider(TextBuffer buffer, Supplier<SourceAnalyzer.Analysis> analysis, TypeIndex types) {
        this.buffer = buffer;
        this.analysis = analysis;
        this.types = types;
    }

    @Override
    public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
        SourceAnalyzer.Analysis current = analysis.get();
        if (current == null) {
            answer.accept(Versioned.of(buffer.version(), CompletionList.EMPTY));
            return;
        }

        int wordStart = Math.max(0, request.offset() - request.prefix().length());
        List<CompletionItem> items = receiverEndingAt(wordStart) >= 0
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
    private List<CompletionItem> memberItems(SourceAnalyzer.Analysis current, int dotOffset, int caret) {
        int nameEnd = dotOffset;
        String text = buffer.toString();
        while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
        // Mid-identifier, so resolveAt lands on the receiver's own name rather than between tokens.
        int probe = Math.max(0, nameEnd - 1);

        SymbolInfo receiver = current.resolveAt(probe);
        TypeRef type = receiver == null ? null : receiver.type();
        if (type == null) {
            // COULD NOT RESOLVE, and that is usually a matter of timing rather than of the code. The popup
            // opens on the keystroke; the analysis behind it is up to a debounce old and was taken from
            // text without this dot in it. Reporting an empty COMPLETE list caches that failure for the
            // life of the session, which is what left a popup with nothing in it until something was typed.
            unresolvedReceiver = true;
            return List.of();
        }

        // STATIC ACCESS OFFERS STATIC MEMBERS. `Foo.` is a type name, and an instance method reached
        // through one does not compile -- so offering it is offering a mistake, which is worse than
        // offering nothing because the list looks authoritative and the error arrives after acceptance.
        // The same rule membersOf already applies to accessibility.
        boolean staticAccess = receiver.kind() != null && isTypeKind(receiver.kind());

        List<CompletionItem> items = new ArrayList<>();
        for (SymbolInfo member : current.membersOf(type, caret)) {
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
                return true;
            default:
                return false;
        }
    }

    /** Locals, parameters, fields, then keywords, then types that would need an import. */
    private List<CompletionItem> openCodeItems(SourceAnalyzer.Analysis current, Request request) {
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
        return CompletionItem.from(symbol);
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
        return CompletionItem.builder(type.simpleName(), type.kind())
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
