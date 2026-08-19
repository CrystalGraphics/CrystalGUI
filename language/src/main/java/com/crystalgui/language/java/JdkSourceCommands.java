package com.crystalgui.language.java;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.language.java.assist.JdkSourceExtract;
import com.crystalgui.language.platform.ScriptServices;
import com.crystalgraphics.platform.CgPlatform;

import java.nio.file.Path;

/**
 * <b>Download JDK Sources</b> — M13 §25.5's one affordance.
 *
 * <h3>A command, and never a startup step</h3>
 *
 * <p>The fetch is tens of megabytes from a third party over the user's own connection, and what it
 * produces is a GPL-derived extract made on their machine for their use. Every one of those is a reason
 * it has to be asked for rather than done quietly on a first launch — which is also what IntelliJ does,
 * and what {@code plan_m11.md} §24.1 already named as the popup's <i>Download documentation</i> entry.
 * The engine bands and the mapping data are fetched automatically because without them the feature does
 * not work at all; this one only makes an existing feature better, so it waits to be asked.</p>
 *
 * <h3>Why there is no notification when it lands</h3>
 *
 * <p>The progress bar is the report: it names the download while it runs, and the command dims in the
 * palette the moment the extract is on disk, which is the same enabled/disabled signal every other
 * one-shot command in this application uses. A balloon saying "done" for something the status bar just
 * spent a minute describing is the second statement of one fact — and the failure case is not silent
 * either, because {@link JdkSourceExtract.Result} says which of "already had it", "fetched it", "nothing
 * configured" and "could not reach it" happened, and those four look identical from the outside.</p>
 */
public final class JdkSourceCommands {

    public static final String DOWNLOAD = "java.downloadJdkSources";

    private JdkSourceCommands() {
    }

    /**
     * Registers the command, and adopts an extract a previous session already fetched.
     *
     * <p>The adoption is the half that is easy to leave out and impossible to notice missing: without it
     * the download works, the file is on disk, and every launch after the first shows the assembled form
     * again — with the command still offering to fetch what is already there.</p>
     */
    public static void register(CommandRegistry registry) {
        Path cacheRoot = cacheRoot();
        JdkSourceExtract.useCachedIfPresent(cacheRoot);

        registry.register(Command.of(DOWNLOAD, "Download JDK Sources")
                // NO ACCELERATOR. A binding is for something done repeatedly; this is done once per
                // installation, and a key that fires a large download by accident is a poor trade.
                .enabledWhen(context -> !JdkSourceExtract.isCached(cacheRoot()))
                .run(() -> JobScheduler.shared()
                        .job(JobKey.of(JdkSourceCommands.class, "jdk-sources"), JobLane.BACKGROUND,
                                context -> {
                                    // THE JOB'S OWN PROGRESS, so this reports into the status bar like
                                    // the mapping fetch rather than being a silent stall -- and so it can
                                    // be cancelled from the Processes popup, which for a download of this
                                    // size is the affordance that matters most.
                                    JdkSourceExtract.Result result =
                                            JdkSourceExtract.acquire(cacheRoot(), context.progress());
                                    // SAID ONCE, WHICHEVER IT IS -- the same rule PlatformMappings
                                    // follows. "Already cached" and "could not reach it" both end with
                                    // the popup unchanged, and are entirely different things to somebody
                                    // wondering why nothing happened.
                                    System.err.println("[crystalgui] jdk sources: " + result);
                                    return null;
                                })
                        .submit()));
    }

    /**
     * Where the extract goes.
     *
     * <p>Read from the platform each time rather than captured, because a slot is filled by whichever
     * loader registered it and this class must not care which — {@code ScriptService.NONE} answers a
     * working-directory path, which is what the harness and every test get.</p>
     */
    private static Path cacheRoot() {
        return CgPlatform.get(ScriptServices.SERVICE).cacheRoot();
    }
}
