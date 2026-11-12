package com.crystalgui.text.lang;


import java.util.HashMap;
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
 * <h3>Owned, and shared by the applications of a desktop</h3>
 *
 * <p>"What this person reaches for" is a fact about the person, not about a text buffer — accepting
 * {@code println} in one file should make it rise in the next. So it is a service a desktop owns, thread-safe, and
 * read as a {@link Snapshot}: a ranking asks once per sort, never per comparison, and never sees a half-updated
 * list.</p>
 *
 * <pre>{@code
 * CompletionRecency recency = EditorSuggest.recencyOf(editor.document());   // the desktop's
 * recency.note(accepted);                                                // on accept
 * CompletionRecency.Snapshot ranks = recency.snapshot();                 // once per sort
 * int rank = ranks.rankOf(item);                                         // higher is more recent, 0 for never
 * }</pre>
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
    public synchronized void note(CompletionItem item) {
        if (item == null) return;
        accepted.put(keyOf(item.kind(), item.textToInsert()), Boolean.TRUE);
    }

    /**
     * The ranks now, immutable: {@code 0} for never accepted, higher for more recent. A rank rather than a timestamp,
     * so it needs no clock and cannot be perturbed by how long the application has been open.
     */
    public synchronized Snapshot snapshot() {
        Map<String, Integer> ranks = new HashMap<>();
        // The map is access-ordered, so iteration runs least-recent first: position i of n scores i + 1.
        int position = 0;
        for (String key : accepted.keySet()) ranks.put(key, ++position);
        return new Snapshot(ranks);
    }

    /** One reading of the recency, for a sort. */
    public static final class Snapshot {
        private final Map<String, Integer> ranks;

        private Snapshot(Map<String, Integer> ranks) {
            this.ranks = ranks;
        }

        /** How recently {@code item} was accepted — higher is more recent, {@code 0} for never. */
        public int rankOf(CompletionItem item) {
            if (item == null || ranks.isEmpty()) return 0;
            Integer rank = ranks.get(keyOf(item.kind(), item.textToInsert()));
            return rank == null ? 0 : rank;
        }
    }

    /** Forgets everything. For tests, and for a future "reset my completion history". */
    public synchronized void clear() {
        accepted.clear();
    }

    static String keyOf(SymbolKind kind, String insertText) {
        return (kind == null ? "?" : kind.name()) + ":" + (insertText == null ? "" : insertText);
    }
}
