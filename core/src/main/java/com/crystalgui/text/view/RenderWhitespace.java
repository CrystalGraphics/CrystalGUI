package com.crystalgui.text.view;

/**
 * Which whitespace is drawn — VS Code's {@code editor.renderWhitespace}.
 *
 * <p>Source: {@code src/vs/editor/common/viewLayout/viewLineRenderer.ts}, microsoft/vscode, MIT.</p>
 *
 * <p>{@code selection} is not ported. It renders whitespace only inside the current selection, which
 * means the markers change on every caret move and so must be recomputed from selection state rather than
 * from the line — a different input to everything else here. Worth adding when something asks; guessing at
 * it now would put selection state into a function whose whole point is that it depends only on the
 * text.</p>
 */
public enum RenderWhitespace {

    /** Nothing. The default, as in VS Code. */
    NONE,

    /**
     * Leading, trailing, tabs, and runs of two or more spaces.
     *
     * <p>The useful one, and the reason the mode exists at all: it shows indentation and accidental
     * double spaces while leaving ordinary single spaces between words alone, so prose and code stay
     * readable instead of turning into a field of dots.</p>
     */
    BOUNDARY,

    /** Only whitespace after the last non-blank character — what a linter would flag. */
    TRAILING,

    /** Every space and tab. */
    ALL
}
