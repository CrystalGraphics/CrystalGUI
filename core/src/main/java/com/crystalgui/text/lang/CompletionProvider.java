package com.crystalgui.text.lang;

import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * What could go here — the source of a completion list.
 *
 * <p>Asynchronous by callback for the reasons {@link Resolver} sets out, including the one that matters
 * most here: <b>the callback may never fire.</b> A completion request is superseded by the next keystroke
 * constantly, and the session must not be waiting on an answer that was cancelled.</p>
 */
public interface CompletionProvider {

    /** Offers nothing — the default for a language with no engine. */
    CompletionProvider NONE = new CompletionProvider() {

        @Override
        public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
            answer.accept(Versioned.of(0, CompletionList.EMPTY));
        }

        @Override
        public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
            answer.accept(item);
        }
    };

    /**
     * Why a session opened, which changes what should be offered.
     *
     * <p>The distinction is not cosmetic. After {@code .} the only sensible answer is the members of what
     * precedes it; on Ctrl+Space in open code it is everything in scope plus unimported types. A provider
     * that cannot tell them apart either floods a member list with locals or offers nothing where a
     * trigger character should have offered members.</p>
     */
    enum TriggerKind {
        /** The user asked — Ctrl+Space. */
        EXPLICIT,
        /** A trigger character was typed. {@link Request#triggerCharacter} says which. */
        CHARACTER,
        /** The previous answer was {@link CompletionList#incomplete}, so the session asked again. */
        RETRIGGER
    }

    /**
     * Where and why completion was asked for.
     *
     * <p>{@code prefix} is what has been typed at the position and is what the list filters on. It is
     * carried rather than left to the provider to re-derive: word boundaries are a language question, and
     * a provider that answers it differently from the session doing the filtering produces a list where
     * the highlighted match and the typed text disagree.</p>
     *
     * @param offset           where the caret is, in UTF-16 offsets into the document
     * @param prefix           the partial word already typed at {@code offset}, possibly empty
     * @param triggerKind      why the session opened
     * @param triggerCharacter the character that opened it, or null unless {@link TriggerKind#CHARACTER}
     */
    record Request(int offset, String prefix, TriggerKind triggerKind, @Nullable String triggerCharacter) {

        public Request {
            if (prefix == null) prefix = "";
            if (triggerKind == null) triggerKind = TriggerKind.EXPLICIT;
        }

        /** Ctrl+Space at an offset. */
        public static Request explicit(int offset, String prefix) {
            return new Request(offset, prefix, TriggerKind.EXPLICIT, null);
        }

        /** A trigger character was typed. */
        public static Request character(int offset, String prefix, String character) {
            return new Request(offset, prefix, TriggerKind.CHARACTER, character);
        }
    }

    /** Everything that could be inserted at {@code request}. @see Resolver for the callback contract */
    void complete(Request request, Consumer<Versioned<CompletionList>> answer);

    /**
     * Fills in the expensive parts of one item — the documentation pane's content.
     *
     * <p>Called for the <b>selected row only</b> and only when {@link CompletionItem#needsResolution()},
     * because doing it for a whole list means reading hundreds of javadoc comments to draw one. The answer
     * is a new item; the original is immutable and may still be in a list somewhere.</p>
     *
     * <p>Not versioned, unlike everything else here. The item was produced against some document version
     * and resolving it only reads what the item already names — a class and a member — so it stays valid
     * however far the document has moved. Stamping it would invite a consumer to discard a perfectly good
     * answer.</p>
     */
    void resolveItem(CompletionItem item, Consumer<CompletionItem> answer);
}
