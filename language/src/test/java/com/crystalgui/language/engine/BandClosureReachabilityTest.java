package com.crystalgui.language.engine;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * <b>Which jars in a band are actually reachable, and which are 13 MB of nothing.</b>
 *
 * <h3>Why this is a measurement and not an assertion</h3>
 *
 * <p>A band is pinned as a whole closure because {@code jdt.core} declares its platform dependencies as
 * open ranges and a partial pin resolves differently in six months. That is about <em>versions</em>. It
 * says nothing about whether we ever touch {@code org.eclipse.core.resources} — the workspace layer this
 * engine never opens — or the JNA it drags in, which is <b>3.4 MB of band 17 alone</b>.</p>
 *
 * <p>So this walks the constant pools outward from the roots we actually load and reports what nothing
 * reaches. It <b>fails no build</b>: the answer is input to a decision about what to ship, and a test that
 * failed whenever a closure gained a jar would be a test that has to be edited every time a dependency
 * moves.</p>
 *
 * <h3>What it can and cannot prove</h3>
 *
 * <p>A constant-pool reference is the honest question — {@code ExecutionNeedsNoGrammarTest} uses the same
 * reasoning — because if a class file names a type at all, some input can reach it. But it is
 * <b>reachability, not necessity</b>: a jar with no references into it certainly cannot be loaded by name,
 * while a jar with references may still never be touched at runtime. So an unreachable jar is a safe
 * candidate to drop and a reachable one is not automatically needed.</p>
 *
 * <p>Reflection defeats it, and Eclipse uses plenty. That is precisely why {@code smokeEngineBands} runs
 * afterwards on a real JVM of each band's era: this narrows the candidates, the smoke proves them.</p>
 */
public class BandClosureReachabilityTest {

    /** What we load by name. Everything else in a band is there because these asked for it. */
    private static final List<String> ROOT_PREFIXES = List.of(
            "org.eclipse.jdt.core", "ecj", "rhino");

    @Test
    public void reportUnreachableJarsPerBand() throws Exception {
        for (EngineBand band : EngineBand.values()) {
            String paths = System.getProperty("cgui.test.engineBand" + band.minimumFeatureVersion());
            if (paths == null || paths.isEmpty()) continue;
            report(band, paths);
        }
    }

    private void report(EngineBand band, String pathList) throws IOException {
        List<File> jars = new ArrayList<>();
        for (String entry : pathList.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            File jar = new File(entry.trim());
            if (jar.isFile() && jar.getName().endsWith(".jar")) jars.add(jar);
        }
        Assume.assumeFalse("no jars staged for " + band, jars.isEmpty());

        // Every class name -> the jar that declares it. First declaration wins, which matches how a
        // classloader resolves a split package and keeps the answer deterministic.
        Map<String, File> owners = new HashMap<>();
        Map<File, Set<String>> declared = new LinkedHashMap<>();
        for (File jar : jars) {
            Set<String> names = new HashSet<>();
            try (JarFile open = new JarFile(jar)) {
                for (JarEntry entry : java.util.Collections.list(open.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    String name = entry.getName().substring(0, entry.getName().length() - 6);
                    names.add(name);
                    owners.putIfAbsent(name, jar);
                }
            }
            declared.put(jar, names);
        }

        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (File jar : jars) {
            if (!isRoot(jar)) continue;
            for (String name : declared.get(jar)) {
                if (reached.add(name)) pending.add(name);
            }
        }

        while (!pending.isEmpty()) {
            String name = pending.poll();
            File owner = owners.get(name);
            if (owner == null) continue;
            for (String referenced : referencesOf(owner, name)) {
                if (owners.containsKey(referenced) && reached.add(referenced)) pending.add(referenced);
            }
        }

        Map<String, Long> unreachable = new TreeMap<>();
        long total = 0;
        long dead = 0;
        for (File jar : jars) {
            total += jar.length();
            Set<String> names = declared.get(jar);
            boolean anyReached = false;
            for (String name : names) {
                if (reached.contains(name)) {
                    anyReached = true;
                    break;
                }
            }
            if (!anyReached && !names.isEmpty()) {
                unreachable.put(jar.getName(), jar.length());
                dead += jar.length();
            }
        }

        System.out.println("=== band " + band + ": " + jars.size() + " jars, "
                + (total / 1024 / 1024) + " MB total");
        if (unreachable.isEmpty()) {
            System.out.println("   every jar has at least one class reachable from the roots");
        } else {
            System.out.println("   " + unreachable.size() + " jars unreachable, "
                    + (dead / 1024) + " KB:");
            unreachable.forEach((name, size) ->
                    System.out.printf("     %8d KB  %s%n", size / 1024, name));
        }
    }

    private static boolean isRoot(File jar) {
        for (String prefix : ROOT_PREFIXES) {
            if (jar.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    /** Every class this one names in its constant pool, internal-name form. */
    private static Set<String> referencesOf(File jar, String internalName) throws IOException {
        Set<String> referenced = new HashSet<>();
        try (JarFile open = new JarFile(jar)) {
            JarEntry entry = open.getJarEntry(internalName + ".class");
            if (entry == null) return referenced;
            try (InputStream bytes = open.getInputStream(entry)) {
                ClassReader reader = new ClassReader(bytes);
                // The constant pool directly: cheaper than visiting, and it is exactly the question --
                // a name in the pool is a name this class can resolve.
                char[] buffer = new char[reader.getMaxStringLength()];
                for (int index = 1; index < reader.getItemCount(); index++) {
                    int offset = reader.getItem(index);
                    if (offset == 0) continue;
                    try {
                        int tag = reader.readByte(offset - 1);
                        if (tag == 7) {
                            referenced.add(reader.readUTF8(offset, buffer));
                        } else if (tag == 1) {
                            collectFromDescriptor(reader.readUTF8(offset - 1 + 1, buffer), referenced);
                        }
                    } catch (RuntimeException unreadable) {
                        // A pool entry we cannot interpret is one fewer edge, not a failure: this is a
                        // report, and being conservative here can only ever OVER-report a jar as dead.
                    }
                }
            }
        }
        return referenced;
    }

    /** Pulls {@code Lcom/foo/Bar;} shapes out of a descriptor or signature string. */
    private static void collectFromDescriptor(String text, Set<String> into) {
        int index = text.indexOf('L');
        while (index >= 0) {
            int end = text.indexOf(';', index);
            if (end < 0) return;
            String candidate = text.substring(index + 1, end);
            if (!candidate.isEmpty() && candidate.indexOf(' ') < 0) into.add(candidate);
            index = text.indexOf('L', end);
        }
    }

    /** Unused, kept to document that Type is the correct tool if this ever needs descriptors properly. */
    @SuppressWarnings("unused")
    private static String erasureOf(String descriptor) {
        return Type.getType(descriptor).getInternalName();
    }
}
