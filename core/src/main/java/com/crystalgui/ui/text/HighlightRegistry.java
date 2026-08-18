package com.crystalgui.ui.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Named sets of {@link TextRange}s to be styled by {@code ::highlight(name)} — this engine's
 * {@code CSS.highlights}, from the CSS Custom Highlight API.
 *
 * <h3>What this is for</h3>
 * <p>Styling ranges of text <b>without putting elements around them</b>. That is the API's entire reason
 * to exist on the web, and the motivating cases are ours exactly: syntax highlighting, search matches,
 * spell-check marks, diff runs. Monaco and CodeMirror cannot afford a {@code <span>} per token, and
 * neither can we — every element here is a real Taffy node.</p>
 *
 * <pre>{@code
 * text.highlights().set("keyword", TextRange.of(0, 4));
 * text.highlights().set("search", TextRange.of(12, 18), TextRange.of(40, 46));
 * }</pre>
 * <pre>{@code
 * ::highlight(keyword) { color: #C678DD; }
 * ::highlight(search)  { background-color: #5A4A00; }
 * }</pre>
 *
 * <h3>Per element, not global — and why that is the honest translation</h3>
 * <p>{@code CSS.highlights} is one registry for the whole document, which works because a DOM
 * {@code Range} carries its own container node. A {@link TextRange} here is a bare pair of indices, so
 * the owning element has to come from somewhere: it comes from the registry itself. The name-to-style
 * mapping is still global — {@code ::highlight(keyword)} means the same thing everywhere — which is the
 * part that actually matters for theming.</p>
 *
 * <h3>Names are not validated against the stylesheet</h3>
 * <p>Registering {@code "keyword"} when no {@code ::highlight(keyword)} rule exists is legal and does
 * nothing, exactly as on the web. A highlighter should not have to know which theme is loaded, and a
 * theme should be free to style only some of the names a highlighter emits.</p>
 */
public final class HighlightRegistry {

    /** Insertion-ordered: overlapping highlights are resolved by registration order, so the answer is at
     * least deterministic and explainable. See {@code UIText}'s span translation for the rule. */
    private final Map<String, List<TextRange>> byName = new LinkedHashMap<>();
    private final Consumer<HighlightRegistry> onChanged;

    public HighlightRegistry(Consumer<HighlightRegistry> onChanged) {
        this.onChanged = onChanged;
    }

    /** Replaces every range registered under {@code name}. */
    public HighlightRegistry set(String name, TextRange... ranges) {
        return set(name, List.of(ranges));
    }

    public HighlightRegistry set(String name, List<TextRange> ranges) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A highlight name is required — it is what ::highlight(x) matches");
        }
        if (ranges.isEmpty()) return remove(name);

        List<TextRange> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Integer.compare(a.start(), b.start()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start() < sorted.get(i - 1).end()) {
                // THE NAME IS THE ONE FACT A READER NEEDS, and it was the one fact this omitted. Two
                // offsets say a producer registered overlapping ranges; the name says WHICH producer,
                // and there are half a dozen feeding one editor line.
                throw new IllegalArgumentException("Ranges within one highlight must not overlap: "
                        + sorted.get(i - 1) + " and " + sorted.get(i) + " under \"" + name
                        + "\". Use two differently-named highlights if you want both to apply.");
            }
        }
        List<TextRange> previous = byName.put(name, Collections.unmodifiableList(sorted));
        if (!sorted.equals(previous)) onChanged.accept(this);
        return this;
    }

    /** Adds to whatever is already registered under {@code name}. */
    public HighlightRegistry add(String name, TextRange range) {
        List<TextRange> merged = new ArrayList<>(get(name));
        merged.add(range);
        return set(name, merged);
    }

    public List<TextRange> get(String name) {
        return byName.getOrDefault(name, Collections.emptyList());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public HighlightRegistry remove(String name) {
        if (byName.remove(name) != null) onChanged.accept(this);
        return this;
    }

    public HighlightRegistry clear() {
        if (!byName.isEmpty()) {
            byName.clear();
            onChanged.accept(this);
        }
        return this;
    }

    /** Name → ranges, in registration order. */
    public Map<String, List<TextRange>> entries() {
        return Collections.unmodifiableMap(byName);
    }
}
