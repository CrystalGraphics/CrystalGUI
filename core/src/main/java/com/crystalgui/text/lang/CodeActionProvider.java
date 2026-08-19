package com.crystalgui.text.lang;

import com.crystalgui.text.diagnostic.Diagnostic;

import java.util.List;
import java.util.function.Consumer;

/**
 * What can be done about the problems in a range — LSP's {@code textDocument/codeAction}.
 *
 * <h3>Asked, never enumerated</h3>
 *
 * <p>The obvious design is a table from each problem to its full set of actions. It is the wrong one, and
 * all three references refuse it: Monaco concatenates the answers of N independently registered
 * providers, IntelliJ merges highlight-attached fixes with every {@code IntentionAction} whose own
 * {@code isAvailable} says yes, and JDT asks each {@code IQuickFixProcessor} whether it
 * {@code hasCorrections} for a problem id before asking what they are. In none of them is anything asked
 * to enumerate everything, because such a table is the size of (problems × contributors), is never
 * finished — ECJ alone reports on the order of a thousand distinct problems — and is one shared file that
 * every new fix has to edit.</p>
 *
 * <p>So a provider answers <b>only for itself</b>, and the caller merges. Three kinds of contributor fall
 * out of that, and keeping them apart is what keeps any one of them small:</p>
 *
 * <ol>
 *   <li><b>Keyed on {@link Diagnostic#code()}</b> — the only part that needs a switch, and the only part
 *       that can have one, since only whoever reported a problem knows what it means.</li>
 *   <li><b>Keyed on the diagnostic's <em>shape</em></b> — suppress, disable the rule, go to related.
 *       These need no table at all: they read whether {@code code} is present, whether {@code related} is
 *       non-empty, and nothing else.</li>
 *   <li><b>Not tied to a problem</b> — refactorings offered at a caret, which answer "what can I do here"
 *       against the syntax tree rather than against any diagnostic. Same type, same list, different
 *       trigger.</li>
 * </ol>
 *
 * <p><b>An unknown code returning nothing is the designed answer, not a gap.</b> The list still carries
 * the shape-derived actions, which is more than a problem offers today, and treating an empty result as a
 * hole to be filled is exactly how the exhaustive table gets built by accident.</p>
 *
 * <h3>Asynchronous, and the callback may never fire</h3>
 *
 * <p>Same contract as {@link CompletionProvider}: the answer is stamped with the version it was computed
 * against, and a request superseded by an edit is simply dropped rather than answered late.</p>
 */
public interface CodeActionProvider {

    /** Offers nothing — the default for a language with no engine. */
    CodeActionProvider NONE = (request, answer) -> answer.accept(Versioned.of(0, List.of()));

    /**
     * Where the actions were asked for, and what problems are there.
     *
     * <p>The diagnostics are <b>handed in</b> rather than looked up, which is LSP's arrangement and is
     * load-bearing for two reasons. The caller has them already, tracked through every edit since they
     * were reported, so their offsets are live in a way a provider re-deriving from row/column could not
     * match. And it is what lets a provider answer about a problem it did not report — the shape-derived
     * contributor works for GLSL and Java alike without knowing either.</p>
     *
     * @param from        start of the range asked about, in UTF-16 offsets
     * @param to          end of it, exclusive; equal to {@code from} for a caret
     * @param diagnostics the problems overlapping that range, in live offsets
     * @param version     the document version {@code from}/{@code to} address, stamped onto any edit
     */
    record Request(int from, int to, List<Diagnostic> diagnostics, long version) {

        public Request {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** The whole range a caret sits at, with whatever problems cover it. */
        public static Request at(int offset, List<Diagnostic> diagnostics, long version) {
            return new Request(offset, offset, diagnostics, version);
        }
    }

    /** Everything this provider offers for {@code request}. @see Resolver for the callback contract */
    void actionsAt(Request request, Consumer<Versioned<List<CodeAction>>> answer);
}
