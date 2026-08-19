package com.crystalgui.language.engine.bridge;

import java.util.List;

/**
 * What a correction may ask the <b>host</b> while it is being computed.
 *
 * <h3>Why an interface rather than another parameter</h3>
 *
 * <p>Corrections run on the engine's side of the classloader boundary and mostly need nothing but the
 * syntax tree. A few need something only the host has, and the first of them —
 * {@link #importCandidates} — arrived as a {@code Function<String, List<String>>} argument on
 * {@code codeActionsIn}. That works exactly once. "Did you mean" wants a fuzzy version of the same index;
 * anything generating a line wants the indent; anything touching files wants the workspace. Each would be
 * another parameter, and a parameter here is a <b>bridge signature</b> — a shape both loaders must agree
 * on and the oldest band must compile against.</p>
 *
 * <p>So the seam is one interface that grows methods instead of one method that grows arguments. Adding
 * to it is a change on one side; adding a parameter is a change to the contract itself.</p>
 *
 * <h3>Where it lives, and why it has to live here</h3>
 *
 * <p>In the bridge package, which is the one thing {@code EngineClassLoader} delegates to the parent
 * unconditionally. Anywhere else and the host would implement one class while the engine received a
 * different one of the same name — the {@code Bridge cannot be cast to Bridge} failure that package
 * exists to prevent.</p>
 *
 * <h3>What is deliberately not on it yet</h3>
 *
 * <p><b>The indent unit.</b> {@code Rewrites} formats generated code from a fixed four spaces, and the
 * editor does have a {@code tabSize} — but {@code LanguageServices} belongs to the <em>document</em>, and
 * two panes onto one file are one document with two editors that may disagree. So the honest source is
 * the document's own text, detected the way an editor detects it, and that is worth writing when the
 * first correction generates a line rather than now, when none does and the answer would be dead.</p>
 */
public interface CodeActionContext {

    /** Offers nothing — a host with no classpath index, and the default for anything not asked. */
    CodeActionContext NONE = new CodeActionContext() {
        @Override public List<String> importCandidates(String simpleName) {
            return List.of();
        }

        @Override public List<String> similarTypeNames(String simpleName) {
            return List.of();
        }
    };

    /**
     * Every qualified name that could satisfy {@code simpleName} — an <b>exact</b> match on the simple
     * name, never a prefix or fuzzy one.
     *
     * <p>The distinction is the whole reason this is not completion's query: that one is deliberately
     * generous because someone is typing, and importing {@code Listener} because the unresolved name was
     * {@code List} would be a fix that compiles and is not what anyone asked for.</p>
     */
    List<String> importCandidates(String simpleName);

    /**
     * Qualified names of types whose simple name is a <b>near miss</b> for {@code simpleName} — the
     * "did you mean" question, closest first, already capped.
     *
     * <p>A third query rather than a flag on the other two, because each is a different judgement:
     * completion is generous because someone is typing, an import wants an exact name, and this wants a
     * name a keystroke or two away and nothing further. What "near" means is decided on the host, which
     * is the side that owns the index and can walk it cheaply.</p>
     */
    List<String> similarTypeNames(String simpleName);
}
