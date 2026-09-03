package com.crystalgui.fs.client;

import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * <b>What was in this file at each of the last few saves</b> — IntelliJ's Local History, VS Code's
 * {@code workingCopyHistory} behind its Timeline.
 *
 * <h3>Two things it makes possible</h3>
 *
 * <p><b>"Keep mine" is recoverable</b> — a conflict resolved by overwriting would otherwise be the end
 * of the other person's version, with nowhere it survives. And it supplies <b>the merge base</b>: a
 * three-way merge needs the common ancestor, and the last entry before this session's edits is exactly
 * that, on disk rather than in a cache any read could evict.</p>
 *
 * <h3>Client-local, bounded, and skipped for large files</h3>
 *
 * <p>The server has the file; what it does not have is what this client had before. Bounded by count
 * and by age so a person saving a thousand times a day still has a bounded store, and never written
 * for a document above the first size tier — a 50 MB log's history is a way to fill a disk.</p>
 */
public final class LocalHistory {

    private static final String PREFIX = "history.";

    /** How many entries are kept per file. VS Code's default is 50; ten covers a working session. */
    public static final int DEFAULT_ENTRIES_PER_FILE = 10;

    /** How old an entry may get. A fortnight, after which it is nobody's working memory. */
    public static final long DEFAULT_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000;

    /** Above this a file gets no history at all. The first size tier. */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final ConfigStorage storage;
    private final LongSupplier clockMillis;
    private final int entriesPerFile;
    private final long maxAgeMillis;

    public LocalHistory(ConfigStorage storage) {
        this(storage, System::currentTimeMillis, DEFAULT_ENTRIES_PER_FILE, DEFAULT_MAX_AGE_MILLIS);
    }

    public LocalHistory(ConfigStorage storage, LongSupplier clockMillis, int entriesPerFile,
                        long maxAgeMillis) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clockMillis = clockMillis;
        this.entriesPerFile = Math.max(1, entriesPerFile);
        this.maxAgeMillis = maxAgeMillis;
    }

    /**
     * Records what was saved.
     *
     * <p>Called <b>after</b> a successful save, with the bytes that were written — so an entry always
     * describes something the file genuinely held, and never an edit that failed to reach disk.</p>
     */
    public void record(Resource resource, byte[] content) {
        if (!storage.isWritable() || content.length > MAX_FILE_BYTES) return;
        List<Entry> entries = new ArrayList<>(entriesOf(resource));
        entries.add(new Entry(clockMillis.getAsLong(), content));
        prune(entries);
        storage.write(nameFor(resource), encode(entries));
    }

    /** What this file held at each save, newest first. */
    public List<Entry> entriesOf(Resource resource) {
        String raw = storage.read(nameFor(resource));
        if (raw == null || raw.isEmpty()) return List.of();
        List<Entry> entries = decode(raw);
        prune(entries);
        List<Entry> newestFirst = new ArrayList<>(entries);
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * The version this session started from — <b>the merge base</b>.
     *
     * <p>The newest entry, which is what the file held when it was last in step with this client. A
     * three-way merge of the server's copy and the buffer's needs exactly this, and the workbench used
     * a cache entry that any read could evict.</p>
     */
    @Nullable
    public byte[] mergeBase(Resource resource) {
        List<Entry> entries = entriesOf(resource);
        return entries.isEmpty() ? null : entries.get(0).content();
    }

    public void forget(Resource resource) {
        storage.delete(nameFor(resource));
    }

    /** One save's worth of content. */
    public record Entry(long at, byte[] content) {
    }

    /** Oldest first out, and anything past its age with them. */
    private void prune(List<Entry> entries) {
        long now = clockMillis.getAsLong();
        entries.removeIf(entry -> now - entry.at() > maxAgeMillis);
        while (entries.size() > entriesPerFile) entries.remove(0);
    }

    private static String nameFor(Resource resource) {
        return PREFIX + Integer.toHexString(resource.toString().hashCode()) + "."
                + resource.name().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * One line per entry: {@code <millis> <base64>}.
     *
     * <p>Whole snapshots rather than deltas against the previous one, which the plan proposed and which
     * is not worth it here: ten entries of a source file is under a megabyte, and a delta chain has a
     * failure mode — one corrupt link loses every entry after it — that a bounded list of snapshots
     * does not. Delta storage is worth revisiting when the cap is raised, not before.</p>
     */
    private static String encode(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append(entry.at()).append(' ')
                    .append(Base64.getEncoder().encodeToString(entry.content())).append('\n');
        }
        return out.toString();
    }

    private static List<Entry> decode(String raw) {
        List<Entry> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            int space = line.indexOf(' ');
            if (space <= 0) continue;
            try {
                out.add(new Entry(Long.parseLong(line.substring(0, space)),
                        Base64.getDecoder().decode(line.substring(space + 1))));
            } catch (RuntimeException skip) {
                // One unreadable line loses one entry, not the file's whole history.
            }
        }
        return out;
    }
}
