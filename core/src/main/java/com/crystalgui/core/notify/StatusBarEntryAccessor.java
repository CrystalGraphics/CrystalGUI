package com.crystalgui.core.notify;

import com.crystalgui.core.dispose.Disposable;

/**
 * A live handle on one status bar entry — VS Code's {@code IStatusbarEntryAccessor}.
 *
 * <p>Ported from {@code vs/workbench/services/statusbar/browser/statusbar.ts}.</p>
 *
 * <h3>Why a handle rather than a string key</h3>
 *
 * <p>{@code StatusBar.set(id, text)} keyed every write by a string, which meant two things that both went
 * wrong in practice. <b>A writer had to remember to withdraw its own entry</b> — a missed
 * {@code clear(id)} left a stale readout on the bar with nothing owning it — and <b>two writers that
 * happened to choose the same id silently shared one slot</b>, last mover winning, which is the exact
 * failure that made {@code Workbench.onStatus} unusable and got the bar keyed in the first place. Keying
 * it merely moved the collision from "one slot for everyone" to "one slot per string".</p>
 *
 * <p>An accessor is the entry's identity, so a collision is not expressible: two writers hold two
 * accessors whatever they call them. Lifetime becomes ownership rather than etiquette — the handle is a
 * {@link Disposable}, so it can be registered on a {@code Disposer} and released with whatever created it,
 * instead of depending on a teardown path that only runs when someone remembers to call it.</p>
 *
 * <p>The {@code id} passed to {@code addEntry} survives for what an id is actually for in the reference:
 * naming the entry in a "hide this" menu, and persisting that choice.</p>
 */
public interface StatusBarEntryAccessor extends Disposable {

    /**
     * Replaces what this entry says.
     *
     * <p>Silent when the new entry equals the old one, which is not a nicety: the caret readout is written
     * on every selection change and the shader graph's line-owner readout on every caret move, so an
     * unguarded update would announce on a per-frame path whether or not anything changed.</p>
     */
    void update(StatusBarEntry entry);

    /** What this entry currently says. */
    StatusBarEntry entry();

    /** Takes the entry off the bar. Idempotent. */
    @Override
    void dispose();
}
