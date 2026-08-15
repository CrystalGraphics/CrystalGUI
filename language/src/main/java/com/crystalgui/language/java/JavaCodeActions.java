package com.crystalgui.language.java;

import com.crystalgui.language.engine.bridge.SourceAnalyzer;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionProvider;
import com.crystalgui.text.lang.Versioned;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The engine's half of {@link CodeActionProvider} — everything Java can offer about a problem.
 *
 * <p>A supplier rather than the analysis itself, for the reason {@code JavaCompletionProvider} already
 * records: {@code current} is swapped on every compile, so a provider holding an instance would answer
 * from whichever analysis existed when it was built and go silently stale after the first edit.</p>
 *
 * <h3>Answered from the analysis's version, not the buffer's</h3>
 *
 * <p>The request carries the version its offsets address; the answer carries the version the fixes were
 * <em>built</em> against, which is the analysis's and may lag it. Those are deliberately allowed to
 * differ: the caller compares them and drops the answer, rather than this having to decide what a fix for
 * a document nobody is looking at any more is worth. It is the same arrangement
 * {@code onDiagnostics} uses, and the reason {@link CodeAction#isApplicableTo} exists.</p>
 *
 * <p>Synchronous despite the callback shape, because the analysis is already in hand — there is nothing to
 * wait for. The callback is the contract rather than the mechanism, and keeping it means a future band
 * that has to cross a thread does not change this interface.</p>
 */
final class JavaCodeActions implements CodeActionProvider {

    private final Supplier<SourceAnalyzer.Analysis> analysis;

    JavaCodeActions(Supplier<SourceAnalyzer.Analysis> analysis) {
        this.analysis = analysis;
    }

    @Override
    public void actionsAt(Request request, Consumer<Versioned<List<CodeAction>>> answer) {
        SourceAnalyzer.Analysis current = analysis.get();
        if (current == null) {
            answer.accept(Versioned.of(request.version(), List.of()));
            return;
        }
        answer.accept(Versioned.of(current.version(),
                current.codeActionsIn(request.from(), request.to())));
    }
}
