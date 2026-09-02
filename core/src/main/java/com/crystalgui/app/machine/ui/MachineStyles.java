package com.crystalgui.app.machine.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.crystalgui.net.SheetRef;
import com.crystalgui.serialization.ContentHash;
import com.crystalgui.serialization.PlainOps;

/**
 * <b>Step 3 — where the sizes and colours went.</b>
 *
 * <p>{@link MachinePanel} wrote structure and names. The other half — geometry and look — is
 * {@code machine.css}, sitting in this same directory, and this class is the twenty lines that read
 * it and turn it into something a session can announce.</p>
 *
 * <h3>Why the {@code .css} is beside the {@code .java}</h3>
 *
 * <p>So the panel is one directory: model, tree, theme, both session halves. The engine's own
 * stylesheets live under {@code src/main/resources/assets/crystalgui/ui/styles/} because they are
 * shipped assets a resource pack is expected to override; an example's theme is not that, and
 * splitting it across two source roots would mean opening two trees to read one panel.</p>
 *
 * <p>Gradle does not copy non-Java files out of {@code src/main/java} by default, so
 * {@code core/build.gradle.kts} adds that directory as a second resource root with {@code **}{@code
 * /*.java} excluded. One line, and the comment beside it says why. Without it this class compiles,
 * ships, and throws at runtime looking for a file that was never packaged — which is the failure
 * worth knowing about if you copy this layout.</p>
 *
 * <h3>Why {@code getResourceAsStream} and not {@code CgIO}</h3>
 *
 * <p>{@code CgIO} is CrystalGraphics' loader and resolves through Minecraft's resource manager,
 * which is exactly right for a shipped asset on a client and unavailable on a dedicated server.
 * {@link Class#getResourceAsStream} is plain JDK and reads from the classpath, which a server has.
 * The rule this is an instance of: <b>anything a server can reach must not touch {@code CgIO},
 * fonts or GL.</b></p>
 *
 * <p>The sharpest version of that rule is one directory away. {@code StyleSheet} is
 * <b>unloadable on a server</b> — its {@code DEFAULT} field is a {@code static final} that reads
 * {@code default.css} through {@code CgIO} at class-initialisation time, so merely touching the
 * class raises {@code NoClassDefFoundError} in a process with no resource manager, even for
 * {@code StyleSheet.parse}, which needs none of it. That is why the text below stays a
 * {@link String} on this side: the server's whole dealing with a theme is naming one, and only the
 * client ever parses it. {@code core/src/headlessTest/} runs with CrystalGraphics deliberately off
 * the classpath so this class of mistake fails there rather than in production.</p>
 *
 * <h3>How a theme actually travels</h3>
 *
 * <p>Not as text, in the normal case. A {@link SheetRef} pairs a hash with an optional resource id,
 * and that one shape covers four situations:</p>
 *
 * <table>
 *   <tr><th>Situation</th><th>What happens</th></tr>
 *   <tr><td>Shipped theme, client has it</td>
 *       <td>The client resolves the id through its own resource manager. Nothing transfers, and a
 *           resource pack still overrides it — which is why the id is kept at all.</td></tr>
 *   <tr><td>Client's copy is a different version</td>
 *       <td>The hashes disagree, so the client fetches the server's copy rather than silently
 *           drawing a different theme.</td></tr>
 *   <tr><td>Server-only theme, e.g. from a datapack</td>
 *       <td>Same fetch path. A bare id could not serve this at all — a datapack never reaches a
 *           client's resource manager.</td></tr>
 *   <tr><td>Generated at runtime ({@code id == null})</td>
 *       <td>Straight to fetch, with the hash as the only identity, so two identical generated sheets
 *           transfer once between them.</td></tr>
 * </table>
 *
 * <p>{@link #SHEET} takes the last of those. A sheet loaded off this jar's classpath is not
 * something an arbitrary client can look up by name, so it is honest to say so rather than claim an
 * id and have the lookup miss. A real mod that ships
 * {@code assets/mymod/ui/styles/machine.css} uses {@link SheetRef#ofResource} instead — strictly
 * better, because it usually transfers nothing.</p>
 */
public final class MachineStyles {

    /** Matched by the class selectors in {@code machine.css}. Constants, so a typo is a compile error. */
    public static final String PANEL_CLASS = "machine-panel";
    public static final String ROW_CLASS = "machine-row";
    public static final String LABEL_CLASS = "machine-label";
    public static final String TITLE_CLASS = "machine-title";
    public static final String STATUS_CLASS = "machine-status";
    public static final String WIRE_CLASS = "machine-wire";

    /** The protocol demo's own vocabulary. See {@code MachinePanel.demoEntry}. */
    public static final String HINT_CLASS = "machine-hint";
    public static final String DEMO_CLASS = "machine-demo";
    public static final String KIND_CLASS = "machine-kind";
    public static final String KIND_REQUEST_CLASS = "machine-kind-request";
    public static final String KIND_NOTIFY_CLASS = "machine-kind-notify";
    public static final String KIND_REFUSED_CLASS = "machine-kind-refused";
    public static final String DIRECTION_CLASS = "machine-direction";
    public static final String METHOD_CLASS = "machine-method";
    public static final String OUTCOME_CLASS = "machine-outcome";
    public static final String WHO_SERVER_CLASS = "machine-who-server";
    public static final String WHO_CLIENT_CLASS = "machine-who-client";

    /** The nested {@link EnginePanel}'s own box. Hidden by the sheet until the section is opened. */
    public static final String ENGINE_CLASS = "machine-engine";

    /**
     * Added and removed by {@code MachinePanel.toggleEngine}, <b>on the client only</b>.
     *
     * <p>A class rather than a pseudo-class because the engine re-evaluates a pseudo-class on its own
     * terms and a class on yours — the standing rule for state a widget flips from its own listener,
     * and one this repository has paid for three times.</p>
     */
    public static final String ENGINE_OPEN_CLASS = "machine-engine-open";

    /** Resolved against this class's own package, which is what keeps the name unqualified. */
    private static final String FILE = "machine.css";

    /**
     * The sheet's text.
     *
     * <p>Read once at class-init. That is safe <em>here</em>, unlike the {@code StyleSheet.DEFAULT}
     * case above, for exactly one reason: this reads from the classpath rather than through a
     * resource manager, so there is no environment in which the class loads and the read cannot
     * happen.</p>
     */
    public static final String CSS = read();

    /**
     * What the server announces.
     *
     * <p>The hash is of the sheet's own bytes, so it changes exactly when the text does. Any stable
     * digest would do; this reuses {@link ContentHash} because it is already on the server path and
     * already hashes a {@code PlainOps} value, which a bare string is.</p>
     */
    public static final SheetRef SHEET = SheetRef.anonymous(ContentHash.of(PlainOps.INSTANCE, CSS));

    private MachineStyles() {
    }

    private static String read() {
        try (InputStream in = MachineStyles.class.getResourceAsStream(FILE)) {
            if (in == null) {
                // Naming the likely cause, because the obvious reading -- "the file is missing" --
                // is wrong: it is in the source tree, it just was not packaged. See the class javadoc.
                throw new IllegalStateException(FILE + " is not on the classpath. It lives beside "
                        + "the source, so core/build.gradle.kts must keep src/main/java as a "
                        + "resource root for it to be packaged.");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = in.read(chunk)) != -1; ) buffer.write(chunk, 0, read);
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not read " + FILE, failed);
        }
    }
}
