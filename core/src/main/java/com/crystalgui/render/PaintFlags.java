package com.crystalgui.render;

/**
 * The paint pass's switches, each read once from a system property: for bisecting a paint bug or measuring what a
 * mechanism saves, never for shipping. All on by default but the check.
 *
 * <pre>{@code
 * -Dcrystalgui.paint.replay=false        # every box painted every frame
 * -Dcrystalgui.paint.replayCheck=true    # kept segments painted anyway and compared; a mismatch is logged once
 * }</pre>
 */
public final class PaintFlags {

    /** {@code crystalgui.paint.segments=false}: no box's paint is taken apart from its neighbours'. */
    public static final boolean SEGMENTS = !"false".equals(System.getProperty("crystalgui.paint.segments"));

    /** {@code crystalgui.paint.replay=false}: every box painted every frame. Needs segments. */
    public static final boolean REPLAY = SEGMENTS && !"false".equals(System.getProperty("crystalgui.paint.replay"));

    /**
     * {@code crystalgui.paint.replayCheck=true}: a segment whose key holds is painted anyway and compared with what it
     * kept, and a box that drew otherwise under an unchanged key is named once. Every box paints.
     */
    public static final boolean REPLAY_CHECK = REPLAY && Boolean.getBoolean("crystalgui.paint.replayCheck");

    /** {@code crystalgui.paint.cull=false}: no subtree is skipped for lying outside the clip (Blink's cull rect). */
    public static final boolean CULL = !"false".equals(System.getProperty("crystalgui.paint.cull"));

    /** {@code crystalgui.paint.nodes=false}: no box gets a node a compositor moves, nor a surface. */
    public static final boolean NODES = !"false".equals(System.getProperty("crystalgui.paint.nodes"));

    /** {@code crystalgui.paint.surfaces=false}: a box the compositor moves draws into the frame. */
    public static final boolean SURFACES = NODES && !"false".equals(System.getProperty("crystalgui.paint.surfaces"));

    /** {@code crystalgui.paint.damage=false}: a surface walked again executes all of itself. */
    public static final boolean DAMAGE = !"false".equals(System.getProperty("crystalgui.paint.damage"));

    private PaintFlags() {
    }
}
