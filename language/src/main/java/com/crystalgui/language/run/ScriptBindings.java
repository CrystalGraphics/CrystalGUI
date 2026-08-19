package com.crystalgui.language.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * What a host puts in scope for every script — and the one place a mod adds to it.
 *
 * <h3>The extension point, and why it is a registry rather than a parameter</h3>
 *
 * <p>A scripting host is only worth having if scripts can reach the application, and which objects those
 * are is not something this module can know: one mod offers a world, another a machine, another its own
 * event bus. Passing them in would mean the <em>application</em> assembles the set — so two mods could
 * not both contribute without the application knowing about both, which is exactly the coupling a mod
 * loader exists to avoid.</p>
 *
 * <p>So contributors register themselves, the same way {@code JavaLanguage.register()} adds a language
 * service: <b>adds, never replaces</b>, so registration order does not matter and nobody has to be last.</p>
 *
 * <h3>A binding carries its type AND its value, together</h3>
 *
 * <p>Not two parallel maps, which is the shape this would naturally take and the one that breaks
 * quietly. A binding is declared to the compiler as {@code name : Type} and supplied to the run as
 * {@code name = value}, and if those come from different places they can disagree — a mod updating the
 * type and not the supplier compiles a field the value cannot be assigned to. The failure is a
 * reflection error at run time naming a field the author never wrote.</p>
 *
 * <p>The value is a {@link Supplier} because it is read per run: "the current world" is a different
 * object on Tuesday, and a captured one would be a leak as well as a lie.</p>
 */
public final class ScriptBindings {

    private ScriptBindings() {
    }

    /**
     * One name a script can use.
     *
     * @param name     the identifier, as the script will write it
     * @param typeName the declared type, resolvable on the script's classpath — fully qualified, since
     *                 the prelude emits it as a field declaration with no imports of its own
     * @param value    read once per run, never captured
     */
    public record Binding(String name, String typeName, Supplier<Object> value) {
    }

    /** A source of bindings, asked afresh each time so a contributor can offer them conditionally. */
    @FunctionalInterface
    public interface Contributor {
        List<Binding> bindings();
    }

    /**
     * Copy-on-write because it is read on every compile and every run, and written approximately never —
     * once per mod at startup. A lock on the read path would be paid twenty times a second by a host
     * that recompiles while typing.
     */
    private static final List<Contributor> CONTRIBUTORS = new CopyOnWriteArrayList<>();

    /** Adds a contributor. Never replaces one, so two mods can both offer bindings. */
    public static void register(Contributor contributor) {
        if (contributor != null) CONTRIBUTORS.add(contributor);
    }

    /** Adds a fixed set — the common case, for a host whose objects do not come and go. */
    public static void register(Binding... bindings) {
        List<Binding> fixed = List.of(bindings);
        register(() -> fixed);
    }

    public static void unregister(Contributor contributor) {
        CONTRIBUTORS.remove(contributor);
    }

    /** For a test, and for a host tearing down. */
    public static void clear() {
        CONTRIBUTORS.clear();
    }

    /**
     * Every binding on offer right now.
     *
     * <p><b>Later wins on a name collision</b>, and the collision is not hypothetical: two mods will
     * eventually both call something {@code world}. Silently keeping the first would give the second a
     * binding that exists, has the right name, and is somebody else's object — so the last registration
     * wins, which at least makes the behaviour orderable and describable.</p>
     */
    public static List<Binding> all() {
        Map<String, Binding> byName = new LinkedHashMap<>();
        for (Contributor contributor : CONTRIBUTORS) {
            List<Binding> offered = contributor.bindings();
            if (offered == null) continue;
            for (Binding binding : offered) {
                if (binding != null && binding.name() != null) byName.put(binding.name(), binding);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** What the prelude declares — {@code name -> type}. */
    public static Map<String, String> types() {
        Map<String, String> types = new LinkedHashMap<>();
        for (Binding binding : all()) types.put(binding.name(), binding.typeName());
        return types;
    }

    /**
     * What a run is given — {@code name -> value}.
     *
     * <p>A supplier that throws takes its own binding out rather than the whole run: a mod whose object
     * is unavailable right now should cost a script that name, not every script the ability to start.</p>
     */
    public static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Binding binding : all()) {
            try {
                values.put(binding.name(), binding.value() == null ? null : binding.value().get());
            } catch (RuntimeException unavailable) {
                // Deliberately swallowed. @see the note above.
            }
        }
        return values;
    }
}
