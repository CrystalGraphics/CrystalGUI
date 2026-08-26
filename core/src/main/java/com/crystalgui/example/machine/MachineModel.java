package com.crystalgui.example.machine;

/**
 * <b>Step 1 — the truth the server owns.</b>
 *
 * <p>Some machine somewhere in a world: it can be switched on, it runs at a throughput the player
 * chooses, it works through a cycle, and it has a name. Whether anybody is <em>looking</em> at it is
 * none of its business.</p>
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
 * "state delta" is, and it never names a widget.</p>
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

    /** Fired whenever anything above changes. See the class javadoc for why it is a bare Runnable. */
    private Runnable onChanged = () -> { };

    public void onChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> { } : listener;
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

    public void setRunning(boolean value) {
        if (running == value) return;
        running = value;
        onChanged.run();
    }

    public void setThroughput(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == throughput) return;
        throughput = clamped;
        onChanged.run();
    }

    public void setLabel(String value) {
        String cleaned = value == null ? "" : value;
        if (cleaned.equals(label)) return;
        label = cleaned;
        onChanged.run();
    }

    /** Abandons the current cycle. What the panel's Purge button is wired to. */
    public void purge() {
        if (progress == 0f) return;
        progress = 0f;
        onChanged.run();
    }

    /**
     * One world tick.
     *
     * <p>Returns without firing when the machine is stopped, which matters more than it looks: this
     * is called every tick forever, and a model that announced a change each time would hand the
     * session a dirty set on every single tick and turn a quiet panel into constant traffic.</p>
     */
    public void tick() {
        if (!running) return;
        progress += CYCLE_SPEED * (0.25f + throughput);
        if (progress >= 1f) {
            progress = 0f;
            completedCycles++;
        }
        onChanged.run();
    }
}
