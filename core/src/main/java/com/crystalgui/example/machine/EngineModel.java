package com.crystalgui.example.machine;

/**
 * <b>Step 1b — a slice of the truth.</b>
 *
 * <p>The machine's engine: it is driven harder or softer, it heats up doing it, and it trips when it
 * gets too hot. {@link MachineModel} owns one, ticks it, and stops itself when it stalls.</p>
 *
 * <h3>Why this is a separate object at all</h3>
 *
 * <p>Because the UI is going to be separate too. {@code EnginePanel} is a nested
 * {@link com.crystalgui.net.window.Networked} panel inside {@code MachinePanel}, and what a nested
 * panel is handed is a <b>slice</b> — the narrowest thing it honestly needs. Its {@code serve} and
 * {@code tick} take an {@code EngineModel}, so it is not that the panel has been asked politely not
 * to touch the rest of the machine: <b>it cannot name it.</b> The signature is the boundary.</p>
 *
 * <p>Which is why the slice is worth carving in the model rather than passing the whole machine and
 * hoping. Handing a child the parent's model works, compiles, and quietly makes the child a second
 * place that knows everything — and the first time two of them disagree about who owns a field, the
 * split has to be done anyway, retroactively, through code that already reached across it.</p>
 *
 * <h3>Ownership is enforced, not asked for</h3>
 *
 * <p>The constructor and {@link #tick} are <b>package-private</b>. Only {@link MachineModel} is in
 * this package, so only {@link MachineModel} can make one or drive one; the panel — one package
 * over, in {@code .ui} — sees exactly the operator's controls: {@link #setLoad} and
 * {@link #restart}, plus the readings. That asymmetry is the actual design. A child UI is trusted to
 * change what the user is allowed to change and is not trusted to advance the world.</p>
 *
 * <h3>Read the import list</h3>
 *
 * <p>There isn't one, for the same reason {@link MachineModel} has none. And no listeners, for the
 * same reason too: a panel projects the engine, reading it on the tick, so the engine never learns a
 * window exists.</p>
 */
public final class EngineModel {

    /** Heat added per tick at full load, while the machine is drawing on the engine. */
    private static final float HEAT_PER_TICK = 0.02f;

    /** Heat lost per tick when nothing is drawing on it. Slower than the rise, as heat is. */
    private static final float COOL_PER_TICK = 0.01f;

    /** How hot a restart leaves it. Never a reset — a restarted engine is still a hot one. */
    private static final float RESTART_TEMPERATURE = 0.6f;

    /** 0..1 — how hard the operator is driving it. Faster cycles, hotter engine. */
    private float load = 0.4f;

    /** 0..1 — 1 is the trip point. */
    private float temperature;

    private boolean stalled;

    /** Package-private: an engine belongs to a machine, and is made by one. */
    EngineModel() {
    }

    public float load() {
        return load;
    }

    public float temperature() {
        return temperature;
    }

    public boolean isStalled() {
        return stalled;
    }

    /**
     * What the machine multiplies its cycle rate by — {@code 0.5} idling up to {@code 1.0} at full
     * load, and zero while stalled. The engine is the machine's <em>rate</em>, never its state.
     */
    public float output() {
        return stalled ? 0f : 0.5f + 0.5f * load;
    }

    /** What the operator asked for. The panel's slider, and its {@code engine/tune} method. */
    public void setLoad(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == load) return;
        load = clamped;
    }

    /**
     * Clears a stall. Drops the temperature to something workable but <b>never raises it</b> — an
     * engine that has cooled all the way down while stalled does not get warmed up by being started.
     */
    public void restart() {
        if (!stalled) return;
        stalled = false;
        temperature = Math.min(temperature, RESTART_TEMPERATURE);
    }

    /**
     * One world tick. Package-private, and driven by {@link MachineModel#tick()}. A cooling engine
     * settles at zero on its own.
     *
     * @param driving whether the machine is currently drawing on it
     */
    void tick(boolean driving) {
        if (driving && !stalled) {
            temperature = Math.min(1f, temperature + HEAT_PER_TICK * (0.2f + load));
            if (temperature >= 1f) stalled = true;
        } else if (temperature > 0f) {
            // A stalled engine cools too -- that is what makes a restart worth waiting for.
            temperature = Math.max(0f, temperature - COOL_PER_TICK);
        }
    }
}
