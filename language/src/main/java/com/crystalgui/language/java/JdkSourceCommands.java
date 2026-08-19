package com.crystalgui.language.java;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.Notifications;
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
 * <h3>It reports twice, and the first version reported neither time</h3>
 *
 * <p>A progress bar <b>while</b> it runs, and a notification when it ends. The first draft had only the
 * bar, on the reasoning that a balloon would be the second statement of one fact — and both halves of
 * that failed at once, which is why they are spelled out here rather than left as a change nobody can
 * account for:</p>
 *
 * <ul>
 *   <li>{@code acquire} asked the server for the download's <b>size</b> before calling
 *       {@link com.crystalgui.core.async.Progress#begin}, so the bar did not exist until a request had
 *       completed. Press the command with the host unreachable and nothing happens for fifteen seconds.</li>
 *   <li>A job is only drawn after 400 ms of work, so a fetch that failed <em>fast</em> was never drawn at
 *       all. The bar covers the success it is watching; it cannot cover the failure it never got to.</li>
 * </ul>
 *
 * <p>So: announce before any I/O, and say what happened at the end. A user-initiated command that
 * produces no visible change reads as broken, and "already had it", "fetched it" and "could not reach it"
 * are indistinguishable from outside.</p>
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
                //
                // AND NO `enabledWhen`, which it had and which was worse than useless. Dimming once the
                // extract is on disk is the signal every other one-shot command here uses, and it is
                // unreadable for this one: the row greys out at the moment the download SUCCEEDS, so the
                // only thing distinguishing "it worked" from "the command is broken" is a state the user
                // cannot see. Reported from the harness as exactly that, twice in a row -- first as
                // "nothing happened" (it was downloading, silently) and then as "now it is greyed out"
                // (it had finished). Running it when there is nothing to do now costs one `isValid` and
                // answers "already downloaded", which is information; a dead row is not.
                //
                // It is also the only retry path: a cache that has gone bad had no way back except
                // deleting a file by hand.
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
                                    return result;
                                })
                        // ON THE UI THREAD. `onDone` is documented to run during drain(), and that is not
                        // a nicety here: a notification emits a signal, a listener touches the cascade,
                        // and `invalidateStyleMatch` from a worker thread corrupts StyleEngine's
                        // dirty-match set -- an ArrayIndexOutOfBoundsException thrown in advanceFrame with
                        // nothing about this command anywhere in the trace.
                        .onDone(JdkSourceCommands::report)
                        .submit()));
    }

    /**
     * <b>Say what happened, because a user-initiated command that shows nothing reads as broken.</b>
     *
     * <p>This class argued the opposite a version ago — that the progress bar was the report and a
     * balloon would be the second statement of one fact. That was wrong in exactly the case that matters:
     * a fetch which fails before the bar appears reports <em>nothing at all</em>, and a bar only appears
     * after 400 ms of work, so every fast failure was silent. The bar covers the success it is watching;
     * it cannot cover the failure it never got to.</p>
     *
     * <p>An error rather than an info for the two outcomes that leave the popup unchanged, because
     * "already had it", "fetched it" and "could not reach it" are indistinguishable from the outside and
     * the last of them is the one somebody needs to act on.</p>
     */
    private static void report(JdkSourceExtract.Result result) {
        if (result == null) return;
        switch (result.state()) {
            case INSTALLED:
                Notifications.info("JDK sources downloaded — " + result.detail());
                break;
            case CACHED:
                Notifications.info("JDK sources are already downloaded.");
                break;
            case NOT_CONFIGURED:
                Notifications.warning("No JDK source download is configured — " + result.detail());
                break;
            default:
                Notifications.error("Could not download JDK sources — " + result.detail());
                break;
        }
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
