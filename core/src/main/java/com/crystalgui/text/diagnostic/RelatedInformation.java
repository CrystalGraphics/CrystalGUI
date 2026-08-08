package com.crystalgui.text.diagnostic;

import com.crystalgui.text.TextPoint;

/**
 * A second place worth looking at — the Language Server Protocol's {@code DiagnosticRelatedInformation},
 * VS Code's {@code IRelatedInformation}.
 *
 * <h3>The half of an error that a single position cannot carry</h3>
 *
 * <p>Most interesting failures are about <b>two</b> places. "Two properties are named 'Color'" is about the
 * duplicate <em>and</em> the original; "this input is already connected" is about the edge being replaced.
 * With one position a producer has to choose which one to point at and describe the other in prose, and the
 * user then goes looking for it by hand — which is exactly what our duplicate-property warning does today,
 * naming the property in its message because it had nowhere else to put it.</p>
 *
 * <p>No resource here, unlike the reference, for the same reason {@code DiagnosticSet} has no resource
 * dimension: a diagnostic belongs to one document and there is nothing yet that could open another one and
 * reveal a position in it.</p>
 *
 * @param start   first character of the other place
 * @param end     one past its last character
 * @param message what is worth saying about it — "the first one is here"
 */
public record RelatedInformation(TextPoint start, TextPoint end, String message) {

    public RelatedInformation {
        if (start == null || end == null) {
            throw new IllegalArgumentException("related information needs a range");
        }
        if (message == null) message = "";
        if (end.compareTo(start) < 0) {
            TextPoint swap = start;
            start = end;
            end = swap;
        }
    }

    /** A whole row elsewhere — what a producer that knows only a line number can say. */
    public static RelatedInformation onRow(int row, String message) {
        return new RelatedInformation(new TextPoint(row, 0),
                new TextPoint(row, Integer.MAX_VALUE), message);
    }
}
