package com.crystalgui.language.run.view;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.workbench.view.ViewContainerRegistry;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.decoration.FileDecorations;

import javax.annotation.Nullable;

/**
 * Says that something is running, in the two places that can say it.
 *
 * <h3>The mark, and why it is only a colour</h3>
 *
 * <p>IntelliJ puts {@code modified.svg} over the <b>Run tool window's stripe button</b> while anything is
 * executing. The rail already draws exactly that icon for its unread-dot form — {@code
 * icon("crystalgui:general/modified")}, in {@code currentColor}, positioned to overhang the glyph's
 * top-right — so a running badge is a style class and a colour, and no new artwork at all.</p>
 *
 * <h3>Not on editor tabs, and the reason is IntelliJ's model rather than ours</h3>
 *
 * <p>A run there belongs to a <b>run configuration</b>, not to a file, so there is nothing for a tab to
 * be highlighted <em>as</em> — which is why IntelliJ has no active-tab mark to copy. Ours is closer to a
 * file than theirs, but the fact is already stated twice: the stripe dot answers <i>is anything
 * running</i> and the tree row answers <i>which file</i>. A tab would be a third statement in the place
 * with the least room, and the place a dirty dot already occupies.</p>
 *
 * <h3>Both writes cross a thread, and neither may cross it directly</h3>
 *
 * <p>{@link RunSessions#onDidChange} fires from wherever the transition happened — a one-shot's own
 * thread, or the game thread inside a tick handler. Both things updated here are {@code UIElement} state:
 * the badge attaches an internal child, and invalidating decorations repaints tree rows. So the signal
 * only <b>schedules</b>, and the work runs in {@link JobScheduler#drain()}, which {@code UIDocument} calls
 * once a frame on the UI thread.</p>
 *
 * <p><b>Keyed</b>, so a script that transitions twice in a frame — and a burst of them at startup —
 * coalesces into one update rather than one per transition.</p>
 *
 * <p>The shared scheduler rather than a ticker of this class's own, because the two obvious tickers are
 * both wrong: {@code RunPanel}'s stops when the panel is closed, which is exactly when the dot is the
 * only thing left saying anything is running.</p>
 */
public final class RunIndicators {

    /** On the stripe button's badge while anything is live. @see #install */
    public static final String RUNNING_BADGE_CLASS = "__running__";

    private final RunSessions sessions;
    private final ViewContainerRegistry containers;
    @Nullable private final FileDecorations decorations;
    private final JobKey key = JobKey.of(RunIndicators.class, "run-indicators");

    @Nullable private Connection watch;

    private RunIndicators(RunSessions sessions, ViewContainerRegistry containers,
                          @Nullable FileDecorations decorations) {
        this.sessions = sessions;
        this.containers = containers;
        this.decorations = decorations;
    }

    /**
     * Starts marking, and paints the current state once so a panel installed mid-session is not blank.
     *
     * @param decorations the tree's decorations, invalidated on every transition. <b>Required for the
     *                    tree mark to appear at all</b> — {@code RunDecorations} resolves correctly when
     *                    asked, and before this nothing ever asked again after it was registered, so a
     *                    script starting coloured no row until something else happened to rebind the tree
     */
    public static RunIndicators install(Workbench workbench, RunSessions sessions,
                                        @Nullable FileDecorations decorations) {
        RunIndicators indicators = new RunIndicators(
                sessions, workbench.toolWindowManager().viewContainers(), decorations);
        indicators.watch = sessions.onDidChange.connect(script -> indicators.schedule());
        indicators.apply();
        return indicators;
    }

    /** Stops marking and clears the badge. */
    public void dispose() {
        if (watch != null) {
            watch.disconnect();
            watch = null;
        }
        containers.setBadge(RunPanels.RUN_TYPE, null);
    }

    private void schedule() {
        JobScheduler.shared()
                // NOTHING OFF-THREAD TO DO. The work function exists because that is the shape of a job;
                // the answer is read here rather than in onDone only so that a transition which has since
                // been superseded still reports the state at the moment it was asked, which is what the
                // keyed replacement is entitled to assume.
                .<Boolean>job(key, JobLane.LATENCY, context -> !sessions.active().isEmpty())
                .onDone(this::paint)
                .submit();
    }

    private void apply() {
        paint(!sessions.active().isEmpty());
    }

    private void paint(boolean anyActive) {
        containers.setBadge(RunPanels.RUN_TYPE,
                anyActive ? ViewContainerRegistry.DOT : null, RUNNING_BADGE_CLASS);
        // THE TREE HAS TO BE TOLD. A FileDecorationProvider is pulled during bind, so a provider whose own
        // state moved and never said so is a provider that is right and invisible.
        if (decorations != null) decorations.invalidate();
    }
}
