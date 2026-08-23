package com.crystalgui.text.diff;

import java.util.Collections;
import java.util.List;

/**
 * A line-level change together with the character-level changes inside it.
 *
 * <p>Ported from {@code DetailedLineRangeMapping} in
 * <a href="https://github.com/microsoft/vscode">microsoft/vscode</a>, MIT.</p>
 *
 * <p><b>The inner ranges are not a second feature</b> — they come from re-running the same algorithms over
 * the characters of the block, which is why a diff view's word marks and its line bands can never disagree.
 * A view that computed word marks separately would eventually draw a mark on a line it had not banded.</p>
 *
 * <p>Empty inner ranges mean the block is a pure insertion or deletion: there is nothing to compare
 * against, so there is nothing finer to say than "these lines arrived" or "these lines went".</p>
 */
public record DetailedDiff(DiffRange lines, List<InnerRange> inner) {

    public DetailedDiff {
        inner = Collections.unmodifiableList(inner);
    }
}
