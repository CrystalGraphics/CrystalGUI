package com.crystalgui.core.async;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.dispose.Disposable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * What modules and applications declare, as a published list of {@code (owner, item)} that every sequence reads and
 * any thread may add to.
 *
 * <pre>{@code
 * static final Contributions<NodeKind> KINDS = Contributions.of("node-kinds", NodeKind::id);
 * static final Contributions.Index<Map<String, NodeKind>> BY_ID = KINDS.index(entries -> byId(entries));
 *
 * KINDS.add(Owner.PROCESS, kind);                       // a module, at startup
 * Disposable done = KINDS.add(app.owner(), kind);       // an application: it goes when the owner does
 * KINDS.removeOwner(app.owner());                       // everything one owner added, in one generation
 *
 * NodeKind kind = BY_ID.get().get("math.add");          // derived once per generation, shared by every reader
 * }</pre>
 *
 * <p>Every change is one new generation of the underlying {@link Published}, so a reader pinned to a frame sees the
 * list whole, before or after a registration and never between. Items are kept in the order they were added.</p>
 *
 * <p>An id function names what a contribution is; two contributions of one id are a conflict, and what happens is
 * the {@link Conflict} chosen at {@link #of(String, Function, Conflict)}: the later wins (logged with both owners),
 * the first stays, or the later is refused (an equal item again is a no-op for its owner, and a second holder for another). A list built without an id function takes everything.</p>
 */
public final class Contributions<T> {

    /** What happens when two contributions carry one id. */
    public enum Conflict {
        /** The later replaces the earlier, and both owners are logged. For hot reload and overrides. */
        LATER_WINS,
        /** The earlier stays, and the later is ignored with a warning naming both owners. For a packaging mistake. */
        FIRST_WINS,
        /**
         * One owner may not contribute an id twice (refused), but another owner contributing it is a second holder:
         * both stay until both owners have gone, and a reader takes the first. For what every application's
         * extension contributes again, each from its own activation.
         */
        SHARED,
        /** The later is refused with an {@link IllegalArgumentException} naming both owners. */
        REJECT
    }

    /** One contribution and who made it. */
    public record Entry<T>(Owner owner, T item) {
    }

    private final String name;
    @Nullable
    private final Function<? super T, ?> id;
    private final Conflict conflict;
    private final Published<List<Entry<T>>> entries;
    private final Index<List<T>> all;

    private Contributions(String name, @Nullable Function<? super T, ?> id, Conflict conflict) {
        this.name = name;
        this.id = id;
        this.conflict = conflict;
        this.entries = Published.of(name, Collections.emptyList());
        this.all = index(list -> {
            List<T> items = new ArrayList<>(list.size());
            for (Entry<T> entry : list) items.add(entry.item());
            return Collections.unmodifiableList(items);
        });
    }

    /** A list that takes everything: no id, so no conflicts. */
    public static <T> Contributions<T> of(String name) {
        return new Contributions<>(Objects.requireNonNull(name, "name"), null, Conflict.LATER_WINS);
    }

    /** A list whose contributions are named by {@code id}; the later of two with one id wins. */
    public static <T> Contributions<T> of(String name, Function<? super T, ?> id) {
        return of(name, id, Conflict.LATER_WINS);
    }

    public static <T> Contributions<T> of(String name, Function<? super T, ?> id, Conflict conflict) {
        return new Contributions<>(Objects.requireNonNull(name, "name"), Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(conflict, "conflict"));
    }

    public String name() {
        return name;
    }

    /** Adds {@code item} for {@code owner}. Disposing the answer removes just this contribution. */
    public Disposable add(Owner owner, T item) {
        return add(owner, item, true);
    }

    /** As {@link #add}, but a contribution of the same id is replaced without a log line: a refinement, not a clash. */
    public Disposable replace(Owner owner, T item) {
        return add(owner, item, false);
    }

    private Disposable add(Owner owner, T item, boolean report) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(item, "item");
        Entry<T> mine = new Entry<>(owner, item);
        entries.update(list -> {
            List<Entry<T>> next = new ArrayList<>(list.size() + 1);
            for (Entry<T> existing : list) {
                if (id != null && Objects.equals(id.apply(existing.item()), id.apply(item))) {
                    if (conflict == Conflict.FIRST_WINS) {
                        CrystalGuiCore.LOGGER.warn("[cgui] '{}' is already contributed to {} by {}; {}'s is ignored",
                                id.apply(item), name, existing.owner(), owner);
                        return list;
                    }
                    if (conflict == Conflict.SHARED) {
                        if (existing.owner() != owner) {
                            next.add(existing);
                            continue;
                        }
                        throw new IllegalArgumentException("'" + id.apply(item) + "' is already contributed to " + name
                                + " by " + owner + "; one owner cannot add it twice (ids should be namespaced, e.g. 'mymod:"
                                + id.apply(item) + "')");
                    }
                    if (conflict == Conflict.REJECT) {
                        // The same contribution again, from a host with more than one entry point, is not a conflict.
                        if (existing.item().equals(item)) {
                            // The same contribution by one owner is nothing; by another it is a second holder, so
                            // it stays until both owners have gone.
                            if (existing.owner() == owner) return list;
                            next.add(existing);
                            continue;
                        }
                        throw new IllegalArgumentException("'" + id.apply(item) + "' is already contributed to " + name
                                + " by " + existing.owner() + "; " + owner + " cannot add it again");
                    }
                    if (report) {
                        CrystalGuiCore.LOGGER.info("[cgui] '{}' in {} from {} was replaced by {}", id.apply(item), name,
                                existing.owner(), owner);
                    }
                    continue;
                }
                next.add(existing);
            }
            next.add(mine);
            return Collections.unmodifiableList(next);
        });
        return () -> entries.update(list -> {
            List<Entry<T>> next = new ArrayList<>(list);
            return next.remove(mine) ? Collections.unmodifiableList(next) : list;
        });
    }

    /** Removes the first contribution of {@code item}, by equality, whoever owns it. @return whether there was one */
    public boolean remove(T item) {
        boolean[] found = {false};
        entries.update(list -> {
            List<Entry<T>> next = new ArrayList<>(list.size());
            for (Entry<T> entry : list) {
                if (!found[0] && entry.item().equals(item)) {
                    found[0] = true;
                } else {
                    next.add(entry);
                }
            }
            return found[0] ? Collections.unmodifiableList(next) : list;
        });
        return found[0];
    }

    /** Removes everything {@code owner} added, as one new generation. */
    public void removeOwner(Owner owner) {
        entries.update(list -> {
            List<Entry<T>> next = new ArrayList<>(list.size());
            for (Entry<T> entry : list) {
                if (entry.owner() != owner) next.add(entry);
            }
            return next.size() == list.size() ? list : Collections.unmodifiableList(next);
        });
    }

    /** Removes everything. For a test, which shares statics with every other in the run. */
    public void clearForTesting() {
        entries.update(list -> list.isEmpty() ? list : Collections.<Entry<T>>emptyList());
    }

    /** Every contribution with its owner, in the order added: immutable, at the generation this thread reads. */
    public List<Entry<T>> entries() {
        return entries.current();
    }

    /** Every item, in the order added: one list per generation, shared by every reader. */
    public List<T> all() {
        return all.get();
    }

    /** The generation this list is at, as this thread reads it. */
    public long generation() {
        return entries.generation().number();
    }

    /** The published list, for its {@link Published#changes()}. */
    public Published<List<Entry<T>>> published() {
        return entries;
    }

    /**
     * A lookup built from the list once per generation (by id, by menu, by chord, by file extension).
     *
     * <p>The builder runs on whichever thread reads first after a change, so it must be a pure function of the
     * list.</p>
     */
    public <I> Index<I> index(Function<? super List<Entry<T>>, ? extends I> build) {
        return new Index<>(entries, build);
    }

    @Override
    public String toString() {
        return "Contributions(" + name + ", " + entries.latest().value().size() + ")";
    }

    /** A value derived from one generation of the list, rebuilt only when the list changes. */
    public static final class Index<I> {
        private record Built<I>(long number, I value) {
        }

        private final Published<? extends List<?>> source;
        private final Function<Object, ? extends I> build;
        private volatile Built<I> built;

        @SuppressWarnings("unchecked")
        private <E> Index(Published<List<E>> source, Function<? super List<E>, ? extends I> build) {
            this.source = source;
            this.build = list -> build.apply((List<E>) list);
        }

        /** The index of the generation this thread reads. */
        public I get() {
            Published.Generation<? extends List<?>> generation = source.generation();
            Built<I> have = built;
            if (have != null && have.number() == generation.number()) return have.value();
            I value = build.apply(generation.value());
            if (have == null || have.number() < generation.number()) {
                built = new Built<>(generation.number(), value);
            }
            return value;
        }
    }
}
