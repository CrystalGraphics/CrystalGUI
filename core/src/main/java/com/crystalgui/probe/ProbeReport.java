package com.crystalgui.probe;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * <b>How a probe hands its verdict to the build.</b> One line, then the report.
 *
 * <pre>{@code
 * public static final String REPORT_PROPERTY = "crystalgui.myprobe.report";
 * // ...at the end of the run:
 * ProbeReport.write(REPORT_PROPERTY, passed, renderedChecklist);
 * }</pre>
 *
 * <p><b>A file rather than a log scrape:</b> a run's stdout is at the mercy of whatever log4j
 * configuration is in force. The verdict is the <em>first line</em>, so the build reads one line rather
 * than parsing a report — and a truncated write is a failure rather than a plausible pass.</p>
 *
 * <p><b>The task deletes the file before the run and requires it after</b>, so an absent file is a
 * failure with a message rather than a silent pass. That rule is the reason this exists at all: a probe
 * that dies before it starts — a world that never loads, a mod that refuses, a port already taken —
 * leaves nothing behind, and {@code serverSmoke}'s first run reported BUILD SUCCESSFUL having asserted
 * nothing.</p>
 *
 * <p>An unset property writes nothing, so a probe driven by hand from a dev run costs nothing.</p>
 */
final class ProbeReport {

    private ProbeReport() {
    }

    /**
     * Writes {@code text} to whatever path {@code property} names, verdict first.
     *
     * @param property the system property naming the file; nothing is written when it is unset
     * @param passed   what goes on line 1, as {@code PASS} or {@code FAIL}
     */
    static void write(String property, boolean passed, String text) {
        String path = System.getProperty(property, "");
        if (path.isEmpty()) return;
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer writer = new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            writer.write((passed ? "PASS" : "FAIL") + System.lineSeparator());
            writer.write(text);
        } catch (IOException cannotWrite) {
            // System.err rather than the logger, for the same reason the file exists at all: this is the
            // path where the reporting mechanism itself has failed. Fatal by omission -- with no file
            // the build refuses, which is the correct reading of "the check could not report".
            System.err.println("[probe] could not write the report to " + path + ": " + cannotWrite);
        }
    }
}
