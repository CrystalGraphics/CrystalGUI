package com.crystalgui.workbench.extension;

import com.google.gson.JsonElement;

import com.crystalgui.serialization.StateMap;

/**
 * <b>An extension's own corner of the session record</b> — its share of what a workbench remembers
 * between runs.
 *
 * <p>The engine serialises what it owns: the dock layout, every tool window's placement, per-tab view
 * state, the active file. What it cannot serialise is what a FEATURE remembers, because once the
 * explorer is an extension {@code WorkbenchSession} has no way to reach it — and reaching for it was
 * the arrangement being removed. IntelliJ's {@code PersistentStateComponent} draws the line in the same
 * place: one component, one corner, one file.</p>
 *
 * <pre>{@code
 * workbench.registerSessionSlice(new SessionSlice() {
 *     public String id() { return "crystalgui:explorer"; }
 *     public void write(StateMap<JsonElement> into) { ... }
 *     public void read(StateMap<JsonElement> from) { ... }
 * });
 * }</pre>
 *
 * <p>Written under {@code "extensions": { "<id>": {...} }} in the same record, so no version bump: the
 * key is additive and a reader that has never heard of a slice ignores it.</p>
 *
 * <h3>Reading is not the same moment as restoring</h3>
 *
 * <p>{@link #read} hands over the bytes and nothing more. Anything an extension wants that is not there
 * <em>yet</em> — a folder it cannot expand until the listing revealing it lands — is its own retry, and
 * {@code WorkspaceProjects.onDidLoadListing()} is what it hangs that on: per listing, which is the only
 * moment the answer can have changed. The session used to run that loop itself, per frame, with an
 * attempt counter, for the one feature that needed it.</p>
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
