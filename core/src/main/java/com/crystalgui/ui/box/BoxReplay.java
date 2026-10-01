package com.crystalgui.ui.box;

/**
 * What a box's two own-paint segments were last recorded under -- the draws under its children and the draws over
 * them -- so a frame can tell whether a segment would come out the same (render-graph G6). One per box that has
 * painted, made on its first paint.
 *
 * <pre>{@code
 * if (box.replay == null) box.replay = new BoxReplay();
 * boolean same = box.replay.sameAs(BoxReplay.BEFORE, key, box.contentRevision, atlasEpoch);
 * }</pre>
 */
final class BoxReplay {

    /** The segment under the box's children, and the one over them. */
    static final int BEFORE = 0, AFTER = 1;
    /**
     * The key's floats: the pose in the box's spatial node (six affine components), the part of the box's own ink
     * the clip leaves (four), and the layer opacity folded into its colours (one).
     */
    static final int KEY_FLOATS = 11;

    private final float[] keys = new float[2 * KEY_FLOATS];
    private final long[] revisions = new long[2];
    private final long[] epochs = new long[2];
    private final boolean[] recorded = new boolean[2];

    /** Whether {@code segment} is recorded under this same key; the key is stored either way. */
    boolean sameAs(int segment, float[] key, long revision, long epoch) {
        int o = segment * KEY_FLOATS;
        boolean same = recorded[segment] && revisions[segment] == revision && epochs[segment] == epoch;
        for (int i = 0; same && i < KEY_FLOATS; i++) same = keys[o + i] == key[i];
        System.arraycopy(key, 0, keys, o, KEY_FLOATS);
        revisions[segment] = revision;
        epochs[segment] = epoch;
        recorded[segment] = true;
        return same;
    }
}
