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

    private final MappingSet mappings;
    private final Hierarchy hierarchy;
    private final MemberResolution.Members members;

    /** Owner-keyed mappings only — no unqualified entry is ever applied. @see MemberResolution */
    public InheritanceAwareRemapper(MappingSet mappings, Hierarchy hierarchy) {
        this(mappings, hierarchy, MemberResolution.Members.NONE);
    }

    /**
     * @param members resolves and verifies unqualified renames against the owner — <b>required</b> on any
     *                host whose mapping came from an unqualified format. @see MemberResolution
     */
    public InheritanceAwareRemapper(MappingSet mappings, Hierarchy hierarchy,
                                    MemberResolution.Members members) {
        this.mappings = mappings;
        this.hierarchy = hierarchy == null ? Hierarchy.NONE : hierarchy;
        this.members = members == null ? MemberResolution.Members.NONE : members;
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
            return MemberResolution.runtimeMethod(mappings, members, owner, name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String direct = mappings.runtimeFieldOfOwner(owner, name);
            if (!direct.equals(name)) return direct;
            String inherited = walkForField(owner, name, new LinkedHashSet<>());
            if (inherited != null) return inherited;
            return MemberResolution.runtimeField(mappings, members, owner, name);
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
