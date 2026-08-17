package com.crystalgui.ui.elements.editor;

/**
 * One piece of the editor's view, owning its own elements and placing them each pass.
 *
 * <p>Ported from VS Code's {@code ViewPart} / {@code ViewEventHandler}
 * ({@code src/vs/editor/browser/view/viewPart.ts}, {@code src/vs/editor/common/viewEventHandler.ts},
 * MIT). Monaco splits its view into roughly two dozen of these — {@code viewLines}, {@code viewCursors},
 * {@code selections}, {@code currentLineHighlight}, {@code indentGuides}, {@code lineNumbers},
 * {@code rulers}, {@code whitespace} — and the split is the reason none of them is large. This editor
 * had all of them inlined as {@code layOut*} methods on one 4,100-line class.</p>
 *
 * <h3>Why the parts stay in this package rather than mirroring Monaco's directory per part</h3>
 * <p>Monaco needs a {@code ViewContext} because a part may not reach into the view. Here the parts are
 * package-private classes beside the editor, and reach it directly through package-private accessors. The
 * indirection Monaco needs buys nothing with one view implementation in one package, and a context object
 * that only ever wraps a single editor is a layer to keep in step rather than a seam. What is genuinely
 * worth porting is the <b>decomposition and the render protocol</b>, and both are here.</p>
 *
 * <h3>There is no {@code shouldRender} gate, and that is now a decision rather than an omission</h3>
 * <p>Monaco renders a part only when an event has marked it dirty ({@code _getViewPartsToRender} in
 * {@code browser/view.ts}), and this class carried the flag — {@code setShouldRender}, {@code
 * onDidRender}, the lot — from the day the parts were extracted. <b>Nothing ever called it.</b> Every
 * part rendered every frame and the driver ignored the answer, which the class's own javadoc said
 * outright.</p>
 *
 * <p>A protocol that exists and is not honoured is worse than none: the next author reads the flag, sets
 * it, and gets no behaviour change — or worse, trusts that a part not setting it will not render. So it
 * is gone. <b>If it is wanted, it has to be wired from the invalidation sites</b> — the scroll, the
 * selection change, the diagnostic push, the fold toggle — and not from the parts, because a part cannot
 * know that something it reads has changed. Getting that wrong shows up as a <em>stale decoration</em>
 * rather than as a failure, which is the kind of bug that wants its own change to bisect.</p>
 *
 * <p>What made the gate worth having in Monaco is per-frame cost, and the two genuine costs here were
 * text shapings that ran every frame regardless of dirtiness — both now cached at their source, which is
 * where they belonged either way.</p>
 */
abstract class EditorViewPart {

    protected final TextEditor editor;

    EditorViewPart(TextEditor editor) {
        this.editor = editor;
    }

    /**
     * Places this part's elements for the given inclusive view-line window.
     *
     * <p>The window is the editor's realised range, overscan included — the same {@code first}/{@code last}
     * the {@code layOut*} methods took. A part that is not per-line ignores it.</p>
     *
     * <p><b>An empty window means "hide what you have", never "return".</b> {@code lastViewLine <
     * firstViewLine} happens between a document being replaced and the next {@code updateWindow}, and a
     * part that returns early leaves its decorations where the old text was — pointing at rows that no
     * longer exist. The pool-based parts called {@code hideAll()} and two of them returned; this is the
     * rule they now share, and it lives here so a new part inherits it rather than choosing.</p>
     */
    abstract void render(int firstViewLine, int lastViewLine);

    /** Whether this pass has any line to draw on. @see #render */
    protected final boolean hasWindow(int firstViewLine, int lastViewLine) {
        return lastViewLine >= firstViewLine;
    }
}
