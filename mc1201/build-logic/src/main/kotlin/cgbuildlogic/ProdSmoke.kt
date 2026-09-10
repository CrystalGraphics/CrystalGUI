package cgbuildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Prism writes its configs as UTF-8 and some carry a byte-order mark. */
private val BOM = 0xFEFF.toChar()

/** The flag whose presence in a config IS the proof that arming took. */
private const val ARMED_MARKER = "JvmArgs=-Dcrystalgui.autotest=true"

/** How long the launcher gets to read every instance.cfg before the first launch is issued. */
private const val LAUNCHER_READ_MS = 5000L

/** Gap between launch requests, so a forwarded one is not dropped. */
private const val LAUNCHER_SETTLE_MS = 1000L

/** Ceiling on any one PowerShell call, so a stuck one cannot stall the run or its cleanup. */
private const val POWERSHELL_TIMEOUT_SECONDS = 60L

/**
 * Launches every installed client at once, unattended, and fails if one did not draw.
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
 * <p>EVERY INSTANCE IS ARMED FIRST, with the launcher closed. Prism serves a {@code --launch} from the
 * settings it holds in MEMORY, so a config edit made while it is up is simply not seen and the client
 * opens un-armed -- it draws, nothing photographs it, and it never quits. Arming everything up front
 * turns that in-memory copy from the obstacle into the mechanism: the launcher re-reads an armed config
 * however many times it is restarted underneath.</p>
 *
 * <p>Then ALL AT ONCE, a second apart, into the one launcher: every client is up within seconds and the
 * whole run is one client's worth of wall clock. Driving them one at a time costs about 2.5 minutes per
 * instance, because a {@code --launch} FORWARDED to a launcher that has just had a client exit sits that
 * long before it is acted on -- and restarting the launcher between instances to dodge that is slower
 * still. Sharing the GPU four ways does not cost a target its capture, 1.7.10 and its world included.</p>
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

    /**
     * The launcher binary. Read from `prismLauncherExe` in `local.properties` unless set here.
     *
     * <p>Machine-local like the instance directories beside it, and gitignored for the same reason:
     * where somebody installed PrismLauncher is not a fact about this repository.</p>
     */
    @get:Input
    @get:Optional
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
        // A client boots, loads its world, opens the desktop, takes two captures and quits in well
        // under a minute; a world load is about ten seconds. So this is a ceiling on a STUCK client,
        // not a budget for a slow one -- generous enough to survive four clients sharing a GPU, and
        // short enough that a hang is a two-minute failure.
        startTimeoutSeconds.convention(180)
        runTimeoutSeconds.convention(120)
        onlyTargets.convention(emptyList())
        outputs.upToDateWhen { false }
    }

    /** One installed client: where it lives, what Prism calls it, and its untouched config. */
    private data class Target(val name: String, val dir: File, val cfg: File, val uuid: String) {
        val original: String = cfg.readText()
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

        val exe = launcher.orNull?.takeIf { it.isNotBlank() }
            ?: settings.getProperty("prismLauncherExe")?.takeIf { it.isNotBlank() }
            ?: throw GradleException("local.properties names no prismLauncherExe, so prodSmoke cannot "
                + "find the launcher. Add e.g. prismLauncherExe=C:/path/to/prismlauncher.exe")
        if (!File(exe).isFile) throw GradleException("prismLauncherExe is not a file: $exe")

        val targets = mutableListOf<Target>()
        for (spec in instances.get()) {
            val key = spec.substringBefore('=')
            val name = spec.substringAfter('=')
            if (only.isNotEmpty() && name !in only) continue

            val dir = settings.getProperty(key)
            if (dir.isNullOrBlank()) {
                logger.lifecycle("[prodSmoke] {} is not set; skipping", key)
                continue
            }
            val instanceDir = File(dir)
            val cfg = File(instanceDir, "instance.cfg")
            if (!cfg.isFile) throw GradleException("$name: no instance.cfg at $instanceDir")
            val uuid = cfg.readLines().firstOrNull { it.startsWith("uuid=") }?.removePrefix("uuid=")
                ?: throw GradleException("$name: instance.cfg names no uuid")
            targets += Target(name, instanceDir, cfg, uuid)
        }
        if (targets.isEmpty()) throw GradleException("prodSmoke matched no instance")

        val failures = mutableListOf<String>()
        // The launcher must be down while the configs are written, and the sleep is the second half of
        // that: Prism is single-instance, so a `--launch` issued while the previous process is still
        // shutting down is handed to the dying one and dropped.
        killLauncher()
        Thread.sleep(3000)
        try {
            targets.forEach { arm(it.cfg, out, it.name) }
            logger.lifecycle("[prodSmoke] armed {}", targets.joinToString(", ") { it.name })
            startLauncher(exe)
            // Prism rewrites an instance.cfg from its own model, so the arming has to survive the
            // launcher READING it as well as being written. Asked here rather than inferred from a
            // missing capture, which cannot tell a config the launcher discarded from a client that
            // crashed.
            val lost = targets.filterNot { isArmed(it.cfg) }
            if (lost.isNotEmpty()) {
                throw GradleException("the launcher discarded the arming for "
                    + lost.joinToString(", ") { it.name } + "; their configs no longer carry $ARMED_MARKER")
            }

            // ALL AT ONCE, a second apart -- every client is up within seconds. Driving them
            // one at a time costs some 2.5 minutes per instance, because a `--launch` FORWARDED to a
            // launcher that has just had a client exit sits that long before it is acted on, and
            // restarting the launcher between instances to dodge that is slower still. The instances
            // are independent (separate game directories, separate capture paths) and a capture reads
            // the RENDER TARGET rather than the screen, so an overlapped window still photographs.
            targets.forEach { target ->
                captureOf(out, target, "early").delete()
                captureOf(out, target, "late").delete()
                logger.lifecycle("[prodSmoke] {}: launching {}", target.name, target.uuid)
                powershell("Start-Process -FilePath '$exe' -ArgumentList '--launch','${target.uuid}'")
                Thread.sleep(LAUNCHER_SETTLE_MS)
            }

            val up = awaitClients(targets.size, startTimeoutSeconds.get())
            logger.lifecycle("[prodSmoke] {} of {} clients up", up, targets.size)
            val allExited = awaitNoClients(runTimeoutSeconds.get())
            if (!allExited) {
                logger.lifecycle("[prodSmoke] killing clients still alive after {}s", runTimeoutSeconds.get())
                killClients()
            }

            targets.forEach { target ->
                val verdict = verdictFor(target, allExited, out)
                if (verdict != null) failures += "${target.name}: $verdict"
                else logger.lifecycle("[prodSmoke] {} drew", target.name)
            }
        } finally {
            // Kill THEN restore: Prism rewrites instance.cfg from memory as it exits, so a restore
            // written first is erased by the launcher's own shutdown.
            killLauncher()
            Thread.sleep(1000)
            targets.forEach { disarm(it.cfg, it.original) }
        }

        if (failures.isNotEmpty()) {
            throw GradleException("prodSmoke failed on ${failures.size} of ${targets.size}:\n"
                + failures.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("[prodSmoke] {} instances drew the desktop on one jar", targets.size)
    }

    private fun captureOf(out: File, target: Target, which: String) = File(out, "${target.name}-$which.png")

    /** @return null when it drew, else why it did not. */
    private fun verdictFor(target: Target, allExited: Boolean, out: File): String? {
        if (!allExited && !captureOf(out, target, "late").isFile) {
            return "TIMED-OUT after ${runTimeoutSeconds.get()}s; the autotest never quit" + logTail(target)
        }
        // A MISSING CAPTURE IS A FAILURE, never a pass: the client that never reached the autotest
        // wrote nothing, and that is indistinguishable from success to anything that only checks an
        // exit code.
        val capture = captureOf(out, target, "early")
        if (!capture.isFile) return "NO CAPTURE: the client ran and never reached the autotest" + logTail(target)
        if (capture.length() < 4096) return "EMPTY CAPTURE: ${capture.length()} bytes" + logTail(target)
        return null
    }

    /**
     * What the client itself said, appended to a failure.
     *
     * <p>Without it every failure reads the same -- "no capture" -- whether the jar never loaded, the
     * autotest was never armed, or the desktop threw on its first frame.</p>
     */
    private fun logTail(target: Target): String {
        val logs = File(target.dir, ".minecraft/logs")
        val log = listOf("latest.log", "fml-client-latest.log")
            .map { File(logs, it) }.firstOrNull { it.isFile } ?: return "\n      (no log found in $logs)"
        val lines = runCatching { log.readLines() }.getOrElse { return "\n      (${log.name} unreadable)" }
        val autotest = lines.filter { it.contains("AUTOTEST") }.takeLast(3)
        val tail = if (autotest.isNotEmpty()) autotest else lines.takeLast(5)
        val what = if (autotest.isNotEmpty()) "autotest" else "tail"
        return "\n      ${log.name} ($what):\n" + tail.joinToString("\n") { "        $it" }
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
        cfg.writeText(withGeneralKeys(cfg.readText(), listOf("OverrideJavaArgs=true", "JvmArgs=$jvm")))
        // READ IT BACK. The log line announcing "armed" used to be unconditional, so a write that
        // inserted nothing read as success and the failure surfaced twenty minutes later as a missing
        // capture -- pointing at the game rather than at this method.
        if (!isArmed(cfg)) throw GradleException("$name: arming did not take; $cfg has no $ARMED_MARKER")
    }

    private fun isArmed(cfg: File) = cfg.readLines().any { it.startsWith(ARMED_MARKER) }

    /**
     * Puts the two keys back as they were, and nothing else.
     *
     * <p>Not a blanket rewrite of the original text: Prism has by then updated `lastLaunchTime` and
     * `totalTimePlayed` in the same file, and restoring wholesale would roll back the user's own
     * bookkeeping to whatever it was before the smoke ran.</p>
     */
    private fun disarm(cfg: File, original: String) {
        val restored = original.lines().filter {
            it.startsWith("OverrideJavaArgs=") || it.startsWith("JvmArgs=")
        }
        cfg.writeText(withGeneralKeys(cfg.readText(), restored))
    }

    /** `text` with our two keys dropped and `keys` reinserted under `[General]`. */
    private fun withGeneralKeys(text: String, keys: List<String>): String {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val kept = text.split(Regex("\r\n|\n")).filterNot {
            it.startsWith("JvmArgs=") || it.startsWith("OverrideJavaArgs=")
        }
        var inserted = false
        val armed = buildString {
            kept.forEach { line ->
                append(line).append(eol)
                // A BOM is part of the FIRST LINE'S TEXT, so an equality test against "[General]"
                // fails on a file that carries one -- and fails silently, writing the config back
                // unchanged, so the client launches un-armed and the failure surfaces as a missing
                // capture twenty minutes later.
                if (line.trimStart(BOM).trim() == "[General]") {
                    keys.forEach { append(it).append(eol) }
                    inserted = true
                }
            }
        }
        if (!inserted) throw GradleException("instance.cfg has no [General] section to write under")
        return armed
    }

    /**
     * How many Minecraft clients an instance of this launcher currently has running.
     *
     * <p>A COUNT, not a per-client identity. Telling four clients apart means matching the instance
     * directory name inside a command line -- which on Windows only WMI can read at all, and which
     * comes back through PowerShell wrapped at 80 columns, so a 9,000-character command line arrives
     * in pieces that match nothing. Two attempts at that reported "no client process" for four clients
     * that were plainly running. Nothing here needs the identity: which target drew is answered by its
     * capture file, and this only has to say whether any client is up yet and when they have all gone.</p>
     */
    private fun clientCount(): Int =
        powershell("@(Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and " +
            "\$_.CommandLine -like '*PrismLauncher*instances*' }).Count")
            .trim().toIntOrNull() ?: 0

    /** @return how many came up, once they all have or the deadline passes. */
    private fun awaitClients(expected: Int, seconds: Int): Int {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var seen = 0
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000)
            seen = maxOf(seen, clientCount())
            if (seen >= expected) return seen
        }
        return seen
    }

    /** @return true when every client exited on its own before the deadline. */
    private fun awaitNoClients(seconds: Int): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            if (clientCount() == 0) return true
        }
        return false
    }

    private fun killClients() {
        powershell("Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'java*' -and " +
            "\$_.CommandLine -like '*PrismLauncher*instances*' } | " +
            "ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }")
    }

    /**
     * Brings the launcher up once, with every config already armed on disk.
     *
     * <p>THROUGH THE SHELL, not ProcessBuilder directly: Prism is a GUI-subsystem binary that detaches
     * immediately, and started as a child of the Gradle daemon it exits at once with no game and
     * nothing written anywhere.</p>
     */
    private fun startLauncher(exe: String) {
        powershell("Start-Process -FilePath '$exe'")
        // It reads every instance.cfg as it comes up, and a `--launch` served before that read is
        // served from settings it has not loaded yet.
        Thread.sleep(LAUNCHER_READ_MS)
        logger.lifecycle("[prodSmoke] launcher up")
    }

    private fun killLauncher() {
        powershell("Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force")
    }

    /**
     * Runs a PowerShell one-liner and returns its output, or what it managed before the timeout.
     *
     * <p>Bounded, and through a FILE rather than the process's pipe: an unbounded {@code waitFor} let
     * one stuck call stall the whole task -- the cleanup could not even close the launcher, so a run
     * that had finished sat for minutes still holding an armed config -- and draining a pipe with
     * {@code readText()} before waiting hangs on exactly the same call.</p>
     */
    private fun powershell(script: String): String {
        val output = File.createTempFile("prodsmoke", ".txt")
        try {
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            if (!process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("[prodSmoke] a powershell call timed out after {}s and was killed",
                        POWERSHELL_TIMEOUT_SECONDS)
            }
            return output.readText()
        } finally {
            output.delete()
        }
    }
}
