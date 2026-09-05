package com.crystalgui.mc.net;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.net.mirror.UIElementMirror;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.net.wire.CgNetworkChannel;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

import net.minecraft.server.MinecraftServer;

/**
 * Boots a dedicated server, asserts the server-side stack came up, and stops it. The 1.20.x twin of
 * {@code CgUiServerSmoke}.
 *
 * <p><b>This is the only check in the build that can see its class of bug.</b> Every defect it exists
 * for is a <i>runtime</i> property -- "a client-only class is constructed on a server" -- so headless
 * tests cannot see it (they reach no loader), the GL harness cannot see it (a client by design), and no
 * import scan can answer a question about class loading. On 1.7.10 booting a server found three fatal
 * ones in a single run.</p>
 *
 * <p>Run with {@code ./gradlew :mc1201:forge:serverSmoke}. Exit 0 when every hard check passes, 1
 * otherwise; {@code WARN} lines are informational and never fail the run.</p>
 */
public final class ServerSmoke1201 {

    /** Set by the {@code serverSmoke} task. */
    public static final String PROPERTY = "crystalgui.server.smoke";

    /**
     * Where to write the verdict, so the build can tell "failed" from <b>"never ran"</b>. On 1.7.10 a
     * port clash meant the started event never fired, not one assertion ran, and Gradle reported
     * BUILD SUCCESSFUL -- a check that is green when it did not run is worse than no check.
     */
    public static final String REPORT_PROPERTY = "crystalgui.server.smoke.report";

    /** Package whose every class is client-only; enumerated rather than listed. @see #auditClientList */
    private static final String CLIENT_PACKAGE = "com.crystalgui.mc.client";

    /**
     * Classes that must never be loaded in a dedicated server process. Each is present on a dev server's
     * classpath and absent from a real one, so a load here is a hard {@code NoClassDefFoundError} there.
     *
     * <p>Everything in {@link #CLIENT_PACKAGE} is added at run time. The 1.7.10 list was hand-written and
     * recorded its own decay -- three classes added after it was written were never checked, because a
     * guard that fails to grow reports success.</p>
     */
    private static final List<String> NEVER_LOADED_ON_A_SERVER = Arrays.asList(
            // Client-side content that does not live in the client package.
            "com.crystalgui.mc.example.MachineExampleClient1201",
            // Naming this from a common path is the commonest spelling of the bug.
            "net.minecraft.client.Minecraft",
            // The entry point to every GL resource CrystalGUI owns; it registers CgUiLifecycle from a
            // static initialiser, so loading it means something asked a headless process to paint.
            "com.crystalgui.render.CgUiPaintContext");

    private ServerSmoke1201() {}

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Runs every check, prints the report, and stops the server. Called from each loader's
     * server-STARTED event -- late enough that a mod which failed to load has already taken the process
     * down, so reaching this at all is most of the assertion.
     */
    public static void run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        check(lines, failures, "the process is a dedicated server",
                server != null && server.isDedicatedServer(),
                "not a dedicated server -- this check proves nothing anywhere else");

        // The one that was fatal on 1.7.10: preInit died and every dependent mod errored with it.
        boolean platform;
        String platformDetail = "";
        try {
            CgPlatform.ensureCreated();
            platform = true;
        } catch (Throwable notRegistered) {
            platform = false;
            platformDetail = String.valueOf(notRegistered);
        }
        check(lines, failures, "CrystalGraphics platform bundle registered", platform, platformDetail);

        // An unavailable channel is a warn-and-return inside register(), so checking only the lifecycle
        // flag below would report the symptom and hide the cause.
        boolean channel;
        try {
            channel = CgPlatform.get(CgNetworkChannel.SERVICE).isAvailable();
        } catch (Throwable noChannel) {
            channel = false;
        }
        check(lines, failures, "network channel available", channel,
                "the loader's NetworkChannel1201 did not register, or the SERVICE slot holds the no-op default");

        // Warn-and-return on failure rather than a throw, so a server with no networking boots happily
        // and looks fine.
        check(lines, failures, "connection lifecycle installed", Connections1201.isRegistered(),
                "Connections1201.register() stood down; see the [cgui-net] warning above");

        // A connection binds only contributors registered BEFORE it opens, and no peer exists yet -- so
        // this is the last moment the set can still be wrong and the first at which it certainly is not.
        Set<String> contributors = Protocols.contributors();
        check(lines, failures, "protocol contributors bound " + contributors,
                contributors.contains("workspace"),
                "expected 'workspace'; WorkspaceHost1201.register() runs before Connections1201.register()");

        checkDescriptionRoundTrip(lines, failures);
        checkNothingClientSideLoaded(lines, failures);
        reportGlDivergence(lines);

        String report = render(lines, failures);
        print(report);
        writeReport(report, failures.isEmpty());
        stop(server, failures.isEmpty());
    }

    // ── the checks ────────────────────────────────────────────────────────────────────────────────

    /**
     * A description round-trips with no GL anywhere. Content-addressed, so encoding twice and comparing
     * hashes asserts determinism rather than merely smoke-testing the codec.
     */
    private static void checkDescriptionRoundTrip(List<String> lines, List<String> failures) {
        String detail = "";
        boolean ok = false;
        try {
            UIElementRegistry.bootstrap();

            UIElement root = new UIElement();
            root.setId("smoke-root");
            root.addClass("panel");
            UIElement child = new UIElement();
            child.setId("smoke-child");
            root.append(child);

            Object encoded = new UIElementMirror<>(PlainOps.INSTANCE).describe(root);
            String hashA = ContentHash.of(PlainOps.INSTANCE, encoded);
            String hashB = ContentHash.of(PlainOps.INSTANCE,
                    new UIElementMirror<>(PlainOps.INSTANCE).describe(root));

            UIElement decoded = new UIElementMirror<>(PlainOps.INSTANCE).decode(encoded);

            boolean stable = hashA.equals(hashB);
            boolean shape = decoded != null
                    && "smoke-root".equals(decoded.id())
                    && decoded.children().size() == 1;

            ok = stable && shape;
            detail = "hash=" + hashA + (stable ? "" : " UNSTABLE across two encodes")
                    + (shape ? "" : " decoded shape wrong");
        } catch (Throwable failed) {
            detail = String.valueOf(failed);
        }
        check(lines, failures, "UI description round-trips headlessly", ok, detail);
    }

    private static void checkNothingClientSideLoaded(List<String> lines, List<String> failures) {
        List<String> subjects = new ArrayList<>(NEVER_LOADED_ON_A_SERVER);
        subjects.addAll(auditClientList(lines));

        Set<String> definedByTheJvm = loadedFromJvmLog();

        List<String> loaded = new ArrayList<>();
        List<String> undetermined = new ArrayList<>();
        for (String name : subjects) {
            Boolean isLoaded = definedByTheJvm != null
                    ? Boolean.valueOf(definedByTheJvm.contains(name))
                    : loadedByAnyLoader(name);
            if (isLoaded == null) undetermined.add(name);
            else if (isLoaded) loaded.add(name);
        }
        if (definedByTheJvm != null) {
            lines.add("INFO  load state read from the JVM's own class-load log ("
                    + definedByTheJvm.size() + " classes defined this run)");
        }

        if (!undetermined.isEmpty()) {
            // Said out loud rather than counted as a pass: a check that cannot run and reports green is
            // worse than no check.
            Throwable why = loadStateFailure;
            lines.add("WARN  could not determine load state for " + undetermined.size() + " class(es)"
                    + " -- findLoadedClass was not reachable: " + why
                    + " (the PASS below therefore covers only the rest)");
        }

        check(lines, failures,
                "no client-only class loaded on the server (" + subjects.size() + " checked"
                        + (undetermined.isEmpty() ? "" : ", of those determinable") + ")",
                loaded.isEmpty(),
                loaded.isEmpty() ? "" : "LOADED: " + loaded
                        + " -- something on a common path reached a client class; in production this is a "
                        + "NoClassDefFoundError at that point, not here");
    }

    /**
     * Every class in {@link #CLIENT_PACKAGE}, read off the code source without loading anything.
     *
     * <p>Enumerating rather than listing is what stops the guard rotting: a class added to that package
     * is covered the day it is written. A container we cannot read is a WARN and an empty list, never a
     * silent pass.</p>
     */
    private static List<String> auditClientList(List<String> lines) {
        List<String> found = classesIn(CLIENT_PACKAGE);
        if (found == null) {
            lines.add("WARN  could not enumerate " + CLIENT_PACKAGE
                    + " from the code source; only the explicit list was checked");
            return Collections.emptyList();
        }
        lines.add("INFO  " + CLIENT_PACKAGE + " contributes " + found.size()
                + " class(es) to the never-loaded set: " + new TreeSet<>(found));
        return found;
    }

    /**
     * @return the top-level class names in {@code pkg}, or {@code null} if no container could be read.
     *
     * <p>The code source first, which covers a plain directory and a shipped jar. Under FML it is a
     * {@code union:} URL and cannot be walked, so the build passes the directory instead -- otherwise
     * this degrades to the hand-written list on the one loader it matters most on.</p>
     */
    @Nullable
    private static List<String> classesIn(String pkg) {
        List<String> fromCodeSource = scan(codeSourceRoot(), pkg);
        if (fromCodeSource != null) return fromCodeSource;

        String hint = System.getProperty("crystalgui.server.smoke.classdir", "");
        if (hint.isEmpty()) return null;

        List<String> found = new ArrayList<>();
        boolean any = false;
        for (String dir : hint.split(File.pathSeparator)) {
            List<String> in = scan(Paths.get(dir), pkg);
            if (in != null) {
                any = true;
                found.addAll(in);
            }
        }
        return any ? found : null;
    }

    @Nullable
    private static Path codeSourceRoot() {
        try {
            CodeSource source = ServerSmoke1201.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            return "file".equals(uri.getScheme()) ? Paths.get(uri) : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** @return class names under {@code pkg} in {@code root}, or {@code null} if it is not readable. */
    @Nullable
    private static List<String> scan(@Nullable Path root, String pkg) {
        if (root == null) return null;
        String dir = pkg.replace('.', '/');
        try {
            if (Files.isDirectory(root)) {
                Path packageDir = root.resolve(dir);
                if (!Files.isDirectory(packageDir)) return Collections.emptyList();
                try (Stream<Path> entries = Files.list(packageDir)) {
                    return entries.map(p -> p.getFileName().toString())
                            .filter(ServerSmoke1201::isTopLevelClassFile)
                            .map(n -> pkg + "." + n.substring(0, n.length() - ".class".length()))
                            .collect(Collectors.toList());
                }
            }
            if (Files.isRegularFile(root)) {
                List<String> names = new ArrayList<>();
                try (ZipFile jar = new ZipFile(root.toFile())) {
                    Enumeration<? extends ZipEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (!entry.startsWith(dir + "/")) continue;
                        String simple = entry.substring(dir.length() + 1);
                        if (isTopLevelClassFile(simple)) {
                            names.add(pkg + "." + simple.substring(0, simple.length() - ".class".length()));
                        }
                    }
                }
                return names;
            }
            return null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** Nested classes are excluded: loading one implies its outer, which is already on the list. */
    private static boolean isTopLevelClassFile(String fileName) {
        return fileName.endsWith(".class") && fileName.indexOf('$') < 0 && fileName.indexOf('/') < 0;
    }

    /** Where {@code -Xlog:class+load=info} puts the class name on each line. */
    private static final String LOG_MARKER = "[class,load] ";

    /**
     * Every class the JVM defined this run, read from its own {@code -Xlog:class+load} file.
     *
     * <p>The reflective route cannot work here: {@code findLoadedClass} is protected, and the mod runs in
     * FML's named module, which no static {@code --add-opens} can name because the module does not exist
     * at JVM start. This needs no access to anything and is authoritative.</p>
     *
     * @return {@code null} when the log was not requested or is unreadable, so the caller can fall back.
     */
    @Nullable
    private static Set<String> loadedFromJvmLog() {
        String path = System.getProperty("crystalgui.server.smoke.classlog", "");
        if (path.isEmpty()) return null;
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) return null;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(ServerSmoke1201::classNameIn)
                    .filter(name -> name != null)
                    .collect(Collectors.toSet());
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** {@code [0.1s][info][class,load] java.lang.Object source: ...} -> {@code java.lang.Object}. */
    @Nullable
    private static String classNameIn(String line) {
        int start = line.indexOf(LOG_MARKER);
        if (start < 0) return null;
        start += LOG_MARKER.length();
        int end = line.indexOf(" source:", start);
        return (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
    }

    /**
     * Whether {@code name} has already been defined by this class's loader or any of its parents.
     *
     * <p>Deliberately not {@code Class.forName(name, false, loader)}: that would <b>load the class</b>,
     * which is the very thing being asserted against -- the check would then always pass and would create
     * the condition it exists to detect. {@code null} means the question could not be asked at all.</p>
     */
    @Nullable
    private static Boolean loadedByAnyLoader(String name) {
        try {
            Method find = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            find.setAccessible(true);
            for (ClassLoader loader = ServerSmoke1201.class.getClassLoader();
                 loader != null; loader = loader.getParent()) {
                if (find.invoke(loader, name) != null) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable notAvailable) {
            loadStateFailure = notAvailable;
            return null;
        }
    }

    /** Why { #loadedByAnyLoader} could not answer, so the WARN names a cause rather than a fact. */
    
    private static volatile Throwable loadStateFailure;

    /**
     * Whether a GL backend was installed, which on a server it should not have been. <b>A WARN, never a
     * failure</b> -- it reports a fact about the environment rather than a defect in the code.
     *
     * <p>1.7.10's dev server reports "not installed", matching production. ModDevGradle's is unmeasured,
     * and this line is the only thing that would say so.</p>
     */
    private static void reportGlDivergence(List<String> lines) {
        try {
            Class<?> cgGl = Class.forName("com.crystalgraphics.platform.gl.CgGL");
            Field backend = cgGl.getDeclaredField("backend");
            backend.setAccessible(true);
            boolean installed = backend.get(null) != null;
            lines.add("WARN  GL backend " + (installed ? "IS" : "is not") + " installed on this server"
                    + (installed
                    ? " -- expected in a DEV run (merged classpath), and NOT what production does: there "
                    + "CgPlatform.register catches NoClassDefFoundError and CgGL stays null. Server-side "
                    + "code that touches CgGL therefore passes here and NPEs in production."
                    : " -- matching production."));
        } catch (Throwable cannotTell) {
            lines.add("WARN  could not determine whether a GL backend is installed (" + cannotTell + ")");
        }
    }

    // ── reporting and shutdown ────────────────────────────────────────────────────────────────────

    private static void check(List<String> lines, List<String> failures,
                              String what, boolean ok, String detail) {
        lines.add((ok ? "PASS  " : "FAIL  ") + what
                + (ok || detail.isEmpty() ? "" : System.lineSeparator() + "        " + detail));
        if (!ok) failures.add(what);
    }

    private static String render(List<String> lines, List<String> failures) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append("=================== CrystalGUI 1.20.x dedicated-server smoke ===================").append(nl);
        for (String line : lines) out.append(line).append(nl);
        out.append("--------------------------------------------------------------------------------").append(nl);
        out.append(failures.isEmpty()
                ? "RESULT: pass -- the server-side stack is up"
                : "RESULT: FAIL -- " + failures.size() + " check(s): " + failures).append(nl);
        out.append("================================================================================");
        return out.toString();
    }

    /**
     * System.out AND the logger: the logger is what a CI scraper reads, System.out is what survives a
     * log4j configuration that routes our category elsewhere. A report that can be swallowed is not one.
     */
    private static void print(String report) {
        System.out.println(System.lineSeparator() + report);
        System.out.flush();
        CrystalGuiCore.LOGGER.info(report);
    }

    /** @see #REPORT_PROPERTY */
    private static void writeReport(String report, boolean passed) {
        String path = System.getProperty(REPORT_PROPERTY, "");
        if (path.isEmpty()) return;
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()),
                StandardCharsets.UTF_8)) {
            // The verdict FIRST, on its own line, so the build reads one line rather than parsing a
            // report -- and so a truncated write is a failure rather than a plausible pass.
            writer.write((passed ? "PASS" : "FAIL") + System.lineSeparator());
            writer.write(report);
        } catch (IOException cannotWrite) {
            // Said out loud, and fatal by omission: with no file the build refuses, which is the correct
            // reading of "the check could not report".
            System.err.println("[cgui-smoke] could not write the report to " + path + ": " + cannotWrite);
        }
    }

    /**
     * Stops the server, with the exit code carrying the verdict.
     *
     * <p>On success a clean halt, so the world saves and the stopping event runs -- itself part of what
     * is being smoke-tested, since that is what closes every connection. On failure {@code Runtime.halt}
     * after flushing, because a clean shutdown exits 0 and the verdict has to reach Gradle;
     * {@code System.exit} would run shutdown hooks that can throw on a half-initialised server and mask
     * the code.</p>
     */
    private static void stop(@Nullable MinecraftServer server, boolean passed) {
        if (passed) {
            if (server != null) server.halt(false);
            return;
        }
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }
}
