package com.crystalgui.language.js.rhino.resolve;

import com.crystalgui.text.TextPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Offset → row/column over one immutable snapshot of a script.
 *
 * <h3>Why the conversion happens here and not downstream</h3>
 *
 * <p>Rhino reports every position as an absolute file offset, which is better than what JDT gives and
 * different from what {@link com.crystalgui.text.diagnostic.Diagnostic} takes: a diagnostic names a
 * <b>row and column</b>, deliberately, because that is what survives an edit somewhere else in the
 * file. Converting needs the exact text the parse saw — and the analysis is the only thing that still
 * has it, because the buffer will have moved on by the time anything downstream looks.</p>
 *
 * <p>Built once per analysis and thrown away with it. A binary search over line starts rather than a
 * scan per position: a file with two hundred problems is exactly the file somebody is typing in, and a
 * scan would be quadratic in the thing that is already slowest.</p>
 */
public final class LineIndex {

    private final int[] lineStarts;
    private final int length;

    public LineIndex(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        this.lineStarts = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) lineStarts[i] = starts.get(i);
        this.length = text.length();
    }

    public TextPoint pointAt(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= clamped) low = mid;
            else high = mid - 1;
        }
        return new TextPoint(low, clamped - lineStarts[low]);
    }
}
