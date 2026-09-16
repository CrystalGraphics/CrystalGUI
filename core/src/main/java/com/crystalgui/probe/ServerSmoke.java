package com.crystalgui.probe;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
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

/**
 * <b>Boots a dedicated server, asserts the server-side stack came up, and stops it.</b>
 *
 * <pre>{@code
 * // from the loader's server-STARTED event:
 * if (ServerSmoke.enabled()) ServerSmoke.run(new MyServerSmokeHost());
 * }</pre>
 *
 * <p>Run it from <b>server-started</b>, not server-starting: that is late enough that a mod which
 * failed to load has already taken the process down, so reaching this at all is most of the assertion.
 * It always ends the process — cleanly on a pass, {@code halt(1)} on a failure.</p>
 *
 * <h3>Why this exists, and why no other check replaces it</h3>
 *
 * <p>Every defect it was built for is a <em>runtime</em> property — "a client-only class is constructed
 * on a server" — so nothing static can see one. Headless tests assert by <i>absence</i> and reach no
 * loader; the GL harness is a client with a real context by design; an import guard sees imports, and
 * every offending line was a legal import in a module that legitimately has both halves.</p>
 *
 * <h3>Easy to get wrong</h3>
 *
 * <ul>
 *   <li>{@link Host#clientPackage()} is <b>enumerated, never listed</b>. A hand-written list is a guard
 *       that rots: the 1.7.10 one named a class that had been deleted — so it passed forever — while
 *       three classes added after it was written were never checked at all.</li>
 *   <li>Load state is read from the JVM's own {@code -Xlog:class+load} file when the build supplies one.
 *       The reflective fallback cannot work under a module system that names the mod's module, and a
 *       check that cannot run must say so rather than report green.</li>
 *   <li>Nothing here may <em>load</em> a subject class. {@code Class.forName} would create the very
 *       condition being detected, after which the check passes forever.</li>
 * </ul>
 *
 * @see Host for the six things a loader has to answer
 */
public final class ServerSmoke {

    /** Set by the {@code serverSmoke} task. */
    public static final String PROPERTY = "crystalgui.server.smoke";

    /**
     * Where the verdict goes, so the build can tell "failed" from "never ran". @see ProbeReport
     *
     * <p>This check is where that rule was learned: a port clash meant the started event never fired,
     * not one assertion executed, and Gradle reported BUILD SUCCESSFUL. A check that is green when it
     * did not run is worse than no check, because it is now also a claim.</p>
     */
    public static final String REPORT_PROPERTY = "crystalgui.server.smoke.report";

    /** Directories to enumerate when the code source is not walkable. @see #classesIn */
    public static final String CLASSDIR_PROPERTY = "crystalgui.server.smoke.classdir";

    /** The JVM's own {@code -Xlog:class+load=info} output. @see #loadedFromJvmLog */
    public static final String CLASSLOG_PROPERTY = "crystalgui.server.smoke.classlog";

    /**
     * Subjects every host shares. Each is present on a dev server's merged classpath and absent from a
     * real one, so a load here is a hard {@code NoClassDefFoundError} there.
     *
     * <p><b>LWJGL is deliberately absent</b>, and that is a finding rather than an omission — see
     * {@link #reportGlDivergence}.</p>
     */
    private static final List<String> NEVER_LOADED_ANYWHERE = Collections.singletonList(
            // The entry point to every GL resource CrystalGUI owns; it registers CgUiLifecycle from a
            // static initialiser, so loading it means something asked a headless process to paint.
            "com.crystalgui.render.CgUiPaintContext");

    /** Where {@code -Xlog:class+load=info} puts the class name on each line. */
    private static final String LOG_MARKER = "[class,load] ";

    /** Why {@link #loadedByAnyLoader} could not answer, so the WARN names a cause rather than a fact. */
    private static volatile Throwable loadStateFailure;

    private ServerSmoke() {}

    /**
     * What only the running game can answer. <b>Implement it in the loader module</b> — this object's
     * own class is the anchor the client package is enumerated from, so it has to live in the same
     * container as the classes it is asking about.
     */
    public interface Host {

        /** For the report banner, e.g. {@code "1.7.10"} or {@code "1.20.x"}. */
        String label();

        /** Whether this process really is a dedicated server; everything else proves nothing if not. */
        boolean isDedicatedServer();

        /** Whether the loader's connection lifecycle installed itself. */
        boolean connectionsRegistered();

        /** A package whose every class is client-only. Enumerated, so it cannot go stale. */
        String clientPackage();

        /** Client-only classes that live outside {@link #clientPackage()}. */
        default List<String> alsoNeverLoaded() {
            return Collections.emptyList();
        }

        /** Stops the server cleanly. Called only on a pass — a failure halts instead. */
        void halt();
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Runs every check, prints the report, writes the verdict, and ends the process. */
    public static void run(Host host) {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        check(lines, failures, "the process is a dedicated server", host.isDedicatedServer(),
                "not a dedicated server -- this check proves nothing anywhere else");

        // The one that was fatal: the platform bundle built all nine services eagerly and died at the
        // first, taking every dependent mod's preInit with it. Reaching this line is most of the
        // assertion.
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

        // An unavailable channel is a warn-and-return inside the lifecycle's register(), so checking only
        // the flag below would report the symptom and hide the cause.
        boolean channel;
        try {
            channel = CgPlatform.get(CgNetworkChannel.SERVICE).isAvailable();
        } catch (Throwable noChannel) {
            channel = false;
        }
        check(lines, failures, "network channel available", channel,
                "the loader's CgNetworkChannel provider did not register, or the SERVICE slot still "
                        + "holds the no-op default");

        // Warn-and-return on failure rather than a throw, so a server with no networking boots happily
        // and looks fine.
        check(lines, failures, "connection lifecycle installed", host.connectionsRegistered(),
                "the loader's connection registration stood down; see the [cgui-net] warning above");

        // A connection binds only contributors registered BEFORE it opens, and no peer exists yet -- so
        // this is the last moment the set can still be wrong and the first at which it certainly is not.
        Set<String> contributors = Protocols.contributors();
        check(lines, failures, "protocol contributors bound " + contributors,
                contributors.contains("workspace"),
                "expected 'workspace'; the workspace host must register before the connection lifecycle");

        checkDescriptionRoundTrip(lines, failures);
        checkNothingClientSideLoaded(host, lines, failures);
        reportGlDivergence(lines);

        String report = render(host, lines, failures);
        print(report);
        ProbeReport.write(REPORT_PROPERTY, failures.isEmpty(), report);
        stop(host, failures.isEmpty());
    }

    // ── the checks ──────────────────────────────────────────────────────────────────────────────

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

    private static void checkNothingClientSideLoaded(Host host, List<String> lines,
                                                     List<String> failures) {
        List<String> subjects = new ArrayList<>(NEVER_LOADED_ANYWHERE);
        subjects.addAll(host.alsoNeverLoaded());
        checkTheNamedOnesStillExist(host, subjects, lines, failures);
        subjects.addAll(auditClientPackage(host, lines));

        Set<String> definedByTheJvm = loadedFromJvmLog();

        List<String> loaded = new ArrayList<>();
        List<String> undetermined = new ArrayList<>();
        for (String name : subjects) {
            Boolean isLoaded = definedByTheJvm != null
                    ? Boolean.valueOf(definedByTheJvm.contains(name))
                    : loadedByAnyLoader(host, name);
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
            lines.add("WARN  could not determine load state for " + undetermined.size() + " class(es)"
                    + " -- findLoadedClass was not reachable: " + loadStateFailure
                    + " (the PASS below therefore covers only the rest)");
        }

        check(lines, failures,
                "no client-only class loaded on the server (" + subjects.size() + " checked"
                        + (undetermined.isEmpty() ? "" : ", of those determinable") + ")",
                loaded.isEmpty(),
                loaded.isEmpty() ? "" : "LOADED: " + loaded
                        + " -- something on a common path reached a client class; in production this is "
                        + "a NoClassDefFoundError at that point, not here");
    }

    /**
     * <b>The hand-written names still name something.</b> A class that has been renamed or deleted can
     * never be loaded, so it passes for ever — which is exactly how the 1.7.10 list rotted, and this is
     * the half {@link Host#clientPackage()}'s enumeration cannot cover.
     *
     * <p>Asked as a <em>resource</em>, never {@code Class.forName}: looking one up by name would load
     * it, creating the condition being detected. @see #loadedByAnyLoader</p>
     */
    private static void checkTheNamedOnesStillExist(Host host, List<String> named, List<String> lines,
                                                    List<String> failures) {
        ClassLoader loader = host.getClass().getClassLoader();
        List<String> missing = new ArrayList<>();
        for (String name : named) {
            String resource = name.replace('.', '/') + ".class";
            boolean present = loader == null
                    ? ClassLoader.getSystemResource(resource) != null
                    : loader.getResource(resource) != null;
            if (!present) missing.add(name);
        }
        check(lines, failures, "every explicitly named client-only class still exists (" + named.size()
                        + " named)", missing.isEmpty(),
                "GONE: " + missing + " -- a name nothing defines can never be loaded, so it has been "
                        + "passing for nothing. Rename it here or drop it.");
    }

    /**
     * Every class in the host's client package, read off the code source without loading anything.
     *
     * <p>A container that cannot be read is a WARN and an empty list, never a silent pass.</p>
     */
    private static List<String> auditClientPackage(Host host, List<String> lines) {
        String pkg = host.clientPackage();
        List<String> found = classesIn(host, pkg);
        if (found == null) {
            lines.add("WARN  could not enumerate " + pkg
                    + " from the code source; only the explicit list was checked");
            return Collections.emptyList();
        }
        lines.add("INFO  " + pkg + " contributes " + found.size()
                + " class(es) to the never-loaded set: " + new TreeSet<>(found));
        return found;
    }

    /**
     * @return the top-level class names in {@code pkg}, or {@code null} if no container could be read.
     *
     * <p>The host's own code source first, which covers a plain directory and a shipped jar — the host
     * lives beside the classes being enumerated, which this class does not. Under FML the location is a
     * {@code union:} URL and cannot be walked, so the build passes directories in
     * {@link #CLASSDIR_PROPERTY} instead; otherwise this degrades to the explicit list on the one loader
     * it matters most on.</p>
     */
    @Nullable
    private static List<String> classesIn(Host host, String pkg) {
        List<String> fromCodeSource = scan(codeSourceRoot(host), pkg);
        if (fromCodeSource != null) return fromCodeSource;

        String hint = System.getProperty(CLASSDIR_PROPERTY, "");
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
    private static Path codeSourceRoot(Host host) {
        try {
            CodeSource source = host.getClass().getProtectionDomain().getCodeSource();
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
                            .filter(ServerSmoke::isTopLevelClassFile)
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

    /**
     * Every class the JVM defined this run, read from its own {@code -Xlog:class+load} file.
     *
     * <p>Authoritative, and needs access to nothing. The reflective route below cannot work under a
     * module system: {@code findLoadedClass} is protected, and a mod runs in a named module that no
     * static {@code --add-opens} can name because it does not exist at JVM start.</p>
     *
     * @return {@code null} when the log was not requested or is unreadable, so the caller can fall back
     */
    @Nullable
    private static Set<String> loadedFromJvmLog() {
        String path = System.getProperty(CLASSLOG_PROPERTY, "");
        if (path.isEmpty()) return null;
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) return null;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(ServerSmoke::classNameIn)
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
     * Whether {@code name} has already been defined by the host's loader or any of its parents.
     *
     * <p>Deliberately not {@code Class.forName(name, false, loader)}: that would <b>load the class</b>,
     * which is the very thing being asserted against — the check would then always pass and would create
     * the condition it exists to detect. {@code null} means the question could not be asked at all.</p>
     */
    @Nullable
    private static Boolean loadedByAnyLoader(Host host, String name) {
        try {
            Method find = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            find.setAccessible(true);
            for (ClassLoader loader = host.getClass().getClassLoader();
                 loader != null; loader = loader.getParent()) {
                if (find.invoke(loader, name) != null) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable notAvailable) {
            loadStateFailure = notAvailable;
            return null;
        }
    }

    /**
     * Whether a GL backend was installed, which on a server it should not have been. <b>A WARN, never a
     * failure</b> — it reports a fact about the environment rather than a defect in the code.
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

    // ── reporting and shutdown ──────────────────────────────────────────────────────────────────

    private static void check(List<String> lines, List<String> failures,
                              String what, boolean ok, String detail) {
        lines.add((ok ? "PASS  " : "FAIL  ") + what
                + (ok || detail.isEmpty() ? "" : System.lineSeparator() + "        " + detail));
        if (!ok) failures.add(what);
    }

    private static String render(Host host, List<String> lines, List<String> failures) {
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append("=========== CrystalGUI ").append(host.label())
                .append(" dedicated-server smoke ===========").append(nl);
        for (String line : lines) out.append(line).append(nl);
        out.append("--------------------------------------------------------------------------------")
                .append(nl);
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

    /**
     * Ends the process, with the exit code carrying the verdict.
     *
     * <p>On success a clean halt, so the world saves and the stopping event runs — itself part of what
     * is being smoke-tested, since that is what closes every connection. On failure {@code Runtime.halt}
     * after flushing, because a clean shutdown exits 0 and the verdict has to reach Gradle;
     * {@code System.exit} would run shutdown hooks that can throw on a half-initialised server and mask
     * the code.</p>
     */
    private static void stop(Host host, boolean passed) {
        if (passed) {
            host.halt();
            return;
        }
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(1);
    }
}
