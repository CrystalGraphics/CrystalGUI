package com.crystalgui.gradle;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails a mod that reaches past CrystalGUI's API, or ships CrystalGUI inside itself.
 *
 * <ul>
 *   <li>A reference to {@code com.crystalgui.mc} or {@code com.crystalgraphics.mc}: the loader hosts, whose
 *       package differs per loader in the shipped jar, so what compiles against one never links. Except
 *       {@code com.crystalgraphics.mc.shared}, the variant selector a single-jar mod's bootstrappers call,
 *       which ships once and unrelocated.</li>
 *   <li>A class of either mod in the mod's own jar: two copies of one class across two mods.</li>
 * </ul>
 *
 * <p>Read from the compiled classes' constant pools, which name every class a file touches, so a fully
 * qualified name is caught as surely as an import.</p>
 */
public abstract class CheckCrystalGuiApi extends DefaultTask {

    static final List<String> INTERNAL = Arrays.asList("com/crystalgui/mc/", "com/crystalgraphics/mc/");
    /** The one package under {@link #INTERNAL} a mod may name: {@code com.crystalgraphics:mc-shared}. */
    static final String SHARED = "com/crystalgraphics/mc/shared/";
    static final List<String> OURS = Arrays.asList("com/crystalgui/", "com/crystalgraphics/");

    /** The mod's compiled classes. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /** The mod's jar, if it builds one. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getJar();

    @TaskAction
    void check() throws IOException {
        List<String> problems = new ArrayList<>();
        for (File root : getClasses()) {
            if (!root.isDirectory()) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root.toPath())) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    TreeSet<String> reached = new TreeSet<>();
                    try (InputStream in = Files.newInputStream(p)) {
                        for (String utf8 : utf8Constants(in)) {
                            String outside = utf8.replace(SHARED, "");
                            INTERNAL.stream().filter(outside::contains).findFirst()
                                .ifPresent(prefix -> reached.add(utf8));
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    reached.forEach(name -> problems.add(root.toPath().relativize(p) + " names " + name));
                });
            }
        }
        for (File jar : getJar()) {
            if (!jar.isFile()) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class") && OURS.stream().anyMatch(name::startsWith)) {
                        problems.add(jar.getName() + " bundles " + name);
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder message = new StringBuilder("crystalgui: this mod reaches past CrystalGUI's API."
                + " com.crystalgui.mc and com.crystalgraphics.mc are the per-loader hosts, a different package on"
                + " every loader, and both mods ship as jars of their own:\n");
            problems.stream().limit(40).forEach(p -> message.append("  ").append(p).append('\n'));
            if (problems.size() > 40) {
                message.append("  ... and ").append(problems.size() - 40).append(" more\n");
            }
            throw new GradleException(message.append("What a mod needs from the loader side is a core seam;"
                + " one missing is ours to add.").toString());
        }
    }

    /** The CONSTANT_Utf8 entries of one class file: every class, descriptor and string it names. */
    static List<String> utf8Constants(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != 0xCAFEBABE) {
            return List.of();
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<String> utf8 = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf8.add(in.readUTF()); break;
                case 7: case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                case 15: in.skipBytes(3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes(4); break;
                case 5: case 6: in.skipBytes(8); i++; break;
                default: throw new IOException("constant pool tag " + tag + " at entry " + i);
            }
        }
        return utf8;
    }
}
