package com.crystalgui.language.map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiled script bytecode, readable → runtime — <b>including the declarations that override</b>.
 *
 * <h3>Why a plain {@code ClassRemapper} is not enough, and fails in the worst way</h3>
 *
 * <p>ASM's {@code Remapper} is asked {@code mapMethodName(owner, name, descriptor)} for every
 * reference. For a <em>call</em> to {@code world.getBlock(...)} the owner is {@code World} and the
 * mapping is found. For the script's own <em>declaration</em> of {@code getBlock} — an override — the
 * owner is the script class, which no mapping mentions. So the call sites are renamed and the
 * declaration is not.</p>
 *
 * <p>The result compiles, verifies, loads and runs. The override is simply no longer an override: the
 * method sits there under the readable name, the runtime calls the one it inherited, and <b>the script's
 * code silently never executes</b>. No exception, no diagnostic, nothing to search for. §15.5 calls this
 * out as the worst failure shape there is and it is the reason this class exists.</p>
 *
 * <h3>The fix is a walk, and it needs the hierarchy</h3>
 *
 * <p>Given a declaration in a class with no mapping, walk its superclasses and interfaces and ask each
 * whether <em>it</em> maps that name. The first that does is the method being overridden, so the
 * declaration takes its runtime name. That needs to know each class's supertypes — hence
 * {@link Hierarchy}, which for a script's own classes comes from the bytes being remapped and for
 * everything else comes from the host's loader.</p>
 *
 * <p><b>Descriptors are deliberately not part of the match.</b> A real mapping set keys on name and
 * descriptor, and the descriptor of an override may differ from the mapped one by covariance or by a
 * generic bridge. Matching on name alone within a known supertype is what tiny-remapper's propagation
 * does for the same reason, and the failure mode of being too permissive here (renaming a same-named
 * unrelated method) is visible, while being too strict is the silent one above.</p>
 */
public final class InheritanceAwareRemapper {

    /** Where supertypes come from. Script classes are not loadable yet, so this cannot be reflection. */
    public interface Hierarchy {
        /** Superclass then interfaces, in internal-name form; empty for {@code java/lang/Object}. */
        List<String> supertypesOf(String internalName);

            /** Nothing has a supertype — the identity case, and what a test with no hierarchy uses. */
        Hierarchy NONE = internalName -> java.util.Collections.emptyList();
    }

    /**
     * The member names a runtime type declares — the owner, asked directly.
     *
     * <h3>Why the mapping alone cannot finish the job</h3>
     *
     * <p>MCP's data is unqualified: SRG names are globally unique, so the files carry no owner. That
     * makes SRG → readable a function and readable → SRG <b>not</b> one — measured on
     * {@code mcp_stable/12}, 357 method names and 329 field names are claimed by more than one runtime
     * name, covering about a fifth of the rows. {@code getUnlocalizedName} is one of them.</p>
     *
     * <p>{@link MappingSet} refuses to guess between them, which is right and leaves the rename
     * unresolved — and an unresolved rename is a {@code NoSuchMethodError} at the call. The owner is
     * what settles it, and the owner is right here: enumerate what the runtime type declares, map each
     * name FORWARD (the direction that is a function), and keep the one whose readable name is the one
     * the script wrote. No {@code packaged.srg}, no second mapping format.</p>
     *
     * <h3>It also stops an unqualified rename escaping its own namespace</h3>
     *
     * <p>{@code run} and {@code add} are ordinary readable names that MCP maps. Applied without an
     * owner they renamed the SCRIPT'S OWN {@code run()} to a {@code func_*} — leaving {@code ScriptHost}
     * reporting that a class it had just compiled had no entry point — and would do the same to
     * {@code list.add(...)}. A type that will not load answers empty, which is the correct answer for
     * the classes being compiled: they are not loadable yet, and their members are authored names rather
     * than readable-namespace aliases.</p>
     */
    public interface Members {

        /** Every member name {@code internalName} declares, supertypes included; empty if unloadable. */
        Set<String> namesOf(String internalName);

        /** Knows nothing, so no unqualified rename is ever applied. The default, and what a test uses. */
        Members NONE = internalName -> java.util.Collections.emptySet();
    }

    private final MappingSet mappings;
    private final Hierarchy hierarchy;
    private final Members members;

    /** Owner-keyed mappings only — no unqualified entry is ever applied. @see Members */
    public InheritanceAwareRemapper(MappingSet mappings, Hierarchy hierarchy) {
        this(mappings, hierarchy, Members.NONE);
    }

    /** @param members resolves and verifies unqualified renames against the owner. @see Members */
    public InheritanceAwareRemapper(MappingSet mappings, Hierarchy hierarchy, Members members) {
        this.mappings = mappings;
        this.hierarchy = hierarchy == null ? Hierarchy.NONE : hierarchy;
        this.members = members == null ? Members.NONE : members;
    }

    /**
     * Remaps a whole set of compiled classes.
     *
     * <p>Takes the set rather than one class because the hierarchy of a script's own classes is only
     * knowable from the set — a nested class extending an MC type is remapped correctly only if its
     * supertypes can be looked up, and they are in the same map.</p>
     */
    public Map<String, byte[]> remap(Map<String, byte[]> classes) {
        if (mappings.isIdentity()) {
            // THE COMMON CASE, AND IT COSTS NOTHING. A dev environment, NeoForge, the harness and every
            // non-MC host already speak the readable namespace. Returning the input untouched means
            // those hosts take the same code path rather than a second one nobody exercises.
            return classes;
        }
        Hierarchy combined = withLocalClasses(classes);
        Remapper remapper = new PropagatingRemapper(combined);

        Map<String, byte[]> out = new HashMap<>(classes.size());
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassReader reader = new ClassReader(entry.getValue());
            // COMPUTE_MAXS and not COMPUTE_FRAMES: renaming changes no stack shape, so recomputing
            // frames would mean loading every referenced type to merge them -- which on an MC host
            // means loading MC classes at compile time, and which can fail on a type that is not
            // loadable yet. Nothing here needs it.
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            out.put(remapper.map(entry.getKey().replace('.', '/')).replace('/', '.'),
                    writer.toByteArray());
        }
        return out;
    }

    /**
     * The host's hierarchy, plus the classes being remapped.
     *
     * <p>A script's own classes are not loadable — they have not been defined yet — so their supertypes
     * have to come from the bytes. Reading them here rather than requiring the caller to supply them
     * is what makes a nested class that extends an MC type work without the caller knowing it exists.</p>
     */
    private Hierarchy withLocalClasses(Map<String, byte[]> classes) {
        final Map<String, List<String>> local = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            ClassReader reader = new ClassReader(entry.getValue());
            List<String> supertypes = new ArrayList<>();
            if (reader.getSuperName() != null) supertypes.add(reader.getSuperName());
            for (String face : reader.getInterfaces()) supertypes.add(face);
            local.put(reader.getClassName(), supertypes);
        }
        return internalName -> {
            List<String> own = local.get(internalName);
            return own != null ? own : hierarchy.supertypesOf(internalName);
        };
    }

    /** The remapper proper: direct lookups first, then the supertype walk. */
    private final class PropagatingRemapper extends Remapper {

        private final Hierarchy hierarchy;

        private final Map<String, Set<String>> declaredNames = new java.util.HashMap<>();

        PropagatingRemapper(Hierarchy hierarchy) {
            this.hierarchy = hierarchy;
        }

        @Override
        public String map(String internalName) {
            return mappings.runtimeClass(internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String direct = mappings.runtimeMethodOfOwner(owner, name);
            if (!direct.equals(name)) return direct;
            String inherited = walkForMethod(owner, name, new LinkedHashSet<>());
            if (inherited != null) return inherited;
            return fromOwner(owner, name, true);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String direct = mappings.runtimeFieldOfOwner(owner, name);
            if (!direct.equals(name)) return direct;
            String inherited = walkForField(owner, name, new LinkedHashSet<>());
            if (inherited != null) return inherited;
            return fromOwner(owner, name, false);
        }

        /**
         * The runtime name {@code owner} declares for this readable name, or the name unchanged.
         *
         * <p>The unqualified tier, made owner-correct. Two things fall out of asking the owner rather
         * than the map: an ambiguous readable name is resolved (only one of the candidates is declared
         * here), and a name that is not this type's at all is left alone. @see Members</p>
         *
         * <p>The fast path is the map: where the unqualified answer is unique AND the owner declares it,
         * there is nothing to search. The scan only runs for a name the map will not answer, which is
         * the ambiguous fifth of the data.</p>
         */
        private String fromOwner(String owner, String readableName, boolean method) {
            String anywhere = method ? mappings.runtimeMethodAnywhere(readableName)
                    : mappings.runtimeFieldAnywhere(readableName);
            Set<String> declared = names(owner);
            if (!anywhere.equals(readableName)) {
                return declared.contains(anywhere) ? anywhere : readableName;
            }
            for (String candidate : declared) {
                String readable = method ? mappings.readableMethod(owner, candidate)
                        : mappings.readableField(owner, candidate);
                if (readable.equals(readableName)) return candidate;
            }
            return readableName;
        }

        /** {@link Members#namesOf}, once per owner per remap — ECJ names the same types repeatedly. */
        private Set<String> names(String owner) {
            Set<String> known = declaredNames.get(owner);
            if (known == null) {
                known = members.namesOf(owner);
                declaredNames.put(owner, known == null ? java.util.Collections.emptySet() : known);
                known = declaredNames.get(owner);
            }
            return known;
        }

        /**
         * @param seen guards against a cycle. A malformed or adversarial class file can describe one,
         *             and an unguarded walk here hangs the compile rather than failing it
         */
        private String walkForMethod(String owner, String name, Set<String> seen) {
            if (!seen.add(owner)) return null;
            for (String parent : hierarchy.supertypesOf(owner)) {
                String mapped = mappings.runtimeMethodOfOwner(parent, name);
                if (!mapped.equals(name)) return mapped;
                String deeper = walkForMethod(parent, name, seen);
                if (deeper != null) return deeper;
            }
            return null;
        }

        private String walkForField(String owner, String name, Set<String> seen) {
            if (!seen.add(owner)) return null;
            for (String parent : hierarchy.supertypesOf(owner)) {
                String mapped = mappings.runtimeFieldOfOwner(parent, name);
                if (!mapped.equals(name)) return mapped;
                String deeper = walkForField(parent, name, seen);
                if (deeper != null) return deeper;
            }
            return null;
        }
    }

    /**
     * Every declared member name, read from the host's loader and walking supertypes.
     *
     * <p>Inherited members count: a script calling {@code block.getUnlocalizedName()} names the type it
     * has rather than the one that declares the method, so a scan confined to the exact owner would miss
     * every inherited call. Interfaces are walked for the same reason.</p>
     *
     * <p>A type that will not load answers empty — see {@link Members} for why that is the right answer
     * and not a degradation.</p>
     */
    public static Members membersFromClassLoader(final ClassLoader loader) {
        return internalName -> {
            Set<String> names = new LinkedHashSet<>();
            try {
                collectMembers(Class.forName(internalName.replace('/', '.'), false, loader),
                        names, new LinkedHashSet<>());
            } catch (Throwable notLoadable) {
                return names;
            }
            return names;
        };
    }

    private static void collectMembers(Class<?> type, Set<String> into, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return;
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) into.add(method.getName());
        for (java.lang.reflect.Field field : type.getDeclaredFields()) into.add(field.getName());
        collectMembers(type.getSuperclass(), into, seen);
        for (Class<?> face : type.getInterfaces()) collectMembers(face, into, seen);
    }

    /** Supertypes read from the host's loader — everything the script did not itself declare. */
    public static Hierarchy fromClassLoader(final ClassLoader loader) {
        return internalName -> {
            try {
                Class<?> type = Class.forName(internalName.replace('/', '.'), false, loader);
                List<String> supertypes = new ArrayList<>();
                if (type.getSuperclass() != null) {
                    supertypes.add(type.getSuperclass().getName().replace('.', '/'));
                }
                for (Class<?> face : type.getInterfaces()) {
                    supertypes.add(face.getName().replace('.', '/'));
                }
                return supertypes;
            } catch (Throwable notLoadable) {
                // A class that cannot be loaded contributes no supertypes rather than failing the
                // remap. On an MC host this is the ordinary case for a type the loader has not reached
                // yet, and refusing would make remapping order-dependent.
                return java.util.Collections.emptyList();
            }
        };
    }
}
