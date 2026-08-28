package com.crystalgui.example.machine;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 1 — the truth the server owns.</b>
 *
 * <p>Some machine somewhere in a world: it can be switched on, it runs at a throughput the player
 * chooses, it works through a cycle, and it has a name. Whether anybody is <em>looking</em> at it is
 * none of its business.</p>
 *
 * <p>It also has an {@link EngineModel}, which it <b>owns</b> — made here, ticked here, announcing
 * through here. That object exists as a separate thing for a reason worth reading before writing a
 * second one: it is the <b>slice</b> a nested UI is handed, and carving it in the model is what lets
 * the child panel be unable to name the rest of the machine rather than merely asked not to.</p>
 *
 * <h3>Read the import list</h3>
 *
 * <p>There isn't one. That is the whole point of this class and the reason it is first.</p>
 *
 * <p>A model that imports {@code UIElement} has quietly become a widget, and from then on the only
 * way to ask it a question is to build a tree — which on a dedicated server means building a tree
 * that can never be drawn, in a process with no fonts. Keeping the domain ignorant of the UI is not
 * tidiness here; it is what lets the same object be ticked by the world, saved to disk, and consulted
 * by a command, with a panel open or without one.</p>
 *
 * <p>The one concession to the UI is {@link #onChanged}, and note what it is: a bare
 * {@link Runnable}. The model announces that <em>something</em> moved. It does not know what a
 * "state delta" is, and it never names a widget. Listeners are a <b>list</b> with an unsubscribe
 * handle, because one machine may have any number of panels watching it at once — every player who
 * opened the GUI has one.</p>
 */
public final class MachineModel {

    /** How much of a cycle one tick completes at full throughput. Slow enough to watch. */
    private static final float CYCLE_SPEED = 0.02f;

    private boolean running;

    /** 0..1 — what the player asked for. */
    private float throughput = 0.5f;

    /** 0..1 — how far through the current cycle. */
    private float progress;

    private String label = "Machine 01";

    private int completedCycles;

    /**
     * The machine's engine — <b>owned here</b>, and the slice a nested UI is handed.
     *
     * <p>It announces through this machine's own {@link #changed()}, so a watcher subscribes once and
     * hears about both. That is what lets {@link EngineModel} have no listener list of its own, and it
     * is why the constructor is package-private: an engine is made by the machine it belongs to.</p>
     */
    private final EngineModel engine = new EngineModel(this::changed);

    /** Fired whenever anything above changes. See the class javadoc for why these are bare Runnables. */
    private final List<Runnable> onChanged = new ArrayList<>();

    /**
     * Subscribes, and returns the <b>unsubscribe</b> — a panel holds it and runs it when its window
     * closes, or a machine that outlives its viewers accumulates listeners for windows long gone.
     */
    public Runnable onChanged(Runnable listener) {
        if (listener == null) return () -> { };
        onChanged.add(listener);
        return () -> onChanged.remove(listener);
    }

    private void changed() {
        for (Runnable listener : new ArrayList<>(onChanged)) listener.run();
    }

    public boolean isRunning() {
        return running;
    }

    public float throughput() {
        return throughput;
    }

    public float progress() {
        return progress;
    }

    public String label() {
        return label;
    }

    public int completedCycles() {
        return completedCycles;
    }

    /**
     * The engine — <b>the slice a nested panel is served with</b>.
     *
     * <p>{@code MachinePanel.serve} passes exactly this to {@code io.attach(engine, model.engine())},
     * and from there the child's every hook takes an {@link EngineModel} and nothing wider.</p>
     */
    public EngineModel engine() {
        return engine;
    }

    public void setRunning(boolean value) {
        if (running == value) return;
        running = value;
        changed();
    }

    public void setThroughput(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == throughput) return;
        throughput = clamped;
        changed();
    }

    public void setLabel(String value) {
        String cleaned = value == null ? "" : value;
        if (cleaned.equals(label)) return;
        label = cleaned;
        changed();
    }

    /** Abandons the current cycle. What the panel's Purge button is wired to. */
    public void purge() {
        if (progress == 0f) return;
        progress = 0f;
        changed();
    }

    /**
     * One world tick.
     *
     * <p>Returns without firing when the machine is stopped, which matters more than it looks: this
     * is called every tick forever, and a model that announced a change each time would hand the
     * session a dirty set on every single tick and turn a quiet panel into constant traffic.</p>
     *
     * <p>The engine is ticked <b>either way</b> — a stopped machine's engine is cooling, which is
     * something happening — and it keeps the same rule for itself, announcing only when a reading
     * actually moved and going silent once it has cooled to nothing.</p>
     */
    public void tick() {
        engine.tick(running);
        if (!running) return;
        if (engine.isStalled()) {
            // The engine tripped, so the machine stops. setRunning announces, and does nothing at all
            // if something else already stopped us this tick.
            setRunning(false);
            return;
        }
        progress += CYCLE_SPEED * (0.25f + throughput) * engine.output();
        if (progress >= 1f) {
            progress = 0f;
            completedCycles++;
        }
        changed();
    }
}
