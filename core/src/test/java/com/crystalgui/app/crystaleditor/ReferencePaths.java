package com.crystalgui.app.crystaleditor;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <b>Why is this still in the heap?</b> — a reference walk that answers with the <em>path</em>.
 *
 * <p>Written because a {@code WeakReference} that refuses to clear tells you nothing at all: it is the
 * one assertion whose failure message can only ever be "still reachable". This walks from every static
 * in the jar plus whatever roots the caller names, and reports the chain of fields it followed — which
 * is what turned a red test into five separate fixes in one sitting (a detach that re-marked the
 * subtree it had just released, a document-level provider nothing withdrew, a rail's window commands,
 * a rail's focus subscription, and a project listing asked before the server had greeted).</p>
 *
 * <h3>What it deliberately does not follow</h3>
 *
 * <p><b>Weak structures.</b> A {@code WeakHashMap} key, a {@code Reference}, and a set built over
 * either keep nothing alive — and because this is a breadth-first search it returns the SHORTEST path,
 * so following one hides every real path behind it. Three of the first four answers this gave were
 * weak edges: {@code StyleEngine.appliedByElement} (documented weak on purpose),
 * {@code StyleEngine.LIVE} (ditto) and a listener queue.</p>
 *
 * <p><b>JDK internals it cannot open.</b> {@code setAccessible} throws for {@code ArrayList.elementData}
 * and {@code HashMap.table} under the module system, so collections are walked through their public
 * iterators instead — without that the first run of this walk crossed no collection at all, saw 5,113
 * objects, and confidently reported the target unreachable. Anything held only inside a structure with
 * no public iteration (an executor's work queue) is still invisible, which is why "no path" here means
 * "nothing in this jar's statics holds it", not "it is collectable".</p>
 */
final class ReferencePaths {

    private ReferencePaths() {
    }

    /**
     * The chain from a static or a named root to {@code target}, or null when there is none.
     *
     * @param extraRoots things that outlive the subject and are not statics — a workspace, a connection
     * @return the chain, or null when there is none
     */
    static String find(Object target, Map<Object, String> extraRoots) {
        Map<Object, String> roots = new IdentityHashMap<>(extraRoots);
        for (Class<?> type : ourClasses()) {
            for (Field field : declaredFields(type)) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) roots.putIfAbsent(value, type.getSimpleName() + "." + field.getName());
                } catch (Throwable unreadable) {
                    // a static that cannot be read cannot be the path either
                }
            }
        }
        return search(roots, target);
    }

    private static String search(Map<Object, String> roots, Object target) {
        Map<Object, String> seen = new IdentityHashMap<>();
        Deque<Object> queue = new ArrayDeque<>();
        for (Map.Entry<Object, String> root : roots.entrySet()) {
            if (root.getKey() == target) return root.getValue();
            seen.put(root.getKey(), root.getValue());
            queue.add(root.getKey());
        }
        while (!queue.isEmpty()) {
            Object at = queue.poll();
            String here = seen.get(at);
            Map<Object, String> named = new IdentityHashMap<>();
            for (Object next : childrenOf(at, named)) {
                if (next == null || seen.containsKey(next)) continue;
                String edge = named.containsKey(next) ? named.get(next)
                        : at.getClass().getSimpleName() + "[item]";
                String path = here + " -> " + edge;
                if (next == target) return path;
                seen.put(next, path);
                queue.add(next);
            }
        }
        return null;
    }

    private static List<Field> declaredFields(Class<?> type) {
        try {
            return List.of(type.getDeclaredFields());
        } catch (Throwable unreadable) {
            return List.of();
        }
    }

    private static List<Object> childrenOf(Object at, Map<Object, String> named) {
        List<Object> out = new ArrayList<>();
        Class<?> type = at.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) return out;
            for (int i = 0; i < Array.getLength(at); i++) out.add(Array.get(at, i));
            return out;
        }
        if (at instanceof String || at instanceof Number || at instanceof Class) return out;
        if (at instanceof java.lang.ref.Reference) return out;
        String name = type.getName();
        // See the class note: a weak edge keeps nothing alive, and a breadth-first search that follows
        // one reports it as THE answer and hides whatever is really holding the object.
        if (name.contains("Weak") || name.contains("SetFromMap") || name.contains("Synchronized")) {
            return out;
        }
        if (at instanceof Collection) {
            out.addAll((Collection<?>) at);
            return out;
        }
        if (at instanceof Map) {
            for (Map.Entry<?, ?> each : ((Map<?, ?>) at).entrySet()) {
                out.add(each.getKey());
                out.add(each.getValue());
            }
            return out;
        }
        for (Class<?> at2 = type; at2 != null && at2 != Object.class; at2 = at2.getSuperclass()) {
            for (Field field : declaredFields(at2)) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(at);
                    if (value == null) continue;
                    out.add(value);
                    named.putIfAbsent(value, at2.getSimpleName() + "." + field.getName());
                } catch (Throwable unreadable) {
                    // inaccessible is not a path
                }
            }
        }
        return out;
    }

    /** Every class in the built jar, so a root nobody thought of is still a root. */
    private static List<Class<?>> ourClasses() {
        Path classes = Paths.get("build/classes/java/main");
        if (!Files.isDirectory(classes)) classes = Paths.get("core/build/classes/java/main");
        List<Class<?>> out = new ArrayList<>();
        if (!Files.isDirectory(classes)) return out;
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path each : walk.toList()) {
                if (!each.getFileName().toString().endsWith(".class")) continue;
                String binary = classes.relativize(each).toString()
                        .replace(File.separatorChar, '.').replace('/', '.');
                binary = binary.substring(0, binary.length() - ".class".length());
                try {
                    // NOT INITIALISED. Loading a class to read its statics must not RUN its static
                    // block: half of these register something process-wide when they initialise, and a
                    // leak probe that creates the state it is looking for answers its own question.
                    out.add(Class.forName(binary, false, ReferencePaths.class.getClassLoader()));
                } catch (Throwable notLoadable) {
                    // a class that will not load holds nothing
                }
            }
        } catch (Exception unreadable) {
            return out;
        }
        return out;
    }
}
