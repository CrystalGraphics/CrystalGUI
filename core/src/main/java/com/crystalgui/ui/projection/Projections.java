package com.crystalgui.ui.projection;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.ui.dom.UINode;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * <b>A model, stated once, kept on the widgets.</b>
 *
 * <p>A projection is a declared pair — read this from the model, write it to that widget — evaluated
 * by the engine rather than by the author. It replaces the shape every panel used to hand-write:</p>
 *
 * <pre>{@code
 * // before: a method called every tick, and a field you can forget to add to it
 * private void mirror(MachineModel model) {
 *     power.setChecked(model.isRunning());
 *     throughput.setValue(model.throughput());
 *     ...
 * }
 *
 * // after: stated once
 * projections.of(model::isRunning,  power::setChecked)
 *            .of(model::throughput, throughput::setValue);
 * }</pre>
 *
 * <h3>The model is not touched</h3>
 *
 * <p>Both halves are method references over accessors that already exist — no {@code Property} fields,
 * no annotations, no interface to implement, no rewrite. That is deliberate and is the whole reason
 * this is the default tier rather than observable model fields: the case that matters is a large
 * legacy model that cannot be reshaped to suit a UI engine.</p>
 *
 * <h3>What this is NOT</h3>
 *
 * <p>It does not deduce which widget shows which field, and nothing can. React writes
 * {@code <Slider value={m.throughput}/>}, LiveView writes {@code <%= @throughput %>}, Blazor writes
 * {@code @bind-Value}, Unreal writes a {@code RepNotify} handler — every production system states the
 * mapping once per displayed field, because that statement <em>is</em> the UI design. What is
 * automated here is the other question: <b>which fields changed</b>.</p>
 *
 * <h3>Cost</h3>
 *
 * <p>One {@code equals} per projection per run, and the count is the number of DISPLAYED fields, not
 * the size of the model — a five-hundred-field model behind a twelve-control panel has twelve
 * projections. A run in which nothing moved writes nothing, marks nothing dirty and therefore sends
 * nothing, because every widget setter is idempotent. {@link #gatedBy} reduces even that to a single
 * comparison for a model that can report a revision.</p>
 *
 * <p><b>Not thread-safe, by design.</b> Projections write widgets, and the frame thread owns the tree.</p>
 */
public final class Projections {

    /** In declaration order, which is the order they run in and the order a report lists them. */
    private final List<Unit> units = new ArrayList<>();

    /**
     * What already has a projection aimed at it, <b>by identity</b>.
     *
     * <p>So {@link AutoProjection} can leave alone anything stated by hand, in any order and without
     * being told a name. The first version matched by field NAME and required every explicit projection
     * to be declared before the automatic pass — two invisible rules whose only symptom when broken is
     * a widget written twice per tick by two projections that may disagree, last one winning. Identity
     * has neither rule.</p>
     */
    private final Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());

    @Nullable
    private Supplier<?> epoch;

    private boolean epochRead;
    @Nullable
    private Object lastEpoch;

    public static Projections create() {
        return new Projections();
    }

    // ── Declaring ────────────────────────────────────────────────────────────

    /**
     * Reads {@code from} each run and hands it to {@code to} <b>only when it differs</b> from what was
     * handed over last time.
     *
     * <p>Nesting needs no feature of its own — a lambda covers it, and a {@code null} anywhere in the
     * chain is treated as "nothing to show yet" rather than as an error:</p>
     *
     * <pre>{@code
     * of(() -> model.engine().coolant().level(), coolant::setFraction);
     * }</pre>
     */
    public <V> Projections of(Supplier<V> from, Consumer<V> to) {
        units.add(new Field<>(null, Objects.requireNonNull(from, "from"),
                Objects.requireNonNull(to, "to")));
        return this;
    }

    /**
     * As {@link #of(Supplier, Consumer)}, recording what it aims at so nothing else aims there too.
     *
     * @param target the widget being written. Compared by identity, never by name
     */
    public <V> Projections onto(Object target, Supplier<V> from, Consumer<V> to) {
        Objects.requireNonNull(target, "target");
        targets.add(target);
        units.add(new Field<>(describe(target), Objects.requireNonNull(from, "from"),
                Objects.requireNonNull(to, "to")));
        return this;
    }

    /** Whether something already writes {@code target}. @see #onto */
    public boolean targets(Object target) {
        return targets.contains(target);
    }

    private static String describe(Object target) {
        return target instanceof UINode ? ((UINode) target).tagName()
                : target.getClass().getSimpleName();
    }

    /**
     * Keeps {@code into}'s children matching a list from the model, <b>keyed</b>, so an insert is an
     * insert rather than a rebuild.
     *
     * <p>The collection case, and the one a hand-written mirror cannot express at all: it can re-set
     * what it already has and cannot say "these forty items became forty-one". Unreal's
     * {@code FFastArraySerializer} and LiveView's streams are the same idea — identity per item, so
     * add, remove and reorder are each what they are.</p>
     *
     * <p><b>An untouched row keeps its element</b>, which is what makes it cheap over a wire: the
     * mirror sees one {@code insert} rather than a cleared child list, so every other row's instance —
     * and anything the viewer had done to it — survives.</p>
     *
     * @param items  the model's list; {@code null} is treated as empty
     * @param into   the container whose DESCRIBED children are managed. Nothing else may add children
     *               to it, or reconciliation will fight whatever does
     * @param key    an item's stable identity. Not its index, and not the item itself unless it is
     *               genuinely immutable and has value equality
     * @param create builds the row element for an item seen for the first time
     * @param apply  writes an item into its row, every run. Cheap because widget setters are idempotent
     */
    public <T> Projections each(Supplier<? extends List<T>> items, UINode into,
                                Function<T, Object> key, Function<T, UINode> create,
                                BiConsumer<UINode, T> apply) {
        Objects.requireNonNull(into, "into");
        targets.add(into);
        units.add(new Keyed<>(Objects.requireNonNull(items, "items"), into,
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(create, "create"),
                Objects.requireNonNull(apply, "apply")));
        return this;
    }

    /**
     * Skips every projection in this set while {@code epoch} answers what it answered last run.
     *
     * <p>For a model large enough that one comparison per displayed field is worth avoiding — a
     * revision counter, a version stamp, or the model reference itself when it is immutable. Unreal's
     * {@code NetUpdateFrequency} plus dirty tracking in spirit.</p>
     *
     * <p><b>Only sound if the epoch changes for every change that matters.</b> An epoch that misses a
     * mutation makes the projection miss it too, silently and permanently — which is the failure this
     * whole mechanism exists to remove, so it is opt-in and never a default.</p>
     */
    public Projections gatedBy(Supplier<?> epoch) {
        this.epoch = epoch;
        this.epochRead = false;
        this.lastEpoch = null;
        return this;
    }

    // ── Running ──────────────────────────────────────────────────────────────

    /**
     * Evaluates every projection.
     *
     * @return how many wrote something — zero on a run where the model did not move, which is the
     *         common case and the one that must cost nothing downstream
     */
    public int run() {
        if (epoch != null) {
            Object now;
            try {
                now = epoch.get();
            } catch (RuntimeException failed) {
                now = null;
            }
            if (epochRead && Objects.equals(lastEpoch, now)) return 0;
            epochRead = true;
            lastEpoch = now;
        }
        int changed = 0;
        for (Unit unit : units) {
            if (unit.run()) changed++;
        }
        return changed;
    }

    public int size() {
        return units.size();
    }

    /**
     * Forgets every projection and everything they hold.
     *
     * <p>Called when the thing being projected onto goes away. A projection holds the model, the
     * widget and the last value it wrote; a keyed one additionally holds every realised row. None of
     * that is a leak while the whole window is discarded together — but {@link #close} is what makes
     * that true rather than assumed, and it is the hook a listener-based binding must be undone from.</p>
     */
    public void close() {
        for (Unit unit : units) unit.release();
        units.clear();
        targets.clear();
        epoch = null;
        epochRead = false;
        lastEpoch = null;
    }

    public boolean isEmpty() {
        return units.isEmpty();
    }

    // ── The two kinds ────────────────────────────────────────────────────────

    private abstract static class Unit {
        /** Set after the first failure, so a broken projection reports once rather than every tick. */
        private boolean reported;

        /** @return true if this wrote something */
        final boolean run() {
            try {
                return evaluate();
            } catch (RuntimeException failed) {
                /*
                 * A PROJECTION MUST NOT THROW THE FRAME.
                 *
                 * The tick loop already isolates one window from another, and this isolates one
                 * projection from the rest of its own panel -- otherwise a single null-happy getter
                 * stops every OTHER field on the panel from updating, which presents as the panel
                 * being dead rather than as one accessor being wrong.
                 *
                 * Reported once. Per tick would be sixty log lines a second for one bad getter, which
                 * buries the report it is trying to make.
                 */
                if (!reported) {
                    reported = true;
                    CrystalGuiCore.LOGGER.warn("Projection {} failed and will be retried silently: {}",
                            describe(), failed.toString());
                }
                return false;
            }
        }

        abstract boolean evaluate();

        abstract String describe();

        /** Drops whatever this holds. Overridden where there is something to drop. */
        void release() {
        }
    }

    /** One field: read, compare, write on change. */
    private static final class Field<V> extends Unit {
        @Nullable private final String name;
        private final Supplier<V> from;
        private final Consumer<V> to;

        private boolean applied;
        @Nullable private V last;

        Field(@Nullable String name, Supplier<V> from, Consumer<V> to) {
            this.name = name;
            this.from = from;
            this.to = to;
        }

        @Override
        boolean evaluate() {
            V value = from.get();
            // The `applied` flag rather than a bare equality test, for the same reason ProgressBar
            // needs one: a field whose first value equals its widget's default would otherwise never
            // be written at all, and the widget would sit at a default that merely looks right.
            if (applied && Objects.equals(last, value)) return false;
            applied = true;
            last = value;
            to.accept(value);
            return true;
        }

        @Override
        String describe() {
            return name == null ? "<unnamed>" : name;
        }
    }

    /** A keyed list reconciled against a container's described children. */
    private static final class Keyed<T> extends Unit {
        private final Supplier<? extends List<T>> items;
        private final UINode into;
        private final Function<T, Object> key;
        private final Function<T, UINode> create;
        private final BiConsumer<UINode, T> apply;

        /** Realised rows by key, in the order they are currently laid out. */
        private final Map<Object, UINode> realised = new LinkedHashMap<>();

        /** The item each row was last written from, so an unchanged row is not rewritten. */
        private final Map<Object, T> lastItem = new LinkedHashMap<>();

        Keyed(Supplier<? extends List<T>> items, UINode into, Function<T, Object> key,
              Function<T, UINode> create, BiConsumer<UINode, T> apply) {
            this.items = items;
            this.into = into;
            this.key = key;
            this.create = create;
            this.apply = apply;
        }

        @Override
        boolean evaluate() {
            List<T> current = items.get();
            if (current == null) current = List.of();

            boolean changed = false;
            List<Object> order = new ArrayList<>(current.size());
            Set<Object> wanted = new HashSet<>(Math.max(4, current.size() * 2));

            // 1. Create what is new; write a row only when ITS OWN item changed.
            for (T item : current) {
                Object id = key.apply(item);
                if (id == null) {
                    throw new IllegalStateException("a projected item answered a null key — a key must "
                            + "be a stable identity, and null cannot identify anything");
                }
                if (!wanted.add(id)) {
                    /*
                     * DUPLICATE KEYS ARE REFUSED, LOUDLY.
                     *
                     * Left alone they are silently destructive rather than merely wrong: both items map
                     * to one element, so the list comes out SHORTER than the model, and the ordering
                     * pass below moves that single element to two places -- the second move undoing the
                     * first. The visible result is a row that vanishes, which reads as a rendering bug
                     * anywhere except here.
                     */
                    throw new IllegalStateException("two projected items share the key " + id
                            + " — a key must be unique within the list");
                }
                order.add(id);

                UINode row = realised.get(id);
                if (row == null) {
                    row = create.apply(item);
                    if (row == null) {
                        throw new IllegalStateException("the row factory answered null for key " + id);
                    }
                    realised.put(id, row);
                    lastItem.put(id, item);
                    apply.accept(row, item);
                    changed = true;
                    continue;
                }
                // A record or any value-equal item costs one equals here and no widget writes at all.
                // A MUTABLE row object compares by identity, so it is re-applied every run -- which is
                // correct rather than wasteful, since its fields may have changed underneath the same
                // reference, and the widget setters it lands on are idempotent.
                if (!Objects.equals(lastItem.get(id), item) || lastItem.get(id) == item) {
                    lastItem.put(id, item);
                    apply.accept(row, item);
                }
            }

            // 2. Remove what is gone. A SET lookup, not a list scan: the first version asked
            //    `wanted.contains(...)` inside this loop, which is O(n^2) and reaches a quarter of a
            //    million comparisons per tick on a five-hundred-row list.
            for (Iterator<Map.Entry<Object, UINode>> it = realised.entrySet().iterator();
                    it.hasNext(); ) {
                Map.Entry<Object, UINode> entry = it.next();
                if (wanted.contains(entry.getKey())) continue;
                into.remove(entry.getValue());
                lastItem.remove(entry.getKey());
                it.remove();
                changed = true;
            }

            // 3. Put them in order, moving only what is out of place.
            for (int i = 0; i < order.size(); i++) {
                UINode row = realised.get(order.get(i));
                List<UINode> laid = into.children();
                if (i < laid.size() && laid.get(i) == row) continue;
                // A row already here is MOVED and a new one is INSERTED, and the two are different
                // calls on purpose: only the move path reports `moved` -- which is what stops a reorder
                // arriving on the far side as a destroy-and-rebuild of the row.
                if (row.parent() == into) {
                    row.moveTo(into, i);
                } else {
                    into.insertAt(i, row);
                }
                changed = true;
            }
            return changed;
        }

        @Override
        void release() {
            realised.clear();
            lastItem.clear();
        }

        @Override
        String describe() {
            return "each(" + into.tagName() + ")";
        }
    }
}
