package com.crystalgui.language.engine.bridge;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the last run left behind — the global scope, flattened into types the host can hold.
 *
 * <h3>Why a snapshot and not the scope itself</h3>
 *
 * <p>A Rhino {@code Scriptable} belongs to the {@code Context} that made it, and a {@code Context} is
 * single-threaded: reading one from the UI thread while the script thread is still using it is undefined,
 * and reading it <em>after</em> the run is over means holding the whole object graph — every value the
 * script built and every Java object it touched — alive for as long as the editor has the file open. The
 * disposal test would fail by construction.</p>
 *
 * <p>So the scope is walked <b>once, on the script thread, at the moment the run ends</b>, and what
 * crosses is this: names, what kind of thing each one is, and the few facts a completion list or a hover
 * actually shows. Nothing here references a Rhino type, so the run's scope becomes collectable the moment
 * the run returns.</p>
 *
 * <h3>One level deep, deliberately</h3>
 *
 * <p>An object's own property names are recorded; their values are not. A REPL user asking what
 * {@code settings.} offers is asking about one object, and the level below that is another snapshot's
 * worth of work for a question nobody asked — while a cyclic graph would make a full walk non-terminating.
 * Node's own inspector draws the same line at its default depth.</p>
 *
 * <h3>This is the "from last run" tier</h3>
 *
 * <p>It outranks JSDoc and inference because it is the only tier that reports what a value <em>is</em>
 * rather than what somebody said or what the syntax suggests. It is also the tier that stops a name a
 * previous run defined being coloured as unresolved.</p>
 */
public final class LiveScopeSnapshot {

    /** Nothing has run, or the run defined nothing. */
    public static final LiveScopeSnapshot EMPTY =
            new LiveScopeSnapshot(Collections.emptyMap(), Collections.emptyList());

    /** What a live value turned out to be — enough to type it, colour it and list its members. */
    public enum Kind {
        FUNCTION,
        /** A Java instance: {@code javaClassName} is its class, and the Java resolver answers for it. */
        JAVA_OBJECT,
        /** A Java class itself — what {@code Java.type("a.b.C")} answers. Members are its statics. */
        JAVA_CLASS,
        ARRAY,
        /** A plain JavaScript object: {@code ownIds} are its properties. */
        OBJECT,
        STRING,
        NUMBER,
        BOOLEAN,
        REGEXP,
        UNDEFINED,
        NULL,
        /** Something with a class of its own — a Date, an Error, a Map. */
        OTHER
    }

    /**
     * One global.
     *
     * @param javaClassName  the binary name, for {@link Kind#JAVA_OBJECT} and {@link Kind#JAVA_CLASS}
     * @param functionName   what a function calls itself, which need not be the name it is bound to
     * @param arity          a function's declared parameter count, or {@code -1}
     * @param ownIds         an object's or a function's own property names, never its prototype's
     */
    public record Entry(String name, Kind kind, @Nullable String javaClassName,
                        @Nullable String functionName, int arity, List<String> ownIds) {

        public Entry {
            if (name == null) name = "";
            if (kind == null) kind = Kind.OTHER;
            ownIds = ownIds == null || ownIds.isEmpty() ? List.of() : List.copyOf(ownIds);
        }

        public static Entry of(String name, Kind kind) {
            return new Entry(name, kind, null, null, -1, List.of());
        }

        /** Whether this names a Java type the Java resolver can be asked about. */
        public boolean isJava() {
            return javaClassName != null && !javaClassName.isEmpty();
        }
    }

    private final Map<String, Entry> byName;
    private final List<String> objectPrototypeIds;

    private LiveScopeSnapshot(Map<String, Entry> byName, List<String> objectPrototypeIds) {
        this.byName = byName;
        this.objectPrototypeIds = objectPrototypeIds;
    }

    /** Insertion order is the walk's order, which is Rhino's own id order — stable, not sorted. */
    public static LiveScopeSnapshot of(List<Entry> entries) {
        return of(entries, List.of());
    }

    /**
     * @param objectPrototypeIds what every object inherits, read from the run's own
     *                           {@code Object.prototype} rather than written down here — {@code toString},
     *                           {@code valueOf}, {@code hasOwnProperty} and the rest. Held once for the
     *                           whole snapshot instead of copied onto each entry, because it is the same
     *                           list for every object in the scope
     */
    public static LiveScopeSnapshot of(List<Entry> entries, List<String> objectPrototypeIds) {
        List<String> shared = objectPrototypeIds == null || objectPrototypeIds.isEmpty()
                ? List.of() : List.copyOf(objectPrototypeIds);
        if (entries == null || entries.isEmpty()) {
            return shared.isEmpty() ? EMPTY : new LiveScopeSnapshot(Collections.emptyMap(), shared);
        }
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry != null && !entry.name().isEmpty()) byName.put(entry.name(), entry);
        }
        return byName.isEmpty() && shared.isEmpty() ? EMPTY : new LiveScopeSnapshot(byName, shared);
    }

    /** What every object inherits — a completion list marks these as inherited from the root. */
    public List<String> objectPrototypeIds() {
        return objectPrototypeIds;
    }

    @Nullable
    public Entry get(@Nullable String name) {
        return name == null ? null : byName.get(name);
    }

    public boolean has(@Nullable String name) {
        return name != null && byName.containsKey(name);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(byName.keySet());
    }

    public List<Entry> entries() {
        return List.copyOf(byName.values());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    @Override
    public String toString() {
        return "LiveScopeSnapshot" + byName.keySet();
    }
}
