package com.crystalgui.language.run;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The transcript — a document a script writes into, bounded, with a level per line.
 *
 * <h3>A console is a text area, and this was a list first</h3>
 *
 * <p>The disproof is selection. An IDE console lets you drag from the middle of one line to the middle
 * of another ten rows down and copy exactly that; a row-based list cannot express it, because its
 * selection unit is the row. IntelliJ's console is an editor component and so is VS Code's output panel,
 * and no amount of styling gets a list there.</p>
 *
 * <p>So the model is a document. What survives from the list version is everything that was actually
 * about the <em>console</em> rather than about rows: the bound, the per-line level, and the fact that
 * output arrives from threads that may not touch a UI.</p>
 *
 * <h3>Two sides, and the thread rule is not negotiable</h3>
 *
 * <p>Output arrives on a script's own thread or the game's. A {@link TextBuffer} may only be mutated on
 * the thread that draws it. So {@link #append} does nothing but enqueue, and {@link #drain} — called
 * once a frame from the view — is the only thing that writes. That was a performance choice for the list
 * and is a correctness one here.</p>
 *
 * <h3>The bound is the ring, and collapsing is gone</h3>
 *
 * <p>Folding repeated lines into a {@code ×N} row went with the list, and it should have: IntelliJ does
 * not collapse, and a text area has nowhere to put a badge without becoming a list again. The flood a
 * bound is genuinely needed for is answered by dropping the oldest lines, which is where a bound
 * belongs — and which is what IntelliJ's own console cycle buffer does, sized in KB for the reason its
 * documentation gives: it warns about "chatty processes", which here is the normal case.</p>
 */
public final class RunConsole {

    /**
     * Fired whenever something is enqueued — <b>from whatever thread enqueued it</b>.
     *
     * <p>So a listener may not touch the UI. Its only legitimate response is to mark something dirty and
     * let the next frame call {@link #drain}, which is exactly what the view does.</p>
     */
    public final Signal.Action onDidChange = new Signal.Action();

    /** One line of the transcript, and everything the view needs to know about it. */
    public static final class Line {
        private final String text;
        private final RunLevel level;
        private final String script;
        @Nullable private final Resource file;
        private final int line;
        private final boolean divider;

        Line(String text, RunLevel level, String script, @Nullable Resource file, int line,
             boolean divider) {
            this.text = text;
            this.level = level;
            this.script = script;
            this.file = file;
            this.line = line;
            this.divider = divider;
        }

        public String text() {
            return text;
        }

        public RunLevel level() {
            return level;
        }

        public String script() {
            return script;
        }

        @Nullable
        public Resource file() {
            return file;
        }

        public int line() {
            return line;
        }

        /** A run boundary rather than something a script printed. */
        public boolean isDivider() {
            return divider;
        }

        /** Whether a click on this line has somewhere to go. */
        public boolean isNavigable() {
            return file != null && line > 0;
        }
    }

    /**
     * The default ring, in KB. IntelliJ's equivalent is a setting with a warning attached rather than a
     * documented default, so this is a starting point: a megabyte is minutes of a chatty script and
     * costs nothing beside the engines already resident.
     */
    public static final int DEFAULT_BUDGET_KB = 1024;

    /** Written from any thread, read only by {@link #drain}. */
    /**
     * What makes a span of a line navigable. Copy-on-write because a filter may be registered while the
     * UI thread is walking the chain to paint a row.
     */
    private final List<ConsoleFilter> filters = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ConcurrentLinkedQueue<Line> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean clearRequested;

    /** Mirrors the attached buffer's rows exactly, one entry per row. Touched only by {@link #drain}. */
    private final List<Line> lines = new ArrayList<>();

    @Nullable private TextBuffer buffer;
    private int budgetChars = DEFAULT_BUDGET_KB * 1024;
    private long dropped;

    // ── The producing side: any thread ───────────────────────────────────────────────────────────

    /** Enqueues one line. Safe from a script's thread, the game's, or anywhere else. */
    public void append(RunMessage message) {
        if (message == null) return;
        pending.add(new Line(message.text(), message.level(), message.script(),
                message.file(), message.line(), false));
        onDidChange.emit();
    }

    /**
     * Opens a run — a boundary line, so the next output is visibly not the last run's.
     *
     * <p>A boundary rather than a clear. IntelliJ gives each run a fresh console and Unity offers Clear
     * on Play; keeping the transcript and drawing a line is the third option, and it is right here for
     * the same reason output survives a stop at all — the most useful transcript is often the previous
     * one. Clearing is a button; it is not the price of running again.</p>
     */
    public void startRun(String label) {
        String text = label == null ? "" : label;
        pending.add(new Line(text, RunLevel.OUT, text, null, 0, true));
        onDidChange.emit();
    }

    /**
     * Empties the transcript.
     *
     * <p>Queued rather than applied, so it cannot land between two lines of a burst and leave half of
     * them above a clear that was asked for before either arrived.</p>
     */
    public void clear() {
        clearRequested = true;
        onDidChange.emit();
    }

    // ── The consuming side: the UI thread only ───────────────────────────────────────────────────

    /**
     * Binds the document this writes into.
     *
     * <p>The <em>view's</em> buffer, not one of this class's own: an editor owns its document and cannot
     * adopt another, so the console writes into the editor's rather than the two holding copies that
     * have to be kept in step. A test attaches a bare {@link TextBuffer} and needs no window.</p>
     */
    public RunConsole attach(@Nullable TextBuffer buffer) {
        this.buffer = buffer;
        lines.clear();
        dropped = 0;
        if (buffer != null) buffer.load("");
        return this;
    }

    /**
     * Applies everything enqueued since the last call. <b>UI thread only.</b>
     *
     * @return whether the document changed, so a caller can skip the work a change would have caused
     */
    public boolean drain() {
        TextBuffer target = buffer;
        if (target == null) return false;

        boolean changed = false;
        if (clearRequested) {
            clearRequested = false;
            if (!lines.isEmpty() || target.length() > 0) {
                target.load("");
                lines.clear();
                dropped = 0;
                changed = true;
            }
        }

        // ONE INSERT FOR THE WHOLE BURST, not one per line. Twenty lines a second is twenty edits a
        // second otherwise, each of which invalidates the editor's measurement caches -- and a burst
        // from a loop can be thousands.
        StringBuilder incoming = null;
        for (Line line = pending.poll(); line != null; line = pending.poll()) {
            if (incoming == null) incoming = new StringBuilder();
            incoming.append(line.text()).append('\n');
            lines.add(line);
        }
        if (incoming != null) {
            target.insert(target.length(), incoming.toString());
            changed = true;
        }

        return trimToBudget(target) || changed;
    }

    /**
     * Drops whole lines off the front until the document fits.
     *
     * <p><b>In batches, and that is not premature.</b> Trimming exactly one line per append makes every
     * append past the bound an O(n) shift of the line list, twenty times a second forever. Taking a
     * tenth at a time amortises it to nothing and costs only that the bound is approached in steps.</p>
     */
    private boolean trimToBudget(TextBuffer target) {
        if (target.length() <= budgetChars || lines.size() < 2) return false;

        int drop = Math.max(1, lines.size() / 10);
        int chars = 0;
        for (int i = 0; i < drop && i < lines.size() - 1; i++) chars += lines.get(i).text().length() + 1;
        if (chars <= 0) return false;

        target.delete(0, Math.min(chars, target.length()));
        lines.subList(0, Math.min(drop, lines.size() - 1)).clear();
        dropped += drop;
        return true;
    }

    /** The line at a row, or null past the end. Read by the tokenizer and by a click. */
    @Nullable
    public Line lineAt(int row) {
        return row < 0 || row >= lines.size() ? null : lines.get(row);
    }

    public int lineCount() {
        return lines.size();
    }

    /**
     * Adds a filter that decides which spans of a line are navigable — see {@link ConsoleFilter}.
     *
     * <p>A chain rather than one, so a Java stack frame, a GLSL compiler error and a URL are three small
     * classes rather than one that grows a branch per language.</p>
     */
    public RunConsole addFilter(ConsoleFilter filter) {
        if (filter != null) filters.add(filter);
        return this;
    }

    /**
     * The navigable spans on a row, recomputed from its text every time.
     *
     * <p><b>Never cached and never stored as document offsets.</b> The ring deletes from the front of the
     * document, which is an edit, so any held offset would begin describing the wrong text the moment the
     * bound is first reached — silently, since the transcript goes on working and only the destinations
     * are wrong. Recomputing cannot desync, and it runs over the rows on screen rather than the
     * transcript.</p>
     */
    public List<ConsoleFilter.Link> linksAt(int row) {
        if (filters.isEmpty()) return List.of();
        Line line = lineAt(row);
        // A DIVIDER IS OURS, not the script's. Running filters over a run boundary can only produce a
        // false positive, and a rule of dashes is exactly the kind of text a loose pattern matches.
        if (line == null || line.isDivider()) return List.of();

        List<ConsoleFilter.Link> found = null;
        for (ConsoleFilter filter : filters) {
            List<ConsoleFilter.Link> some = filter.apply(line.text());
            if (some == null || some.isEmpty()) continue;
            if (found == null) found = new ArrayList<>(some);
            else found.addAll(some);
        }
        if (found == null) return List.of();
        // SORTED, because the tokenizer walks them in order to emit the gaps between them, and two filters
        // answering the same line have no reason to agree about which comes first.
        found.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return found;
    }

    /**
     * How many lines the ring has evicted since the last clear.
     *
     * <p>Reported rather than silent: a transcript that quietly begins in the middle reads as the
     * console having missed something rather than as the bound having been reached.</p>
     */
    public long dropped() {
        return dropped;
    }

    public RunConsole setBudgetKb(int kilobytes) {
        budgetChars = Math.max(1, kilobytes) * 1024;
        return this;
    }

    public int budgetKb() {
        return budgetChars / 1024;
    }
}
