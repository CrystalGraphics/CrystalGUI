package com.crystalgui.fs.client;

import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.fs.Resource;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>Unsaved work, written where the server cannot lose it</b> — VS Code's
 * {@code IWorkingCopyBackupService}, and the thing {@code files.hotExit} is built on.
 *
 * <h3>Client-side, deliberately</h3>
 *
 * <p>A save needs the <em>server</em>, so a workspace whose connection has gone is one where nothing
 * can be saved at all — and the server is exactly what may have gone away. A backup that needed the
 * wire would be unavailable in precisely the situation it exists for.</p>
 *
 * <h3>What it makes possible upstream</h3>
 *
 * <p>Closing the screen with unsaved work stops being a prompt. VS Code does not ask; it writes the
 * backups, closes, and offers them on the next open — which is right because the alternative is a modal
 * dialog between a person and quitting, at the one moment they have already decided.</p>
 */
public final class Backup {

    /** The record's own version, so a format change discards rather than misreads. */
    private static final int VERSION = 1;

    private final ConfigStorage storage;

    public Backup(ConfigStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Writes one document's unsaved content.
     *
     * <p>Debounced by the caller, not here: how often a document is worth backing up is a property of
     * how expensive it is to encode, which the document knows and this does not.</p>
     */
    public void save(Resource resource, byte[] content, @Nullable String etag) {
        if (!storage.isWritable()) return;
        Map<String, String> record = new LinkedHashMap<>();
        record.put("v", String.valueOf(VERSION));
        record.put("resource", resource.toString());
        record.put("etag", etag == null ? "" : etag);
        record.put("content", Base64.getEncoder().encodeToString(content));
        storage.write(ResourceKeys.nameFor(resource), encode(record));
    }

    /**
     * Carries a backup to where its file went.
     *
     * <p>The record names the resource it belongs to, so this rewrites it rather than moving the file:
     * a backup still claiming the old path would be restored to a document that has moved on from it.
     * <b>The etag is kept</b> — it is the one this content was in step with, and the restore compares
     * it against the file's to notice the file moved while the client was away, which is what happened.</p>
     *
     * <p>A backup already held for the destination is replaced. There is one record per resource and no
     * merging two documents' unsaved text, and the file that moved is the one now living there.</p>
     */
    public void rename(Resource from, Resource to) {
        if (from.equals(to) || !storage.isWritable()) return;
        Entry entry = read(ResourceKeys.nameFor(from));
        if (entry == null) return;
        save(to, entry.content(), entry.etag());
        storage.delete(ResourceKeys.nameFor(from));
    }

    /** Drops one — what a successful save does. */
    public void discard(Resource resource) {
        storage.delete(ResourceKeys.nameFor(resource));
    }

    /**
     * What is on offer, from a previous session or from this one before a crash.
     *
     * <p>Everything in the store, with no name filter: this store is a directory of backups and nothing
     * else. A record that will not read is dropped by {@link #read} rather than skipped here.</p>
     */
    public List<Entry> restorable() {
        List<Entry> out = new ArrayList<>();
        for (String name : storage.list()) {
            Entry entry = read(name);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    @Nullable
    public Entry get(Resource resource) {
        return read(ResourceKeys.nameFor(resource));
    }

    public void discardAll() {
        for (String name : storage.list()) storage.delete(name);
    }

    /**
     * One document's unsaved content, and the etag it was last in step with.
     *
     * <p>The etag is what makes a restored document honest: it comes back <b>dirty against that
     * etag</b>, so if the file moved while the client was away the next save is a conflict rather than
     * a silent overwrite of somebody else's work.</p>
     */
    public record Entry(Resource resource, byte[] content, String etag) {
    }

    @Nullable
    private Entry read(String name) {
        String raw = storage.read(name);
        if (raw == null || raw.isEmpty()) return null;
        Map<String, String> record = decode(raw);
        if (!String.valueOf(VERSION).equals(record.get("v"))) {
            // A record this build cannot read is DISCARDED rather than guessed at: restoring the wrong
            // bytes over somebody's file is worse than losing an edit they can retype.
            storage.delete(name);
            return null;
        }
        String resource = record.get("resource");
        String content = record.get("content");
        if (resource == null || content == null) return null;
        try {
            return new Entry(Resource.parse(resource), Base64.getDecoder().decode(content),
                    record.getOrDefault("etag", ""));
        } catch (RuntimeException unreadable) {
            storage.delete(name);
            return null;
        }
    }

    /** Line-per-field, because a backup must be readable when everything else has failed. */
    private static String encode(Map<String, String> record) {
        StringBuilder out = new StringBuilder();
        record.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) out.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return out;
    }

    /** Bytes as text, for a caller that has a String. */
    public void save(Resource resource, String content, @Nullable String etag) {
        save(resource, content.getBytes(StandardCharsets.UTF_8), etag);
    }
}
