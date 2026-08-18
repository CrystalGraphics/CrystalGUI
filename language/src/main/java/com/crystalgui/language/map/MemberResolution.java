package com.crystalgui.language.map;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turning a readable member name into the runtime one, <b>asking the owner</b>.
 *
 * <h3>Why the mapping alone is not enough, in one direction only</h3>
 *
 * <p>MCP's data is unqualified: SRG names are globally unique, so the files carry no owner column. That
 * makes runtime → readable a function, and readable → runtime <b>not</b> one. Measured on
 * {@code mcp_stable/12}: 357 method names and 329 field names are claimed by more than one runtime name,
 * covering about a fifth of the rows. {@link MappingSet} therefore refuses to guess between them, which
 * is right and leaves the rename unresolved — and an unresolved rename fails at the call.</p>
 *
 * <p>The owner settles it, and the owner is always to hand: enumerate what the runtime type declares,
 * map each name <em>forward</em> (the direction that is a function), and keep the one whose readable name
 * is the one that was written. No {@code packaged.srg}, no second mapping format.</p>
 *
 * <h3>And it stops an unqualified rename escaping its own namespace</h3>
 *
 * <p>This is the half that bites hardest, because the names are ordinary. In {@code mcp_stable/12},
 * {@code add} is {@code func_76163_a}, {@code run} is {@code func_99999_d}, and {@code close},
 * {@code remove}, {@code get}, {@code read} and {@code write} are all mapped too. Applied without an
 * owner they rename members of classes the mapping has nothing to do with:</p>
 *
 * <ul>
 *   <li>the script's own {@code run()} became a {@code func_*}, so {@code ScriptHost} reported that a
 *       class it had just compiled had no entry point;</li>
 *   <li>{@code list.add(...)} — in a Java script and equally in a JavaScript one through the Rhino
 *       membrane — would be rewritten to a Minecraft method and fail at the call.</li>
 * </ul>
 *
 * <h3>One implementation, two callers</h3>
 *
 * <p>{@code InheritanceAwareRemapper} rewrites compiled bytecode and {@code JsLanguage}'s
 * {@code MemberNameMapper} renames members on a live Rhino wrapper. Different mechanisms, one question —
 * and the Java side was fixed first, which is exactly how the JavaScript side would have been left
 * quietly broken on the one environment nobody develops in.</p>
 */
public final class MemberResolution {

    /**
     * The member names a runtime type declares.
     *
     * <p>Supertypes included: a script calling {@code block.getUnlocalizedName()} names the type it has
     * rather than the one that declares the method, so a scan confined to the exact owner would miss
     * every inherited call.</p>
     *
     * <p>A type that will not load answers <b>empty</b>, and that is the correct answer rather than a
     * degradation. It is what the classes being compiled report — they are not loadable yet, and their
     * members are authored names rather than readable-namespace aliases — and everywhere else it is the
     * conservative direction: a rename not applied leaves a readable name, which fails loudly at the
     * call, while a rename applied wrongly produces a {@code NoSuchMethodError} naming something the
     * author never wrote.</p>
     */
    public interface Members {

        /** Every member name {@code internalName} declares, supertypes included; empty if unloadable. */
        Set<String> namesOf(String internalName);

        /** Knows nothing, so no unqualified rename is ever applied. What a test with no host uses. */
        Members NONE = internalName -> java.util.Collections.emptySet();
    }

    private MemberResolution() {
    }

    /** @see #runtimeName */
    public static String runtimeMethod(MappingSet mappings, Members members, String owner,
                                       String readableName) {
        return runtimeName(mappings, members, owner, readableName, true);
    }

    /** @see #runtimeName */
    public static String runtimeField(MappingSet mappings, Members members, String owner,
                                      String readableName) {
        return runtimeName(mappings, members, owner, readableName, false);
    }

    /**
     * The runtime name {@code owner} declares for {@code readableName}, or the name unchanged.
     *
     * <p>Owner-keyed entries are taken as they are: they already name the type they apply to, and
     * second-guessing them would reject a mapping for a type the host has simply not loaded yet. Only
     * the unqualified tier is checked, because only it can be wrong about the owner.</p>
     */
    public static String runtimeName(MappingSet mappings, Members members, String owner,
                                     String readableName, boolean method) {
        String qualified = method ? mappings.runtimeMethodOfOwner(owner, readableName)
                : mappings.runtimeFieldOfOwner(owner, readableName);
        if (!qualified.equals(readableName)) return qualified;

        Set<String> declared = members == null ? Members.NONE.namesOf(owner) : members.namesOf(owner);
        if (declared.isEmpty()) return readableName;

        // ALREADY RESOLVES, SO LEAVE IT. A type that declares the readable name itself needs no rename,
        // and renaming anyway can only break a call that worked -- which is reachable whenever a
        // Minecraft class happens to declare a name the mapping also produces for something else.
        if (declared.contains(readableName)) return readableName;

        String anywhere = method ? mappings.runtimeMethodAnywhere(readableName)
                : mappings.runtimeFieldAnywhere(readableName);
        if (!anywhere.equals(readableName)) {
            // The unambiguous case, and the common one: one candidate, verified against this owner.
            return declared.contains(anywhere) ? anywhere : readableName;
        }

        // AMBIGUOUS OR UNMAPPED, so ask the owner. The scan only runs where the map would not answer,
        // which is the fifth of the data with a colliding readable name.
        for (String candidate : declared) {
            String readable = method ? mappings.readableMethod(owner, candidate)
                    : mappings.readableField(owner, candidate);
            if (readable.equals(readableName)) return candidate;
        }
        return readableName;
    }

    /**
     * Declared member names read from a classloader, walking superclasses and interfaces.
     *
     * <p>Reflection rather than bytes: this asks about types the host has <em>loaded</em>, which is the
     * same view the call will resolve against at run time.</p>
     */
    public static Members fromClassLoader(final ClassLoader loader) {
        return internalName -> {
            Set<String> names = new LinkedHashSet<>();
            try {
                collect(Class.forName(internalName.replace('/', '.'), false, loader),
                        names, new LinkedHashSet<>());
            } catch (Throwable notLoadable) {
                return names;
            }
            return names;
        };
    }

    private static void collect(Class<?> type, Set<String> into, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return;
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) into.add(method.getName());
        for (java.lang.reflect.Field field : type.getDeclaredFields()) into.add(field.getName());
        collect(type.getSuperclass(), into, seen);
        for (Class<?> face : type.getInterfaces()) collect(face, into, seen);
    }

    /** {@link Members} that answers each type once — the callers ask about the same owners repeatedly. */
    public static Members caching(Members delegate) {
        java.util.Map<String, Set<String>> cache = new java.util.concurrent.ConcurrentHashMap<>();
        return internalName -> cache.computeIfAbsent(internalName, delegate::namesOf);
    }
}
