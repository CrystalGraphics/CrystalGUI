package com.crystalgui.app.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>Step 1 — the truth the server owns.</b>
 *
 * <p>Some machine somewhere in a world: it can be switched on, it runs at a throughput the player
 * chooses, it works through a cycle, and it has a name. Whether anybody is <em>looking</em> at it is
 * none of its business.</p>
 *
 * <p>It also has an {@link EngineModel}, which it <b>owns</b> — made here, ticked here. That object
 * exists as a separate thing for a reason worth reading before writing a
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
 * <p>And it holds no listeners either. A panel that shows this machine <b>projects</b> it — the engine
 * reads the model on its tick and writes what changed — so the model never learns a window exists,
 * and a machine that outlives its viewers has nothing to accumulate. Its whole contract with the UI
 * is that its getters answer. (It used to carry a listener list for exactly that purpose, and once
 * projections existed nothing subscribed to it: a model announcing into an empty list, for the
 * benefit of code that had stopped needing to be told.)</p>
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
     * <p>The constructor is package-private: an engine is made by the machine it belongs to.</p>
     */
    private final EngineModel engine = new EngineModel();

    public MachineModel() {
        for (int i = 0; i < 200; i++) {
            slots.add(new Slot(i, ITEMS[i % ITEMS.length], 1 + (i * 7) % 64));
        }
        append("machine assembled");
    }

    /** Enough names that a scrolled window plainly shows different rows. */
    private static final String[] ITEMS = {
            "Iron Ingot", "Copper Wire", "Redstone", "Quartz", "Glass Pane", "Coal", "Gear", "Piston"
    };

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
    }

    public void setThroughput(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == throughput) return;
        throughput = clamped;
    }

    public void setLabel(String value) {
        String cleaned = value == null ? "" : value;
        if (cleaned.equals(label)) return;
        label = cleaned;
    }

    /** Abandons the current cycle. What the panel's Purge button is wired to. */
    public void purge() {
        if (progress == 0f) return;
        progress = 0f;
    }

    // ── Two collections, because a collection is what a stream is for ────────────────────────────

    /**
     * What is in the machine, as a list long enough that describing all of it would be silly.
     *
     * <p>Two hundred slots is not a lot and is already more than a panel shows. The point of the
     * number is that the cost of the UI does not depend on it — a viewer sees a window of rows, and
     * the same code serves two hundred or ten thousand.</p>
     */
    private final List<Slot> slots = new ArrayList<>();

    /** What the machine has done, newest last — what a viewer FOLLOWS. */
    private final List<String> log = new ArrayList<>();

    /** One inventory slot. Its {@code index} is its identity and does not change when it empties. */
    public record Slot(int index, String item, int count) {
    }

    public List<Slot> slots() {
        return Collections.unmodifiableList(slots);
    }

    public int slotCount() {
        return slots.size();
    }

    /** A window of the inventory. What a {@code RowSource} answers with. */
    public List<Slot> slots(int from, int to) {
        int start = Math.max(0, Math.min(from, slots.size()));
        int end = Math.max(start, Math.min(to, slots.size()));
        return List.copyOf(slots.subList(start, end));
    }

    /** Empties a slot, keeping its place. Refused for an index nobody has. */
    public boolean take(int index) {
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.index() != index) continue;
            if (slot.count() == 0) return false;
            slots.set(i, new Slot(index, slot.item(), 0));
            append("took " + slot.count() + " x " + slot.item() + " from slot " + index);
            return true;
        }
        return false;
    }

    public List<String> log() {
        return Collections.unmodifiableList(log);
    }

    public int logSize() {
        return log.size();
    }

    /** A window of the log. */
    public List<String> log(int from, int to) {
        int start = Math.max(0, Math.min(from, log.size()));
        int end = Math.max(start, Math.min(to, log.size()));
        return List.copyOf(log.subList(start, end));
    }

    /** Writes a line. The log is what a following viewer receives without asking. */
    public void append(String line) {
        log.add(line);
    }

    /**
     * One world tick.
     *
     * <p>Nothing happens while the machine is stopped, except that its engine is ticked <b>either
     * way</b> — a stopped machine's engine is cooling, which is something happening.</p>
     */
    public void tick() {
        engine.tick(running);
        if (!running) return;
        if (engine.isStalled()) {
            // The engine tripped, so the machine stops. setRunning does nothing at all if something
            // else already stopped us this tick.
            setRunning(false);
            return;
        }
        progress += CYCLE_SPEED * (0.25f + throughput) * engine.output();
        if (progress >= 1f) {
            progress = 0f;
            completedCycles++;
        }
    }
}
