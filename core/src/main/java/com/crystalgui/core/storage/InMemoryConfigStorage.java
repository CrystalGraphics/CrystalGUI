package com.crystalgui.core.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A {@link ConfigStorage} that keeps everything in a map.
 *
 * <p>What every test uses, and what the harness runs on so a scene never writes into a developer's real
 * configuration. A full implementation rather than a stub: a test that saves preferences and reloads them
 * exercises the same code production does, with only the bytes' destination different.</p>
 */
public final class InMemoryConfigStorage implements ConfigStorage {

    private final Map<String, String> blobs = new LinkedHashMap<>();
    private boolean writable = true;

    @Nullable
    @Override
    public String read(String name) {
        return blobs.get(name);
    }

    @Override
    public void write(String name, String contents) {
        if (!writable) throw new IllegalStateException("Config storage is read-only");
        blobs.put(name, contents);
    }

    @Override
    public List<String> list() {
        return new ArrayList<>(blobs.keySet());
    }

    @Override
    public void delete(String name) {
        blobs.remove(name);
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    /** Makes every write fail, so the read-only path is reachable without a read-only disk. */
    public InMemoryConfigStorage setWritable(boolean writable) {
        this.writable = writable;
        return this;
    }
}
