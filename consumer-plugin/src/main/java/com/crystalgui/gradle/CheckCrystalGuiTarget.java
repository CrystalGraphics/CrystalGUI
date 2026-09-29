package com.crystalgui.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails, naming what each mod does support, when a shipped jar has no variant for the declared target.
 *
 * <p>Read from the jars themselves: each carries {@code META-INF/<modid>/variants.json}, the table its
 * own bootstrapper picks from, so the check cannot disagree with what the game would do.</p>
 */
public abstract class CheckCrystalGuiTarget extends DefaultTask {

    private static final Pattern VARIANT =
        Pattern.compile("\"loader\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"minecraft\"\\s*:\\s*\"([^\"]+)\"");

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getJars();

    @Input
    public abstract Property<String> getTarget();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    void check() throws IOException {
        Target target = Target.parse(getTarget().get());
        StringBuilder report = new StringBuilder();
        for (File jar : getJars()) {
            List<String[]> variants = variants(jar);
            if (variants.isEmpty()) {
                continue;
            }
            boolean served = variants.stream().anyMatch(v -> target.servedBy(v[0], v[1]));
            if (!served) {
                StringBuilder supported = new StringBuilder();
                variants.stream().filter(v -> target.servedBy(v[0], "[0,)"))
                    .forEach(v -> supported.append(' ').append(v[1]));
                throw new GradleException(jar.getName() + " has no variant for " + target + ". It runs "
                    + target.loader() + " on:" + (supported.length() == 0 ? " nothing" : supported));
            }
            report.append(jar.getName()).append(" serves ").append(target).append('\n');
        }
        Files.write(getReport().get().getAsFile().toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Every (loader, range) row of the jar's variant table; empty when it carries none. */
    private static List<String[]> variants(File jar) {
        List<String[]> rows = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.stream().filter(e -> e.getName().matches("META-INF/[^/]+/variants\\.json"))
                .findFirst().orElse(null);
            if (entry == null) {
                return rows;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Matcher m = VARIANT.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                while (m.find()) {
                    rows.add(new String[] {m.group(1), m.group(2)});
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
