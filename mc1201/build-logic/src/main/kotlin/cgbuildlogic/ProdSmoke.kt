package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

/**
 * Launches every installed client in turn, unattended, and fails if one did not draw.
 *
 * <p>The single jar's whole claim is that one artifact runs on four loaders, and nothing but a real
 * client can test it: a dev run resolves classes from source-set directories, so it cannot see
 * relocation, remapping, downgrading or a merged manifest. This drives the launcher itself.</p>
 *
 * <pre>
 * ./gradlew prodSmoke                       # every instance in local.properties
 * ./gradlew prodSmoke -PcgTargets=1710      # one of them
 * </pre>
 *
 * <p>Sequential, because there is one GPU and one launcher. Each instance is armed by writing
 * {@code JvmArgs} into its {@code instance.cfg}, launched by uuid, and restored afterwards — Prism
 * rewrites that file from memory while it is running, so it is closed first and the launcher is
 * started fresh each time.</p>
 *
 * <p>Four ways to fail, and a missing capture is one of them: a client that never reached the
 * autotest wrote nothing, and silence must not read as success.</p>
 */
abstract class ProdSmoke : DefaultTask() {

    /** `<key>=<name>` per instance, e.g. `prismLauncher1710Dir=1710`. */
    @get:Input
    abstract val instances: ListProperty<String>

    /** Where captures land. Space-free: Prism splits a `JvmArgs` value on spaces. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** The launcher binary. */
    @get:Input
    abstract val launcher: Property<String>

    /** Seconds to wait for a client to appear, and then for it to finish. */
    @get:Input
    abstract val startTimeoutSeconds: Property<Int>

    @get:Input
    abstract val runTimeoutSeconds: Property<Int>

    /** Only these names, when `-PcgTargets` narrowed the run. Empty means all. */
    @get:Input
    abstract val onlyTargets: ListProperty<String>

    init {
        group = "verification"
        description = "Launches every installed client on the single jar and fails if one did not draw."
        launcher.convention("C:/Users/mazen/AppData/Local/Programs/PrismLauncher/prismlauncher.exe")
        // Short enough that a stuck client is a five-minute failure rather than a ten-minute silence.
        startTimeoutSeconds.convention(180)
        runTimeoutSeconds.convention(300)
        onlyTargets.convention(emptyList())
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val localProperties = project.rootProject.file("local.properties")
        if (!localProperties.isFile) {
            throw GradleException("local.properties names no instances; prodSmoke has nothing to drive")
        }
        val settings = Properties().apply { localProperties.inputStream().use { load(it) } }
        val out = outputDir.get().asFile.also { it.mkdirs() }
        val only = onlyTargets.get()

        val failures = mutableListOf<String>()
        var ran = 0

        for (spec in instances.get()) {
            val key = spec.substringBefore('=')
            val name = spec.substringAfter('=')
            if (only.isNotEmpty() && name !in only) continue

            val dir = settings.getProperty(key)
            if (dir.isNullOrBlank()) {
                logger.lifecycle("[prodSmoke] {} is not set; skipping", key)
                continue
            }
            ran++
            val result = driveOne(File(dir), name, out)
            if (result != null) failures += "$name: $result" else logger.lifecycle("[prodSmoke] {} drew", name)
        }

        if (ran == 0) throw GradleException("prodSmoke matched no instance")
        if (failures.isNotEmpty()) {
            throw GradleException("prodSmoke failed on ${failures.size} of $ran:\n"
                + failures.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("[prodSmoke] {} instances drew the desktop on one jar", ran)
    }

    /** @return null when it drew, else why it did not. */
    private fun driveOne(instanceDir: File, name: String, out: File): String? {
        val cfg = File(instanceDir, "instance.cfg")
        if (!cfg.isFile) return "SETUP: no instance.cfg at $instanceDir"
        val uuid = cfg.readLines().firstOrNull { it.startsWith("uuid=") }?.removePrefix("uuid=")
            ?: return "SETUP: instance.cfg names no uuid"

        val capture = File(out, "$name-early.png")
        val late = File(out, "$name-late.png")
        capture.delete()
        late.delete()

        val original = cfg.readText()
        try {
            arm(cfg, out, name)
            killLauncher()
            // LET IT GO. Prism is single-instance, and a `--launch` issued while the previous process
            // is still shutting down is handed to the dying one and dropped -- no game, no error, and
            // the wait below then spends its whole budget on a client that was never asked for.
            Thread.sleep(3000)

            logger.lifecycle("[prodSmoke] {}: launching {}", name, uuid)
            // THROUGH THE SHELL, not ProcessBuilder directly. Prism is a GUI-subsystem binary that
            // detaches immediately; started as a child of the Gradle daemon it exits at once and no
            // game appears, with nothing written anywhere. `Start-Process` is what the hand-run script
            // used and what works.
            powershell("Start-Process -FilePath '${launcher.get()}' -ArgumentList '--launch','$uuid'")

            val game = awaitGame(instanceDir.name, startTimeoutSeconds.get())
                ?: return "SETUP: no client process after ${startTimeoutSeconds.get()}s. Prism was " +
                    "asked to launch $uuid and no JVM appeared; check the launcher's own console."
            logger.lifecycle("[prodSmoke] {}: client pid {}, waiting up to {}s for it to finish",
                    name, game, runTimeoutSeconds.get())
            if (!awaitExit(game, runTimeoutSeconds.get())) {
                killPid(game)
                return "TIMED-OUT after ${runTimeoutSeconds.get()}s; the autotest never quit"
            }
        } finally {
            cfg.writeText(original)
            killLauncher()
        }

        // A MISSING CAPTURE IS A FAILURE, never a pass: the client that never reached the autotest
        // wrote nothing, and that is indistinguishable from success to anything that only checks an
        // exit code.
        if (!capture.isFile) return "NO CAPTURE: the client ran and never reached the autotest"
        if (capture.length() < 4096) return "EMPTY CAPTURE: ${capture.length()} bytes"
        return null
    }

    private fun arm(cfg: File, out: File, name: String) {
        // ONE SET OF ARGUMENTS for every loader, and each reads what it understands: `world=*` loads
        // the first save on 1.7.10, where the editor application needs a server, and is ignored on
        // 1.20.x, whose autotest stays on the title screen because loading a save needs a call that
        // differs between 1.20.1 and 1.20.4.
        //
        // NO SPACE IN ANY VALUE. Prism strips the quotes from a JvmArgs value and splits on spaces
        // anyway, gluing the tail onto the next argument -- and the failure is launcher-side, so no
        // log is written at all and it looks exactly like the game never starting.
        val jvm = "-Dcrystalgui.autotest=true " +
            "-Dcrystalgui.autotest.out=${out.absolutePath.replace('\\', '/')}/$name.png " +
            "-Dcrystalgui.autotest.world=* " +
            "-Dcrystalgui.autotest.lateFrame=120"
        val lines = cfg.readLines().filterNot { it.startsWith("JvmArgs=") || it.startsWith("OverrideJavaArgs=") }
        val armed = buildString {
            lines.forEach { line ->
                appendLine(line)
                if (line.trim() == "[General]") {
                    appendLine("OverrideJavaArgs=true")
                    appendLine("JvmArgs=$jvm")
                }
            }
        }
        cfg.writeText(armed)
    }

    /**
     * The client's pid, or null if none appeared.
     *
     * <p>Through WMI rather than {@code ProcessHandle}: on Windows a Java process cannot read another
     * process's command line, so {@code info().commandLine()} is empty and every client looks alike.
     * The instance directory name is the only thing that tells four clients apart, and it appears
     * only in the command line.</p>
     */
    private fun awaitGame(instanceName: String, seconds: Int): Long? {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        val query = "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' OR Name='java.exe'\" | " +
            "Where-Object { \$_.CommandLine -and \$_.CommandLine -like '*$instanceName*' } | " +
            "Select-Object -First 1 -ExpandProperty ProcessId"
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val pid = powershell(query).trim().toLongOrNull()
            if (pid != null) return pid
        }
        return null
    }

    /** True when it exited on its own. `ProcessHandle.of` is enough here: the pid is already known. */
    private fun awaitExit(pid: Long, seconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5000)
            if (ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) return true
        }
        return false
    }

    private fun killPid(pid: Long) {
        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
    }

    /** Prism holds an instance's settings in memory and rewrites the file over any edit. */
    private fun killLauncher() {
        powershell("Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force")
    }

    private fun powershell(script: String): String {
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
