package com.crystalgui.language.run;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Everything the running scripts have printed — one per workspace, collapsed by call site, and bounded.
 *
 * <h3>Why this is not shaped like a process transcript</h3>
 *
 * <p>IntelliJ's Run console is one process's stdout: a linear stream with a beginning, an end and an
 * exit code. Two of those three do not exist here. Scripts run <em>in</em> the game's JVM, so there is
 * no process boundary to capture at, and an event-driven script never terminates. Unity's Console is the
 * reference instead — one process, many concurrently-live scripts, per-frame execution — and this class
 * is the store behind it. @see plan_m9_5.md</p>
 *
 * <h3>Collapsing, and why the key is the call site</h3>
 *
 * <p>A handler that prints once a tick emits twenty lines a second, and a console that shows all of them
 * is a firehose rather than something anybody reads. Unity folds "recurring" messages and recommends it
 * for errors "generated on each frame update" — the same pressure exactly.</p>
 *
 * <p><b>On the text and the origin together</b>, which is Unity's rule with one extra separation. This
 * folded on the origin alone at first, reasoning that a counter printing {@code tick 1}, {@code tick 2}
 * would otherwise never fold — and that deleted output: a script printing through a helper gave every
 * line the same origin, so thirteen distinct results became one row reading {@code ×13}. Three different
 * messages are three messages. The flood a bound is genuinely needed for is answered by the ring.</p>
 *
 * <p>Only <b>consecutive</b> messages fold. Folding across a gap would let a line printed a minute ago
 * absorb one printed now and silently reorder the transcript, which is the one thing a console may not
 * do. And a message with <b>no origin never folds at all</b>: two unattributed lines share "nowhere",
 * not a call site, and merging them would join messages with nothing to do with each other.</p>
 *
 * <h3>Bounded, which is not the same as not surviving</h3>
 *
 * <p>Output deliberately survives its script stopping — the most useful transcript is usually the one
 * from the run that just died. That is a promise about <em>lifetime</em>, not about <em>volume</em>: a
 * script printing without pause would otherwise grow this until the game dies.</p>
 *
 * <p>So it is a ring, and it is sized in <b>KB rather than in lines</b>, which is IntelliJ's own choice
 * for its console cycle buffer — and its documentation's warning that a large buffer "can affect
 * performance in the case of chatty processes" describes our normal case rather than an edge one. Lines
 * would be the wrong unit: one stack trace is worth thirty prints, so a line budget lets a single
 * exception evict a whole run's transcript.</p>
 *
 * <p>Eviction is <b>counted and reported</b>, never silent. A transcript that quietly begins in the
 * middle reads as the console having missed something rather than as the ring having done its job.</p>
 *
 * <h3>Thread safety, unlike {@code Markers}</h3>
 *
 * <p>The diagnostics model is written and read on one thread. This one is not: output arrives on
 * whatever thread the script is running on — its own for a one-shot, the game's for a tick handler —
 * while the panel reads on the UI thread. Every mutator is therefore synchronized, and {@link #entries}
 * answers a snapshot rather than a live view, so a panel iterating it cannot see a row appear underneath
 * itself.</p>
 */
public final class RunConsole {

    /**
     * Fired after every change, including each appended line.
     *
     * <p><b>A consumer must coalesce.</b> At twenty ticks a second this emits twenty times a second per
     * chatty script, and a panel that rebuilt its rows on each one would spend the frame doing it. The
     * house pattern is {@code ProjectFileTree}'s deferred refresh — mark dirty here, rebuild once in the
     * frame — and it applies for the same reason it does there.</p>
     */
    public final Signal.Value<RunConsole> onDidChange = new Signal.Value<>();

    /** One row: a message, and how many consecutive repeats from the same call site it stands for. */
    public static final class Entry {

        private final String script;
        @Nullable private final String origin;
        @Nullable private final Resource file;
        private final int line;
        private final RunLevel level;
        @Nullable private final String collapseKey;
        private String text;
        private int count = 1;
        private boolean divider;

        private Entry(RunMessage message) {
            this.script = message.script();
            this.origin = message.origin();
            this.file = message.file();
            this.line = message.line();
            this.level = message.level();
            this.text = message.text();
            // TAKEN FROM THE MESSAGE, never derived again. This class used to compute its own copy of
            // the same expression, and the two drifted by a single character -- one of the separators
            // in the message's version was a NUL rather than a space, which is a legal Java string
            // literal, compiles silently, and makes every key mismatch. So nothing folded, the console
            // grew a row per tick, and the only visible symptom was a test failing by a factor of 300.
            // One rule, one home: the message says what it folds on and this stores the answer.
            this.collapseKey = message.collapseKey();
        }

        public String script() {
            return script;
        }

        @Nullable
        public String origin() {
            return origin;
        }

        @Nullable
        public Resource file() {
            return file;
        }

        public int line() {
            return line;
        }

        public RunLevel level() {
            return level;
        }

        /**
         * The line this row stands for.
         *
         * <p>Every message folded into a row now has identical text by construction, so "newest" and
         * "first" are the same string — which is the point: a row can only stand for lines a reader
         * would call the same line.</p>
         */
        public String text() {
            return text;
        }

        /** How many consecutive messages this row stands for; 1 for an ordinary line. */
        public int count() {
            return count;
        }

        public boolean isNavigable() {
            return file != null && line > 0;
        }

        /**
         * A run boundary rather than something a script printed.
         *
         * <p>On the entry and not on {@link RunMessage}, because a divider is a fact about the
         * <em>console</em> — nobody wrote it and it has no origin, no level and nothing to navigate to.
         * Putting it on the message would give every real line a field that is always false and would
         * invite a producer to synthesise one.</p>
         */
        public boolean isDivider() {
            return divider;
        }

        int weight() {
            return text.length() + (origin == null ? 0 : origin.length()) + script.length();
        }
    }

    /**
     * The default ring, in KB.
     *
     * <p>IntelliJ's equivalent is a setting with no documented default and a warning attached, so this
     * is a starting point rather than a ported constant. A megabyte is minutes of a chatty script and
     * costs nothing next to the engines already resident.</p>
     */
    public static final int DEFAULT_BUDGET_KB = 1024;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private int weight;
    private int budgetChars = DEFAULT_BUDGET_KB * 1024;
    private long dropped;
    private boolean collapsing = true;

    /** Appends one line, folding it into the previous row when they share a call site. */
    public synchronized void append(RunMessage message) {
        if (message == null) return;

        Entry last = entries.peekLast();
        String key = message.collapseKey();
        // A DIVIDER IS NEVER A FOLD TARGET. It carries a collapse key like any other entry -- it is
        // built from a message -- so without this a run whose first line happened to match the boundary
        // text would merge into it and the boundary would silently count up instead of appearing.
        if (collapsing && last != null && !last.divider && key != null && key.equals(last.collapseKey)) {
            weight -= last.weight();
            last.text = message.text();
            last.count++;
            weight += last.weight();
        } else {
            Entry entry = new Entry(message);
            entries.addLast(entry);
            weight += entry.weight();
        }

        evictWhileOverBudget();
        onDidChange.emit(this);
    }

    /**
     * Drops the oldest rows until the ring fits.
     *
     * <p>Never below one row. A budget smaller than a single message would otherwise empty the console
     * on every append and report a drop each time — the panel would show nothing and blame the ring.</p>
     */
    private void evictWhileOverBudget() {
        while (weight > budgetChars && entries.size() > 1) {
            Entry oldest = entries.removeFirst();
            weight -= oldest.weight();
            dropped++;
        }
    }

    /** A snapshot, oldest first. @see RunConsole for why this is not a live view. */
    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * How many rows the ring has evicted since the last {@link #clear}.
     *
     * <p>Read by the panel so a truncated transcript says so. Silent truncation reads as the console
     * having missed something rather than as the bound having been reached.</p>
     */
    public synchronized long dropped() {
        return dropped;
    }

    /**
     * Opens a run — a boundary row, so the next lines are visibly not the last run's.
     *
     * <h3>A boundary rather than a clear, and the difference matters</h3>
     *
     * <p>IntelliJ gives each run a fresh console and Unity offers Clear on Play, and both throw the
     * previous transcript away. Keeping it and drawing a line is the third option, and it is the right
     * one here for the reason this console already keeps output after a script stops: <b>the most useful
     * transcript is often the one from the run before</b> — the one that worked, or the one whose error
     * you are comparing against. Clearing is still available and is now a button; it is simply not the
     * price of running again.</p>
     *
     * <p>Never folds into the row above it, whatever it says, because two runs that printed nothing are
     * still two runs.</p>
     */
    public synchronized void startRun(String label) {
        Entry entry = new Entry(RunMessage.of(label == null ? "" : label, RunLevel.OUT,
                label == null ? "" : label));
        entry.divider = true;
        entries.addLast(entry);
        weight += entry.weight();
        evictWhileOverBudget();
        onDidChange.emit(this);
    }

    /** Everything, including the eviction notice — this is a fresh start, not a scroll. */
    public synchronized void clear() {
        entries.clear();
        weight = 0;
        dropped = 0;
        onDidChange.emit(this);
    }

    public synchronized RunConsole setBudgetKb(int kilobytes) {
        budgetChars = Math.max(1, kilobytes) * 1024;
        evictWhileOverBudget();
        onDidChange.emit(this);
        return this;
    }

    public synchronized int budgetKb() {
        return budgetChars / 1024;
    }

    /** Unity's Collapse toggle. On by default, because the flood is the normal case here. */
    public synchronized RunConsole setCollapsing(boolean collapsing) {
        this.collapsing = collapsing;
        onDidChange.emit(this);
        return this;
    }

    public synchronized boolean isCollapsing() {
        return collapsing;
    }
}
