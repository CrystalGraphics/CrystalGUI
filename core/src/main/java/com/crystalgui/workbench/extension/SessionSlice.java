package com.crystalgui.workbench.extension;

import com.google.gson.JsonElement;

import com.crystalgui.serialization.StateMap;

/**
 * <b>An extension's own corner of the session record</b> - what your feature remembers between runs.
 *
 * <p>The engine already serialises what it owns: the dock layout, each tool window's placement, per-tab
 * view state, the active file. This is for what a <em>feature</em> remembers, which the engine cannot
 * reach - the explorer's expanded folders are the first one. Register it in {@code activate} and dispose
 * the handle with the rest.</p>
 *
 * <pre>{@code
 * Disposable slice = workbench.registerSessionSlice(new SessionSlice() {
 *     public String id() { return "crystalgui:explorer"; }
 *     public void write(StateMap<JsonElement> into) { into.putList("expanded", ...); }
 *     public void read(StateMap<JsonElement> from)  { ... }
 * });
 * }</pre>
 *
 * <p>It is written under {@code "extensions": { "<id>": {...} }} inside the same record, so adding one
 * needs no version bump: the key is additive, and a reader that has never heard of your slice ignores
 * it. Same line IntelliJ's {@code PersistentStateComponent} draws - one component, one corner.</p>
 *
 * <h3>Reading is not the same moment as restoring</h3>
 *
 * <p>{@link #read} hands over the bytes and nothing more. Anything you cannot apply <em>yet</em> - a
 * folder you cannot expand until the listing revealing it arrives - is your own retry, and
 * {@code WorkspaceProjects.onDidLoadListing()} is the signal to hang it on: it fires per listing, which
 * is the only moment the answer can have changed. Do not poll for it in a frame hook.</p>
 */
public interface SessionSlice {

    /**
     * The key this slice is written under — <b>the extension's own id</b>.
     *
     * <p>Not a name of its own, so a record can be read back to the extension that wrote it and an
     * application that no longer enables a feature leaves that feature's corner untouched rather than
     * dropping it.</p>
     */
    String id();

    /** Called when the workbench's arrangement is saved. */
    void write(StateMap<JsonElement> into);

    /**
     * Called when it is restored, with whatever was written last time — <b>or an empty map</b>, which is
     * an ordinary first run and never an error.
     */
    void read(StateMap<JsonElement> from);
}
