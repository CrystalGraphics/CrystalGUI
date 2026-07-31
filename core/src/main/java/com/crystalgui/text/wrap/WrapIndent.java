package com.crystalgui.text.wrap;

/**
 * How much leading indent a wrapped continuation line carries — VS Code's {@code WrappingIndent}.
 *
 * <p>Source: {@code src/vs/editor/common/config/editorOptions.ts}, microsoft/vscode, MIT licence. The
 * computation in {@link #columnsFor} is {@code computeWrappedTextIndentLength} from
 * {@code monospaceLineBreaksComputer.ts}.</p>
 *
 * <p>Purely visual — the carried indent is <b>not</b> in the document, so it never appears in
 * {@code getText()}, and every conversion in {@link LineProjection} adds and removes it around the model
 * offset rather than storing it. That is the whole reason wrapping is a view concern: a soft wrap must
 * leave the bytes alone, or reformatting on window resize becomes an edit.</p>
 */
public enum WrapIndent {

    /** Continuation lines start at column 0. */
    NONE,
    /** Continuation lines align with the row's own indentation. */
    SAME,
    /** As {@link #SAME} plus one further indent level — VS Code's default. */
    INDENT,
    /** As {@link #SAME} plus two. */
    DEEP_INDENT;

    /**
     * The columns of indent continuation lines of {@code line} should carry.
     *
     * <p>Clamped so the carried indent can never consume more than half the wrap width. Without the
     * clamp a deeply indented line in a narrow viewport wraps into continuation lines with no room for
     * text, and the wrap loop cannot terminate — VS Code carries the same guard for the same reason.</p>
     */
    public int columnsFor(String line, int tabSize, int wrapColumn) {
        if (this == NONE) return 0;

        int firstNonBlank = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                firstNonBlank = i;
                break;
            }
        }
        // A blank line has no indent to carry, and no text to carry it onto.
        if (firstNonBlank < 0) return 0;

        int indent = 0;
        for (int i = 0; i < firstNonBlank; i++) {
            indent += line.charAt(i) == '\t' ? tabSize - (indent % tabSize) : 1;
        }

        int extra = this == DEEP_INDENT ? 2 : this == INDENT ? 1 : 0;
        indent += extra * tabSize;

        return indent >= wrapColumn ? 0 : indent;
    }
}
