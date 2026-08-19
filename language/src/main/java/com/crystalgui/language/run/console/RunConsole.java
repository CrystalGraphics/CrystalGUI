package com.crystalgui.language.run.console;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.exec.ScriptInput;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        private final boolean opening;
        @Nullable private final String origin;
        private final long millis;
        private final boolean typed;

        Line(String text, RunLevel level, String script, @Nullable Resource file, int line,
             boolean divider) {
            this(text, level, script, file, line, divider, false, null, 0L);
        }

        Line(String text, RunLevel level, String script, @Nullable Resource file, int line,
             boolean divider, boolean opening) {
            this(text, level, script, file, line, divider, opening, null, 0L);
        }

        Line(String text, RunLevel level, String script, @Nullable Resource file, int line,
             boolean divider, boolean opening, @Nullable String origin, long millis) {
            this(text, level, script, file, line, divider, opening, origin, millis, false);
        }

        Line(String text, RunLevel level, String script, @Nullable Resource file, int line,
             boolean divider, boolean opening, @Nullable String origin, long millis, boolean typed) {
            this.text = text;
            this.level = level;
            this.script = script;
            this.file = file;
            this.line = line;
            this.divider = divider;
            this.opening = opening;
            this.origin = origin;
            this.millis = millis;
            this.typed = typed;
        }

        /**
         * A line the <b>reader</b> typed, echoed back — not something the script printed.
         *
         * <p>It is in the transcript for the reason a terminal echoes: without it the exchange reads as
         * the script having answered its own question. But it is the one line in a console that did not
         * come from the program, and drawing it like output makes a conversation indistinguishable from
         * a monologue. IntelliJ colours its own input echo for exactly this.</p>
         */
        public boolean isTyped() {
            return typed;
        }

        /** {@code Ask.java:24} — the script's own line that printed this, or null. @see ConsolePrefix */
        @Nullable
        public String origin() {
            return origin;
        }

        /** When it was printed, in wall time. Zero for a boundary, which nothing stamps. */
        public long millis() {
            return millis;
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

        /**
         * The boundary that <b>opens</b> a run, as opposed to the one that closes it.
         *
         * <p>They are drawn differently on purpose. The opening line is a heading — it tells you where to
         * start reading — while the closing one is a footnote about a run you have already read. Giving
         * both the same faded grey is what made two runs of one script hard to tell apart: the seam was
         * there and looked like every other quiet line around it.</p>
         */
        public boolean isRunStart() {
            return opening;
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

    /**
     * What makes a span of a line navigable. Copy-on-write because a filter may be registered while the
     * UI thread is walking the chain to paint a row.
     */
    private final List<ConsoleFilter> filters = new CopyOnWriteArrayList<>();

    private final ConcurrentLinkedQueue<Line> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean clearRequested;

    /**
     * What {@link #pending} is holding, so the queue can be bounded without walking it.
     *
     * <h4>The ring did not cover this, and the gap was invisible</h4>
     *
     * <p>{@link #drain} is the only thing that trims, and it is called once a frame <b>by the panel</b> —
     * so a panel that is closed is a panel that is detached, its ticker unregisters, and nothing drains
     * at all. A script printing every tick then grew this queue without limit for as long as the console
     * stayed shut, which is precisely when nobody is watching for it. "The transcript is bounded" was
     * true of everything the reader could see and false of everything else.</p>
     *
     * <p>Approximate under concurrent appends, and that is fine: a bound trimmed a few lines early or
     * late is still a bound. {@code ConcurrentLinkedQueue} tolerates a producer polling, which is what
     * makes this safe to do from the printing thread rather than deferring it to a drain that may never
     * come.</p>
     */
    private final AtomicInteger pendingChars = new AtomicInteger();

    /** Lines dropped from {@link #pending} before they were ever shown, merged into {@link #dropped}. */
    private final AtomicLong pendingDropped = new AtomicLong();

    /**
     * <b>The whole transcript</b>, in arrival order — the truth the document is derived from, and what
     * the ring bounds. Touched only by {@link #drain}.
     *
     * <p>Separate from {@link #shown} because a filter makes the document a <em>subset</em>. Bounding the
     * document instead would leave this list unbounded whenever a filter is on, which is the one shape
     * where "the console is capped" quietly stops being true.</p>
     */
    private final List<Line> all = new ArrayList<>();

    /** Mirrors the attached buffer's rows exactly, one entry per row. A subsequence of {@link #all}. */
    private final List<Line> shown = new ArrayList<>();

    /** Running total of {@code text + '\n'} over {@link #all}, so the ring needs no re-measure. */
    private int allChars;

    /** Distinct script names in arrival order — what the filter picker offers. @see #scripts() */
    private final LinkedHashSet<String> scriptsSeen = new LinkedHashSet<>();

    /** The script whose output is shown, or null for everything. Applied in {@link #drain}. */
    @Nullable private String filter;

    @Nullable private volatile String requestedFilter;
    private volatile boolean filterRequested;

    @Nullable private TextBuffer buffer;
    private volatile int budgetChars = DEFAULT_BUDGET_KB * 1024;
    private long dropped;

    // ── Input: a console reads as well as writes ─────────────────────────────────────────────────

    /**
     * Where a submitted line is handed to the script blocked on {@code System.in}.
     *
     * <p>Capacity one, and {@code offer}/{@code take} rather than a {@code SynchronousQueue}: a
     * synchronous hand-off only succeeds while a taker is <em>already parked</em>, so a line submitted in
     * the instant between {@code awaitingInput} going true and the reader actually blocking would be
     * dropped — a race that costs the user their keystroke and looks like the field being ignored.</p>
     */
    private final ArrayBlockingQueue<String> input =
            new ArrayBlockingQueue<>(1);

    private volatile boolean awaitingInput;
    @Nullable private volatile String awaitingScript;

    /**
     * Blocks until a line is submitted. <b>Script thread only</b>, called by {@link ScriptInput}.
     *
     * @return the line, or null when the script was stopped while waiting
     */
    public @Nullable
    String awaitInput(@Nullable String script) {
        awaitingScript = script;
        awaitingInput = true;
        onDidChange.emit();
        try {
            return input.take();
        } catch (InterruptedException stopped) {
            // THE INTERRUPT IS THE KILL SWITCH, so it is restored rather than swallowed -- the script's
            // next injected safepoint is what actually ends it, and clearing the flag here would make a
            // script blocked on input the one place a stop does nothing.
            Thread.currentThread().interrupt();
            // AND ONLY HERE IS THE QUEUE DRAINED. A line offered to a request that was abandoned is not
            // for the next one. Draining on ENTRY instead looked equivalent and was not: between one read
            // returning and the next beginning, `awaitingInput` is still true and the field is still on
            // screen, so a line typed in that window is legitimately for the read about to start -- and
            // the entry drain threw it away. Two reads in a row hung on the second, every time.
            input.clear();
            return null;
        } finally {
            awaitingInput = false;
            awaitingScript = null;
            onDidChange.emit();
        }
    }

    /** Whether something is blocked reading {@code System.in} — what makes the input row appear. */
    public boolean isAwaitingInput() {
        return awaitingInput;
    }

    /**
     * Hands a line to whatever is waiting, and echoes it into the transcript. <b>UI thread.</b>
     *
     * <p>The echo is not decoration: a terminal shows what you typed, and without it the transcript reads
     * as the script having answered its own question. Attributed to the WAITING script rather than to
     * whoever is on screen, so a filter keeps the exchange together.</p>
     *
     * @return whether anything was waiting for it
     */
    public boolean submitInput(String line) {
        if (!awaitingInput) return false;
        String text = line == null ? "" : line;
        String script = awaitingScript;
        // MARKED AS TYPED, not appended as ordinary output. It has no origin -- nothing in the script
        // printed it -- and it is the one line in the transcript that did not come from the program, so
        // the view draws it apart. @see Line#isTyped
        enqueue(new Line(text, RunLevel.OUT, script == null ? "input" : script, null, 0,
                false, false, null, System.currentTimeMillis(), true));
        return input.offer(text);
    }

    // ── The producing side: any thread ───────────────────────────────────────────────────────────

    /** Enqueues one line. Safe from a script's thread, the game's, or anywhere else. */
    public void append(RunMessage message) {
        if (message == null) return;
        // STAMPED WHEN IT WAS PRINTED, not when it is drawn. A frame can be several lines behind a burst,
        // so a clock read at drain would give a whole burst the same time -- the time the panel got round
        // to it. The formatting still happens at drain, so the style can change without the transcript
        // having to be re-timed. @see ConsolePrefix
        enqueue(new Line(message.text(), message.level(), message.script(),
                message.file(), message.line(), false, false,
                message.origin(), System.currentTimeMillis()));
    }

    /** The one way anything reaches {@link #pending}, so the bound cannot be bypassed. */
    private void enqueue(Line line) {
        pending.add(line);
        pendingChars.addAndGet(charsOf(line));
        boundPending();
        onDidChange.emit();
    }

    /**
     * Drops the oldest <em>queued</em> lines when the queue outgrows the budget.
     *
     * <p>The same bound as {@link #trimToBudget}, applied at the other end of the pipe — because between
     * the two there is a queue nothing trims, and it is unbounded exactly while the panel is closed.
     * Counted into {@link #pendingDropped} rather than {@link #dropped} directly: that field belongs to
     * the UI thread, and this runs on whichever thread printed.</p>
     */
    private void boundPending() {
        while (pendingChars.get() > budgetChars) {
            Line oldest = pending.poll();
            if (oldest == null) {
                // Everything queued has been drained since the check above. Nothing to correct but the
                // counter, which a concurrent drain has already taken the characters off.
                return;
            }
            pendingChars.addAndGet(-charsOf(oldest));
            pendingDropped.incrementAndGet();
        }
    }

    /** What one line costs the ring — its text and the newline the document will give it. */
    private static int charsOf(Line line) {
        return line.text().length() + 1;
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
        String name = label == null ? "" : label;
        // JUST THE NAME. The break above it and the ordinal after it are both decided in `drain`, which
        // is the only place that knows what the transcript already holds -- and the only thread allowed
        // to ask. This one is called from whichever thread the run changed state on.
        enqueue(new Line(name, RunLevel.OUT, name, null, 0, true, true));
    }

    /**
     * How many times each script has been started since the last clear — what a header's ordinal counts.
     *
     * <p>Kept here rather than derived from {@link #all}, for the reason {@link #scripts()} is kept: the
     * ring evicts, and a count derived from surviving lines would <b>fall</b> as old runs aged out. "Run
     * 2" turning back into "run 1" is worse than no ordinal at all.</p>
     *
     * <p><b>Touched only by {@link #drain}</b>, which is what makes a plain map safe here — {@code
     * startRun} is called from whichever thread a run changed state on, and this is reset from the frame.
     * A clear is queued as well, so numbering at announce time would also count runs the clear was about
     * to forget.</p>
     */
    private final Map<String, Integer> runs = new HashMap<>();

    /**
     * Closes a run — the boundary that says the output above it is finished.
     *
     * <p>Two arguments rather than one, unlike {@link #startRun}: the opening divider's text <em>is</em>
     * the script's name, so one string serves as both the label and the owner, while a closing line reads
     * {@code Main.java finished in 1.2 sec} and still has to belong to {@code Main.java}. Attributing it
     * to its own text would put the summary in a filter bucket of its own — visible under All output and
     * missing from the one script whose run it describes.</p>
     *
     * @see RunSummary
     */
    public void endRun(String script, String summary) {
        if (summary == null || summary.isEmpty()) return;
        enqueue(new Line(summary, RunLevel.OUT, script == null ? "" : script, null, 0, true));
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

    /**
     * Drops everything one script ever printed — <b>a clear for one owner instead of all of them</b>.
     *
     * <p>The transcript is bounded but it is not selective: a script somebody ran once, hours ago, keeps
     * its share of the ring until the ring evicts it, and under <em>All output</em> its lines sit between
     * the ones being read. Clearing takes everything, which is a blunt answer when only one run is in the
     * way.</p>
     *
     * <p>Queued and applied in {@link #drain} like the clear and the filter, for the same reason: the
     * document may only be touched on the thread that draws it, and a menu row should not have to know
     * that.</p>
     */
    public void forget(String script) {
        if (script == null || script.isEmpty()) return;
        forgetting.add(script);
        onDidChange.emit();
    }

    /** Scripts whose lines are to be dropped, applied in {@link #drain}. @see #forget */
    private final ConcurrentLinkedQueue<String> forgetting = new ConcurrentLinkedQueue<>();

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
        all.clear();
        shown.clear();
        scriptsSeen.clear();
        runs.clear();
        allChars = 0;
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
        // BEFORE THE CLEAR, so a clear zeroes these along with everything else it forgets. Not counted
        // as a document change: nothing was written, something was lost -- the notice reads `dropped()`
        // on its own. @see #boundPending
        dropped += pendingDropped.getAndSet(0);
        if (clearRequested) {
            clearRequested = false;
            if (!all.isEmpty() || target.length() > 0) {
                target.load("");
                all.clear();
                shown.clear();
                scriptsSeen.clear();
                runs.clear();
                allChars = 0;
                dropped = 0;
                changed = true;
            }
        }

        // ONE OWNER'S LINES, DROPPED. Before the filter rebuild below rather than after, so a forget and
        // a filter change arriving in the same frame cost one rebuild rather than two.
        if (!forgetting.isEmpty()) {
            for (String polled = forgetting.poll(); polled != null; polled = forgetting.poll()) {
                final String name = polled;
                all.removeIf(line -> name.equals(line.script()));
                scriptsSeen.remove(name);
                runs.remove(name);
                // AND THE FILTER GOES WITH IT. A console narrowed to a script that no longer exists shows
                // an empty document with no row selected to explain why -- which reads as the transcript
                // having been cleared rather than as the filter outliving its subject.
                if (name.equals(filter)) filter = null;
            }
            // RE-MEASURED, not adjusted. The running total is what the ring trims against, and a total
            // left describing lines that are gone would evict from a transcript that had just shrunk.
            allChars = 0;
            for (Line line : all) allChars += charsOf(line);
            rebuild(target);
            changed = true;
        }

        // A RESTYLE IS A REBUILD TOO, for the same reason and through the same door.
        if (prefixRestyled) {
            prefixRestyled = false;
            rebuild(target);
            changed = true;
        }

        // A FILTER CHANGE IS A REBUILD, and it happens HERE rather than in the setter for the reason the
        // clear does: the document may only be touched on the thread that draws it, and a caller wiring a
        // rail row or a menu item should not have to know that.
        if (filterRequested) {
            filterRequested = false;
            filter = requestedFilter;
            rebuild(target);
            changed = true;
        }

        // ONE INSERT FOR THE WHOLE BURST, not one per line. Twenty lines a second is twenty edits a
        // second otherwise, each of which invalidates the editor's measurement caches -- and a burst
        // from a loop can be thousands.
        // THE BREAK BEFORE A RUN IS DECIDED HERE, not when the run was announced.
        //
        // A blank line is what makes the seam between two runs of one script findable without reading —
        // vertical space is what every terminal and log viewer uses for "a new section", and it needs no
        // glyph anybody's font might lack. But whether there is anything to be separated FROM is a
        // question only this method can answer: a clear is queued too, so a run started right after one
        // would otherwise open with a blank row above the top of an empty console.
        List<Line> batch = null;
        for (Line polled = pending.poll(); polled != null; polled = pending.poll()) {
            pendingChars.addAndGet(-charsOf(polled));
            if (batch == null) batch = new ArrayList<>();
            Line line = polled;
            if (line.isRunStart()) {
                // THE ORDINAL IS NUMBERED HERE, for two reasons that agree. A clear is queued, so a count
                // incremented when the run was announced would still be counting runs the clear was about
                // to forget -- "Loud.java (run 5)" as the first line of a console somebody had just
                // emptied. And `startRun` is called from the script's own thread while this map is reset
                // from the frame, which is a plain HashMap being written from two threads.
                int ordinal = runs.merge(line.script(), 1, Integer::sum);
                if (ordinal > 1) {
                    line = new Line(line.script() + " (run " + ordinal + ")",
                            line.level(), line.script(), null, 0, true, true);
                }
            }
            Line previous = batch.isEmpty()
                    ? (all.isEmpty() ? null : all.get(all.size() - 1))
                    : batch.get(batch.size() - 1);
            if (needsBreakBetween(previous, line)) {
                batch.add(new Line("", line.level(), line.script(), null, 0, true, false));
            }
            batch.add(line);
        }

        StringBuilder incoming = null;
        for (Line line : batch == null ? List.<Line>of() : batch) {
            all.add(line);
            allChars += charsOf(line);
            if (line.script() != null && !line.script().isEmpty()) scriptsSeen.add(line.script());
            if (!passes(line)) continue;
            if (incoming == null) incoming = new StringBuilder();
            incoming.append(display(line)).append('\n');
            shown.add(line);
        }
        // A BURST FILTERED DOWN TO NOTHING IS NOT A CHANGE. Every line may belong to another script, and
        // inserting an empty string would still mark the document dirty and re-measure every realised row.
        if (incoming != null && incoming.length() > 0) {
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
        // MEASURED ON THE TRANSCRIPT, not on the document. Under a filter the document is a subset, so a
        // document-sized bound would let the retained transcript grow without limit -- the memory the
        // bound exists to cap, uncapped in exactly the state somebody turned a filter on to survive.
        if (allChars <= budgetChars || all.size() < 2) return false;

        int drop = Math.min(Math.max(1, all.size() / 10), all.size() - 1);
        int chars = 0;
        int shownDrop = 0;
        int shownChars = 0;
        for (int i = 0; i < drop; i++) {
            Line line = all.get(i);
            chars += line.text().length() + 1;
            // IDENTITY, and it is sound: `shown` holds the same Line instances in the same order, so the
            // evicted prefix of `all` maps onto a prefix of `shown` by walking the two together.
            if (shownDrop < shown.size() && shown.get(shownDrop) == line) {
                shownChars += line.text().length() + 1;
                shownDrop++;
            }
        }
        if (chars <= 0) return false;

        all.subList(0, drop).clear();
        allChars -= chars;
        if (shownDrop > 0) {
            shown.subList(0, shownDrop).clear();
            target.delete(0, Math.min(shownChars, target.length()));
        }
        // COUNTED OVER THE TRANSCRIPT, because that is what was lost. Reporting only the rows that
        // happened to be on screen would say "nothing was dropped" while a filtered-out script's output
        // was being discarded, which is the case a reader most needs told.
        dropped += drop;
        return true;
    }

    /**
     * Whether a blank line belongs between two lines of the transcript.
     *
     * <h4>A boundary is set apart on BOTH sides, or it is just another line</h4>
     *
     * <p>The rule reads as two, and they are the same one: <b>a break before every boundary</b>, so the
     * line that closes a run is not jammed against the last thing the run printed; and <b>a break after
     * an opening one</b>, so the header is not jammed against the first. Written as "before a divider"
     * alone, the header ended up flush with the output it labels — and the closing line flush with the
     * output above it, which is the one the eye most needs separated, since that output is what you were
     * reading when the run ended.</p>
     *
     * <p>Never two in a row, and never one at the very top: a transcript that opens with a blank row has
     * pushed its first line down for nothing. A script that prints its own blank line satisfies the rule
     * as well as an inserted one does, which is why this asks about the TEXT rather than about who
     * inserted it.</p>
     */
    private static boolean needsBreakBetween(@Nullable Line previous, Line next) {
        if (previous == null) return false;
        if (previous.text().isEmpty()) return false;
        return next.isDivider() || previous.isRunStart();
    }

    /**
     * How many of a row's columns are the stamp rather than the line itself.
     *
     * <p><b>Everything that colours or points at a row needs this</b>, and each of them gets it wrong in
     * its own way without it. A link's columns come from a filter that was handed the script's text and
     * has never heard of a prefix, so they have to be moved right. A line's colour runs to the end of the
     * row, so it has to start further right — and it did not: an echoed input line painted its own
     * timestamp green and italic along with the words, which reads as the stamp belonging to the line
     * rather than to the console.</p>
     *
     * <p>Zero for a boundary, which is never stamped, and zero when nothing is.</p>
     */
    public int stampWidth(int row) {
        Line line = lineAt(row);
        if (line == null || line.isDivider() || prefixStyle == ConsolePrefix.Style.NONE) return 0;
        return ConsolePrefix.of(prefixStyle, line.millis(), line.origin()).length();
    }

    /**
     * The row as it is drawn — the stamp, then what the script said.
     *
     * <p><b>Boundaries are never stamped.</b> They are the console talking about itself rather than
     * output, and a timestamp on a heading would claim a run started at the moment the panel drew it.
     * Leaving them flush at the margin also sets them apart from the indented output around them, which
     * is the separation the whole boundary treatment exists for.</p>
     */
    private String display(Line line) {
        if (line.isDivider() || prefixStyle == ConsolePrefix.Style.NONE) return line.text();
        return ConsolePrefix.of(prefixStyle, line.millis(), line.origin()) + line.text();
    }

    /**
     * What each row is stamped with. Applied like the filter — see {@link #drain}.
     *
     * <p>A style change rewrites every row, so it is a rebuild rather than something that takes effect
     * from the next line onward: half a transcript stamped and half not would read as the clock having
     * stopped.</p>
     */
    public RunConsole setPrefixStyle(ConsolePrefix.Style style) {
        ConsolePrefix.Style wanted = style == null ? ConsolePrefix.Style.NONE : style;
        if (wanted == prefixStyle) return this;
        prefixStyle = wanted;
        prefixRestyled = true;
        onDidChange.emit();
        return this;
    }

    public ConsolePrefix.Style prefixStyle() {
        return prefixStyle;
    }

    private volatile ConsolePrefix.Style prefixStyle = ConsolePrefix.Style.NONE;
    private volatile boolean prefixRestyled;

    private boolean passes(Line line) {
        return filter == null || filter.equals(line.script());
    }

    /** Re-derives the document from {@link #all}. One load, not one insert per surviving row. */
    private void rebuild(TextBuffer target) {
        shown.clear();
        StringBuilder text = new StringBuilder();
        for (Line line : all) {
            if (!passes(line)) continue;
            shown.add(line);
            text.append(display(line)).append('\n');
        }
        // SELECTION IS LOST HERE, unavoidably: it names offsets that no longer exist. IntelliJ loses it
        // switching console tabs too. Worth stating rather than discovering.
        target.load(text.toString());
    }

    /**
     * Shows only {@code script}'s output, or everything when null. <b>Any thread.</b>
     *
     * <p>Queued like {@link #clear}, applied in {@link #drain} — see there for why.</p>
     */
    public RunConsole setFilter(@Nullable String script) {
        if (Objects.equals(filterRequested ? requestedFilter : filter, script)) return this;
        requestedFilter = script;
        filterRequested = true;
        onDidChange.emit();
        return this;
    }

    /** The script currently shown, or null for everything. The APPLIED one, not a pending request. */
    @Nullable
    public String filter() {
        return filter;
    }

    /**
     * Every script that has written to this transcript, in first-seen order.
     *
     * <p><b>Kept, not derived</b>, and the first attempt had it the other way round on the reasoning that
     * "which scripts can I filter to" is asked when a menu opens. It is not: the picker compares this list
     * every time the console changes, so deriving it walked the whole transcript — up to the ring's whole
     * megabyte — on every frame output was flowing.</p>
     *
     * <p><b>And the ring deliberately does not unwind it.</b> A script whose every line has been evicted
     * still ran, and is still something a reader may want to filter to; dropping it from the picker the
     * moment its output aged out would make the control's contents depend on how chatty its neighbours
     * have been. Only {@link #clear()} empties it, which is the one action that means "forget this run".</p>
     */
    public List<String> scripts() {
        return List.copyOf(scriptsSeen);
    }

    /** How many lines the transcript holds, filtered or not — what the ring bounds. */
    public int transcriptSize() {
        return all.size();
    }

    /** The line at a row, or null past the end. Read by the tokenizer and by a click. */
    @Nullable
    public Line lineAt(int row) {
        return row < 0 || row >= shown.size() ? null : shown.get(row);
    }

    /** Rows in the document — i.e. after filtering. @see #transcriptSize */
    public int lineCount() {
        return shown.size();
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

        // SHIFTED PAST THE STAMP. A filter is handed the script's own text -- that is what keeps it
        // testable and keeps the console's decoration out of it -- but the ROW on screen begins with the
        // prefix, and both the underline and the click test are computed in row columns. Unshifted, every
        // link on a stamped line is underlined a dozen characters to the left of the text it names, and a
        // click in the middle of it opens nothing.
        int shift = stampWidth(row);
        if (shift == 0) return found;
        List<ConsoleFilter.Link> shifted = new ArrayList<>(found.size());
        for (ConsoleFilter.Link link : found) {
            shifted.add(new ConsoleFilter.Link(link.start() + shift, link.end() + shift,
                    link.fileName(), link.line()));
        }
        return shifted;
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
