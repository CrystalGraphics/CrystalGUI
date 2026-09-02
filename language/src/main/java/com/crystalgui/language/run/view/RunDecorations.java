package com.crystalgui.language.run.view;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.language.run.RunSessions;
import com.crystalgui.language.run.RunState;
import com.crystalgui.workbench.decoration.FileDecoration;
import com.crystalgui.workbench.decoration.FileDecorationProvider;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which files have a live script — the running indicator, as an ordinary decoration provider.
 *
 * <h3>Not new machinery, and that is the point</h3>
 *
 * <p>"Which of my scripts are running" looks like it wants a bespoke overlay and does not: the
 * decoration layer already merges independent contributors per field and bubbles to folders, and
 * {@code DiagnosticDecorations} is the working precedent. So this is one small class registering into
 * something built for exactly this, rather than a second marking mechanism the tree has to consult.</p>
 *
 * <h3>It states a COLOUR and never a letter</h3>
 *
 * <p>The merge is per-field, heaviest stater of each field winning — which is what makes composing with
 * the dirty mark possible instead of a contest. A file that is <b>edited and running at the same time</b>
 * is the case that matters most here: it is the one where the file's text is no longer what is running.
 * Claiming the letter would replace the {@code M} and hide the unsaved edit; claiming only the colour
 * shows {@code M} <em>in</em> the running colour, so both facts survive.</p>
 *
 * <p>Weighted between modified and warning on the same reasoning. It should out-state the dirty colour,
 * whose information the {@code M} already carries, and lose to an error or a warning, which are more
 * urgent than the fact that something is running.</p>
 */
public final class RunDecorations implements FileDecorationProvider {

    /** Above {@code WEIGHT_MODIFIED}, below {@code WEIGHT_WARNING}. @see FileDecoration */
    public static final int WEIGHT_RUNNING = 15;

    /** The palette entry in {@code decorations.css}. */
    public static final String RUNNING_CLASS = "decoration-running";

    private final RunSessions sessions;

    public RunDecorations(RunSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public String label() {
        return "Running scripts";
    }

    /**
     * A decoration only while the script can still do something.
     *
     * <p>{@code FINISHED}, {@code STOPPED} and {@code FAILED} are all deliberately unmarked. A rule
     * written as "has been run" would leave every script anybody tried marked for the rest of the
     * session, which is the indicator saying nothing rather than saying something quiet.</p>
     */
    @Override
    @Nullable
    public FileDecoration decorationFor(CgPath path) {
        if (path == null) return null;
        RunSessions.Session session = sessions.sessionOf(Resource.of(path));
        if (session == null || !session.isActive()) return null;
        return FileDecoration.of(WEIGHT_RUNNING, RUNNING_CLASS, null, tooltipFor(session))
                // BUBBLES, so a folder shows that something under it is live -- and carries only a
                // colour, which is what the bubbling rule wants anyway: a folder is not itself running,
                // so it must never inherit a badge that claims it is.
                .withBubble(true);
    }

    private static String tooltipFor(RunSessions.Session session) {
        if (session.state() != RunState.LIVE) return "Running";
        // THE HANDLER COUNT IS THE WHOLE POINT of the live state -- "waiting" says nothing about
        // whether anything will ever fire again, and the number is the only thing that does.
        int handlers = session.handlers();
        return handlers == 1 ? "Live — 1 handler" : "Live — " + handlers + " handlers";
    }

    /**
     * Only the active ones, which is what makes the per-folder cost negligible.
     *
     * <p>This is read per folder row, so answering "every script this workspace has ever run" would grow
     * with the session and be walked on every one of them.</p>
     */
    @Override
    public Collection<CgPath> decorated() {
        List<Resource> active = sessions.active();
        if (active.isEmpty()) return List.of();
        List<CgPath> paths = new ArrayList<>(active.size());
        for (Resource resource : active) {
            CgPath path = pathOf(resource);
            if (path != null) paths.add(path);
        }
        return paths;
    }

    /**
     * The path behind a resource, however it was built.
     *
     * <p><b>{@code Resource.isProject()} is not "the scheme is project" — it is "a {@code CgPath} was
     * used to make it".</b> A resource made with {@code Resource.of("project", "src/Foo.java")} carries a
     * null path, so the obvious spelling of this loop drops it silently — and only here, because
     * {@code Resource.equals} compares scheme and path, so the same resource looks up perfectly in
     * {@link RunSessions}. The result would be a decoration that appears on the file and never bubbles
     * to its folder, which reads as folder bubbling being broken rather than as a resource being built
     * the other way.</p>
     */
    @Nullable
    private static CgPath pathOf(Resource resource) {
        if (resource.isProject()) return resource.asPath();
        if (!Resource.SCHEME_PROJECT.equals(resource.scheme())) return null;
        try {
            return CgPath.parse(resource.path());
        } catch (RuntimeException notAPath) {
            return null;
        }
    }
}
