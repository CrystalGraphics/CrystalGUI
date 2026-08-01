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
 * <h3>{@code shouldRender} is defined but not yet driving anything</h3>
 * <p>Monaco renders a part only when an event has marked it dirty ({@code _getViewPartsToRender} in
 * {@code browser/view.ts}), which is what makes a settled frame cost nothing. This editor still renders
 * every part every frame, exactly as it did when they were methods — the extraction is deliberately a
 * <b>pure code move</b>, so the 226 widget tests are a real net under it.</p>
 *
 * <p>The flag lives here from the start because retrofitting it later means revisiting every part; wiring
 * it means giving each part its own invalidation, and getting that wrong shows up as a stale decoration
 * rather than as a failure, which is the kind of bug that wants its own change to bisect. Until then
 * {@link #shouldRender()} answers {@code true} and the driver ignores it.</p>
 */
abstract class EditorViewPart {

    protected final TextEditor editor;

    /** Deliberately starts true: a part that has never rendered has everything to do. */
    private boolean shouldRender = true;

    EditorViewPart(TextEditor editor) {
        this.editor = editor;
    }

    /** Marks this part as needing a pass. */
    final void setShouldRender() {
        shouldRender = true;
    }

    final boolean shouldRender() {
        return shouldRender;
    }

    final void onDidRender() {
        shouldRender = false;
    }

    /**
     * Places this part's elements for the given inclusive view-line window.
     *
     * <p>The window is the editor's realised range, overscan included — the same {@code first}/{@code last}
     * the {@code layOut*} methods took. A part that is not per-line ignores it.</p>
     */
    abstract void render(int firstViewLine, int lastViewLine);
}
