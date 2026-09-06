package com.crystalgui.document;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.text.lang.LanguageServices;

import org.jetbrains.annotations.Nullable;

/**
 * A text document — a {@link TextBuffer}, plus the language machinery that belongs to it.
 *
 * <p>The buffer is the document: the rope, a monotonic version, the line ending, the charset, the undo
 * stack, the decorations, the diagnostics and a change signal are all already there. This adds the
 * language, its tokenizer and its services, and nothing else.</p>
 *
 * <h3>The language services are the MODEL's, not a view's</h3>
 *
 * <p>So two panes onto one file share one parse tree, and a document with no tab still analyses —
 * which is the state the Problems panel, a background compile and Go to Definition all want it in. The
 * same boundary the buffer already draws for diagnostics and decorations: they describe a
 * <b>document</b>.</p>
 */
public final class TextDocumentModel implements DocumentModel {

    private final TextBuffer buffer;
    private final Signal.Action onChanged = new Signal.Action();
    private final Connection bufferSubscription;

    @Nullable
    private Language language;
    @Nullable
    private SyntaxTokenizer tokenizer;
    @Nullable
    private LanguageServices services;

    public TextDocumentModel() {
        this(new TextBuffer());
    }

    public TextDocumentModel(TextBuffer buffer) {
        this.buffer = buffer == null ? new TextBuffer() : buffer;
        // Adapted rather than re-announced: the buffer is the source, and a second signal kept in step
        // by hand is the copy that eventually disagrees. The payload is dropped because a DocumentModel
        // says only THAT it moved -- a consumer that wants the ChangeSet asks the buffer, which is the
        // one that has it.
        this.bufferSubscription = this.buffer.onChanged.connect(change -> onChanged.emit());
    }

    /** From a file's bytes, taking its ending, charset and byte-order mark from them. */
    public static TextDocumentModel of(byte[] bytes) {
        TextBuffer buffer = new TextBuffer();
        buffer.loadBytes(bytes);
        return new TextDocumentModel(buffer);
    }

    /** The document itself. Editing goes through here; the model adds nothing in front of it. */
    public TextBuffer buffer() {
        return buffer;
    }

    // ── DocumentModel ───────────────────────────────────────────────────────────────────────────

    /** The ending, the charset and the mark restored. @see TextBuffer#encodeBytes */
    @Override
    public byte[] encode() {
        return buffer.encodeBytes();
    }

    @Override
    public void adopt(byte[] bytes) {
        buffer.loadBytes(bytes);
    }

    @Override
    public int version() {
        return buffer.version();
    }

    @Override
    public UndoStack history() {
        return buffer.history();
    }

    @Override
    public int contentVersion() {
        return buffer.alternativeVersion();
    }

    @Override
    public Signal.Action onChanged() {
        return onChanged;
    }

    /** Text is the one thing a three-way merge is actually for. */
    @Override
    public boolean mergeable() {
        return true;
    }

    /** The buffer's set — a document's problems belong to its document. */
    @Override
    public DiagnosticSet diagnostics() {
        return buffer.diagnostics();
    }

    // ── Language ────────────────────────────────────────────────────────────────────────────────

    @Nullable
    public Language language() {
        return language;
    }

    @Nullable
    public SyntaxTokenizer tokenizer() {
        return tokenizer;
    }

    @Nullable
    public LanguageServices services() {
        return services;
    }

    /**
     * Binds this document to a language, its tokenizer and its services.
     *
     * <p>Replacing them closes what was there — a tokenizer holds a native parse tree, and
     * {@code SyntaxTokenizer.close()} existed for a release with nothing calling it, so every parse
     * tree in the application survived until the process ended.</p>
     */
    public TextDocumentModel setLanguage(@Nullable Language language,
                                         @Nullable SyntaxTokenizer tokenizer,
                                         @Nullable LanguageServices services) {
        closeLanguage();
        this.language = language;
        this.tokenizer = tokenizer;
        this.services = services;
        return this;
    }

    /**
     * Releases the parse tree, the engine and the buffer subscription.
     *
     * <p>Reached from the last {@link DocumentReference} being released, never from a widget's
     * teardown: the dock rebuilds every panel on every split and drag, so freeing the tree there
     * releases it for a document that is still open and rebuilds it on the next frame.</p>
     */
    @Override
    public void dispose() {
        closeLanguage();
        bufferSubscription.disconnect();
    }

    private void closeLanguage() {
        if (services != null) {
            services.close();
            services = null;
        }
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        language = null;
    }
}
