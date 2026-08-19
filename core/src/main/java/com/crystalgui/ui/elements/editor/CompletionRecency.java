package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.SymbolKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What has been accepted lately, so the list can offer it sooner — §18.3's recency weigher.
 *
 * <h3>What this is and, more importantly, what it is not</h3>
 *
 * <p>IntelliJ opens {@code System.} with {@code out} at the top. That is not a rule anybody wrote: it is
 * <b>learned statistics</b>, keyed by completion context and persisted across sessions, and it is the single
 * reason its ordering feels prescient. This is the small honest version of that — a most-recently-accepted
 * list, consulted after proximity — and it is worth being plain that <b>it reproduces nothing on a fresh
 * start</b>. Until you have accepted {@code out} once, ours still opens with {@code err}, because within the
 * fields nothing but the alphabet separates them.</p>
 *
 * <p>Building the real thing means a persisted store keyed by context, which is a feature with a storage
 * format and a migration story rather than a weigher. Recorded here rather than half-built.</p>
 *
 * <h3>Global, and deliberately so</h3>
 *
 * <p>"What this person reaches for" is a fact about the person, not about a document — accepting
 * {@code println} in one file should make it rise in the next. IntelliJ's is global for the same reason.
 * The cost is one static, which is why it is a real object with a shared instance rather than a bag of
 * static methods: a test can build its own and the shared one can be cleared.</p>
 *
 * <h3>Keyed by kind and insertion text, never by label</h3>
 *
 * <p>The label carries a signature now, so keying on it would make {@code getProperty(String)} and
 * {@code getProperty(String, String)} separate entries — accepting one would not raise the other, which is
 * wrong: they are the same name and the user reached for that name. Kind is in the key because a field and
 * a method sharing a name are genuinely different things.</p>
 */
public final class CompletionRecency {

    /**
     * How many acceptances are remembered.
     *
     * <p>Small on purpose. Recency is a tiebreak among items that already matched equally well, so its job
     * is to surface the handful of things reached for in the last few minutes — a long tail of half-forgotten
     * entries makes the ordering less predictable rather than more, because the reason a row moved stops
     * being something the user can remember doing.</p>
     */
    private static final int CAPACITY = 200;

    /** The application-wide one. See the class note on why this is global. */
    private static final CompletionRecency SHARED = new CompletionRecency();

    public static CompletionRecency shared() {
        return SHARED;
    }

    /**
     * Access-ordered, so reading an entry moves it to the front and the eldest is the true LRU.
     *
     * <p>{@code accessOrder = true} is the whole mechanism — with insertion order this would evict the
     * oldest <em>first accepted</em> rather than the least recently used, which is a different and much
     * worse policy that behaves identically until the map is full.</p>
     */
    private final LinkedHashMap<String, Boolean> accepted =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CAPACITY;
                }
            };

    /** Records that {@code item} was accepted. */
    public void note(CompletionItem item) {
        if (item == null) return;
        accepted.put(keyOf(item.kind(), item.textToInsert()), Boolean.TRUE);
    }

    /**
     * How recently {@code item} was accepted — higher is more recent, {@code 0} for never.
     *
     * <p>A rank rather than a timestamp, so it needs no clock and cannot be perturbed by how long the
     * application has been open. {@code TransitionEngine} is the standing example of what a hidden clock
     * costs a test.</p>
     */
    public int rankOf(CompletionItem item) {
        if (item == null || accepted.isEmpty()) return 0;
        String wanted = keyOf(item.kind(), item.textToInsert());
        // The map is access-ordered, so iteration runs least-recent first: a hit at position i out of n
        // scores i + 1, and the most recent scores n.
        int position = 0;
        for (String key : accepted.keySet()) {
            position++;
            if (key.equals(wanted)) return position;
        }
        return 0;
    }

    /** Forgets everything. For tests, and for a future "reset my completion history". */
    public void clear() {
        accepted.clear();
    }

    private static String keyOf(SymbolKind kind, String insertText) {
        return (kind == null ? "?" : kind.name()) + ":" + (insertText == null ? "" : insertText);
    }
}
