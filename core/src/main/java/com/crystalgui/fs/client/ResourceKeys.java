package com.crystalgui.fs.client;

import com.crystalgui.fs.Resource;

/**
 * What a per-resource store calls its files — one definition, because {@link Backup} and
 * {@link LocalHistory} must agree and neither owns the answer.
 *
 * <p>Not a general utility: it exists so the naming rule below is stated once. Both stores had their own
 * copy of it, which is the shape where the fourth fix lands in one file and the other keeps the bug.</p>
 */
final class ResourceKeys {

    private ResourceKeys() {
    }

    /**
     * A name derived from the resource, so one document has one file however many times it is written.
     *
     * <p><b>Hashed rather than escaped, and 64 bits rather than 32.</b> A resource contains {@code /}
     * and {@code :} and a storage name is a file name on the local disk, so something has to give — and
     * an escaping scheme gives the wrong thing: {@code Main.java} and {@code main.java} are two
     * resources and one file on Windows and macOS, so the second write would overwrite the first. A
     * collision here is somebody's unsaved work, which is why the readable half is a suffix and never
     * the key.</p>
     *
     * <p>Finding a file is the <em>directory's</em> job — one per workspace, one per store — so the name
     * only has to be unique.</p>
     */
    static String nameFor(Resource resource) {
        return Long.toHexString(fnv1a(resource.toString())) + "."
                + resource.name().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 64-bit FNV-1a — the hash {@code WorkbenchApplication.hashOfProjects} uses, for the same reason. */
    private static long fnv1a(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        return hash;
    }
}
