package com.crystalgui.text.lang;

import java.util.List;
import java.util.function.Consumer;

/**
 * What is this name, what type belongs here, and what can I say after a dot — the three questions an
 * engine answers about a position.
 *
 * <h3>Asynchronous by callback, because the answer may not be worth having by the time it arrives</h3>
 *
 * <p>Every method takes a {@link Consumer} rather than returning a value or a future. Three reasons, and
 * the third is the one that decides it:</p>
 *
 * <ol>
 *   <li>Resolution needs a compile. On a cold classpath that is far past a frame, so a synchronous
 *       signature would be a promise no implementation could keep.</li>
 *   <li>The answer is delivered on the <b>UI thread</b>, during the scheduler's drain — the same contract
 *       {@code JobScheduler.Job.onDone} has. A {@code CompletableFuture} would complete on whichever
 *       thread finished the work, and every consumer would need its own hop back.</li>
 *   <li><b>A superseded request must be able to produce no answer at all.</b> Moving the caret makes a
 *       hover request pointless; the scheduler's keyed single-flight already drops it, and a future would
 *       have to be completed with something to avoid leaking. A callback that is simply never invoked is
 *       the honest expression of "you stopped caring, so this stopped running".</li>
 * </ol>
 *
 * <p>So: <b>the callback may never fire.</b> Anything holding UI state open across a request must key it
 * on something else — a session, a popup's own lifetime — and not on the callback arriving.</p>
 *
 * <h3>Every answer is versioned, and the consumer decides what to do about it</h3>
 *
 * <p>The staleness policy for all three of these is <b>discard</b>: the user asked about one character,
 * and if the document moved the answer is about a different one. {@link Versioned#isFresh} is the check;
 * see that type for why it is not made here.</p>
 *
 * <h3>Partial answers are required, not a nicety</h3>
 *
 * <p>A script under the caret is nearly always incomplete — that is what typing looks like. An engine that
 * answers only for well-formed input answers exactly when it is not needed. ECJ's binding recovery is what
 * makes this possible for Java (see {@code plan_syntax.md} §15.1) and the checklist tests hold it to it.</p>
 */
public interface Resolver {

    /** Answers nothing, always — a language with no engine, and the honest default. */
    Resolver NONE = new Resolver() {

        @Override
        public void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer) {
            answer.accept(Versioned.none(0));
        }

        @Override
        public void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer) {
            answer.accept(Versioned.none(0));
        }

        @Override
        public void membersOf(TypeRef type, int contextOffset, Consumer<Versioned<List<SymbolInfo>>> answer) {
            answer.accept(Versioned.none(0));
        }
    };

    /**
     * What a <b>name</b> refers to — the one question here that is not about a position.
     *
     * <p>Every other method resolves from an offset, because every other caller is looking at a document.
     * A documentation link is not: {@code {@link java.util.List}} names its target outright, and there is
     * no position in any open file that means it. Following one means showing that element's
     * documentation, which needs a {@link SymbolInfo} the caller cannot get any other way — so the
     * link was styled, hit-testable and inert until this existed.</p>
     *
     * <p><b>A default that answers nothing</b>, because most engines cannot do this and a language with
     * no engine certainly cannot. An unanswered link stays inert, which is exactly what it was.</p>
     *
     * @param name what the language calls the thing — a qualified type name for Java, possibly with a
     *             {@code #member} suffix. Interpreting it is the engine's business: only the engine knows
     *             what its own references look like.
     */
    default void describe(String name, Consumer<Versioned<SymbolInfo>> answer) {
        answer.accept(Versioned.none(0));
    }

    /**
     * What the name at {@code offset} refers to — hover and go-to-definition both ask this.
     *
     * @param offset a UTF-16 offset into the document; anywhere within the name, not only its first
     *               character, because a caret sits inside a word far more often than before it
     */
    void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer);

    /**
     * The type the language expects at {@code offset} — an argument's declared type, an assignment's
     * left-hand side, a return.
     *
     * <p>Separate from {@link #resolveAt} because it is a different question: that one asks what is
     * <em>there</em>, this asks what <em>belongs</em> there, and at an empty position only this one has an
     * answer. It exists for completion ranking, where IntelliJ's expected-type conformance is the single
     * largest quality difference against a list sorted by name — offering {@code Color} first inside
     * {@code setColor(|)} is the whole feeling of a good completion list.</p>
     */
    void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer);

    /**
     * Everything reachable on {@code type} from {@code contextOffset} — what a completion list after a dot
     * is built from.
     *
     * <p><b>{@code contextOffset} is not decoration.</b> Accessibility is a property of where you are
     * asking from, not of the type: a private member is a member from inside its own class and not from
     * outside it, and a protected one depends on the asking type's hierarchy. An implementation that
     * ignores this argument offers members that will not compile, which is worse than offering none —
     * the list looks authoritative and the error appears after acceptance.</p>
     *
     * @param type the type to enumerate — hand back a {@link TypeRef} this resolver produced, so the
     *             engine's own binding is intact and generic substitution survives (see {@link TypeRef})
     */
    void membersOf(TypeRef type, int contextOffset, Consumer<Versioned<List<SymbolInfo>>> answer);
}
