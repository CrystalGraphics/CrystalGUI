package com.crystalgui.core.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;

/**
 * Records UI draw commands in painter's order during DOM traversal.
 *
 * <p>Uses a packed fixed-capacity {@code int[]} command pool for zero-allocation
 * recording. Each command occupies {@value #CMD_STRIDE} ints in the pool, with
 * the corresponding {@link CgRenderState} reference stored in a parallel array.</p>
 *
 * <h3>Command kinds</h3>
 * <p>Each command carries a typed discriminant ({@link #CMD_KIND_SOLID} or
 * {@link #CMD_KIND_TEXT}) stored in {@code OFF_FLAGS}. Text commands additionally
 * carry a GL texture ID and MSDF pxRange in parallel side arrays.</p>
 *
 * <h3>Hot-path merge</h3>
 * <p>Consecutive commands with the same render state (by reference), command kind,
 * batch slot, scissor fields, and (for text) texture ID and pxRange are merged by
 * extending the last command's vertex count. Merge detection uses cached
 * {@code last*} fields — never re-reads the command pool.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 * beginRecord()
 *   → all UI traversal and recording
 * endRecord()
 *
 * // replay phase (driven by CgUiDrawListExecutor)
 * </pre>
 * <p>Recording and replay are strictly non-overlapping. After
 * {@code endRecord()}, no new commands may be added.</p>
 *
 * @see CgUiDrawListExecutor
 */
public final class CgUiDrawList {

    // ── Command layout constants ────────────────────────────────────────
    // [0] batchSlot  [1] scissorX  [2] scissorY  [3] scissorW
    // [4] scissorH   [5] vtxStart  [6] vtxCount  [7] cmdKind (flags)
    static final int CMD_STRIDE = 8;
    static final int MAX_COMMANDS = 2048;

    static final int OFF_BATCH_SLOT = 0;
    static final int OFF_SCISSOR_X  = 1;
    static final int OFF_SCISSOR_Y  = 2;
    static final int OFF_SCISSOR_W  = 3;
    static final int OFF_SCISSOR_H  = 4;
    static final int OFF_VTX_START  = 5;
    static final int OFF_VTX_COUNT  = 6;
    static final int OFF_FLAGS      = 7;

    /** Non-text draw command (filled rect, stroke, image, etc.). */
    static final int CMD_KIND_SOLID = 0;
    /** Text draw command — carries textureId and pxRange in parallel arrays. */
    static final int CMD_KIND_TEXT  = 1;

    private final int[] cmdPool = new int[MAX_COMMANDS * CMD_STRIDE];
    private final CgRenderState[] renderStateRefs = new CgRenderState[MAX_COMMANDS];

    // Parallel side arrays for text commands (ignored for CMD_KIND_SOLID)
    private final int[] textTextureIds = new int[MAX_COMMANDS];
    private final float[] textPxRanges = new float[MAX_COMMANDS];

    private int cmdCount;

    // Hot-path merge tracking
    private CgRenderState lastRenderState;
    private int lastCmdKind;
    private int lastBatchSlot;
    private int lastScissorX;
    private int lastScissorY;
    private int lastScissorW;
    private int lastScissorH;
    private int lastTextTextureId;
    private float lastTextPxRange;
    private int lastCommandIndex = -1;

    private boolean recording;

    // ── Frame lifecycle ─────────────────────────────────────────────────

    public void beginRecord() {
        if (recording) throw new IllegalStateException("Already recording");
        cmdCount = 0;
        lastRenderState = null;
        lastCommandIndex = -1;
        recording = true;
    }

    public void endRecord() {
        if (!recording) throw new IllegalStateException("Not recording");
        recording = false;
    }

    // ── Command recording ───────────────────────────────────────────────

    /**
     * Records a non-text (solid) draw command or merges it with the previous
     * command if all merge conditions are met.
     *
     * @param renderState the render state (merge uses reference identity)
     * @param batchSlot   the batch slot index
     * @param scissorX    scissor rect X (0 if disabled)
     * @param scissorY    scissor rect Y (0 if disabled)
     * @param scissorW    scissor rect width (0 if disabled)
     * @param scissorH    scissor rect height (0 if disabled)
     * @param vtxStart    first vertex index in the slot's staging buffer
     * @param vtxCount    number of vertices for this command
     */
    public void record(CgRenderState renderState, int batchSlot,
                       int scissorX, int scissorY, int scissorW, int scissorH,
                       int vtxStart, int vtxCount) {
        if (!recording) throw new IllegalStateException("Not recording — call beginRecord() first");
        if (vtxCount <= 0) return;

        // Hot-path merge: extend last command if state, kind, slot, and scissor all match
        // and vertex data is consecutive in the same batch slot
        if (lastCommandIndex >= 0
                && renderState == lastRenderState
                && CMD_KIND_SOLID == lastCmdKind
                && batchSlot == lastBatchSlot
                && scissorX == lastScissorX && scissorY == lastScissorY
                && scissorW == lastScissorW && scissorH == lastScissorH) {

            int lastBase = lastCommandIndex * CMD_STRIDE;
            int lastVtxStart = cmdPool[lastBase + OFF_VTX_START];
            int lastVtxCount = cmdPool[lastBase + OFF_VTX_COUNT];

            // Merge only if vertex spans are consecutive
            if (vtxStart == lastVtxStart + lastVtxCount) {
                cmdPool[lastBase + OFF_VTX_COUNT] = lastVtxCount + vtxCount;
                return;
            }
        }

        // New command
        if (cmdCount >= MAX_COMMANDS) {
            throw new IllegalStateException("CgUiDrawList command capacity exceeded: " + MAX_COMMANDS);
        }

        int base = cmdCount * CMD_STRIDE;
        cmdPool[base + OFF_BATCH_SLOT] = batchSlot;
        cmdPool[base + OFF_SCISSOR_X]  = scissorX;
        cmdPool[base + OFF_SCISSOR_Y]  = scissorY;
        cmdPool[base + OFF_SCISSOR_W]  = scissorW;
        cmdPool[base + OFF_SCISSOR_H]  = scissorH;
        cmdPool[base + OFF_VTX_START]  = vtxStart;
        cmdPool[base + OFF_VTX_COUNT]  = vtxCount;
        cmdPool[base + OFF_FLAGS]      = CMD_KIND_SOLID;
        renderStateRefs[cmdCount] = renderState;
        textTextureIds[cmdCount] = 0;
        textPxRanges[cmdCount] = Float.NaN;

        lastRenderState = renderState;
        lastCmdKind = CMD_KIND_SOLID;
        lastBatchSlot = batchSlot;
        lastScissorX = scissorX;
        lastScissorY = scissorY;
        lastScissorW = scissorW;
        lastScissorH = scissorH;
        lastCommandIndex = cmdCount;
        cmdCount++;
    }

    /**
     * Records a text draw command or merges it with the previous command if all
     * merge conditions are met (including text-specific texture and pxRange).
     *
     * @param renderState   the text shader render state (merge uses reference identity)
     * @param textTextureId GL texture ID for the atlas page
     * @param textPxRange   MSDF pxRange, or {@link Float#NaN} for bitmap text
     * @param batchSlot     the batch slot index
     * @param scissorX      scissor rect X (0 if disabled)
     * @param scissorY      scissor rect Y (0 if disabled)
     * @param scissorW      scissor rect width (0 if disabled)
     * @param scissorH      scissor rect height (0 if disabled)
     * @param vtxStart      first vertex index in the slot's staging buffer
     * @param vtxCount      number of vertices for this command
     */
    public void recordText(CgRenderState renderState, int textTextureId, float textPxRange,
                           int batchSlot,
                           int scissorX, int scissorY, int scissorW, int scissorH,
                           int vtxStart, int vtxCount) {
        if (!recording) throw new IllegalStateException("Not recording — call beginRecord() first");
        if (vtxCount <= 0) return;

        // Hot-path merge: extend last command if render state, kind, slot, scissor,
        // texture, and pxRange all match and vertex data is consecutive
        if (lastCommandIndex >= 0
                && renderState == lastRenderState
                && CMD_KIND_TEXT == lastCmdKind
                && batchSlot == lastBatchSlot
                && scissorX == lastScissorX && scissorY == lastScissorY
                && scissorW == lastScissorW && scissorH == lastScissorH
                && textTextureId == lastTextTextureId
                && floatMatch(textPxRange, lastTextPxRange)) {

            int lastBase = lastCommandIndex * CMD_STRIDE;
            int lastVtxStart = cmdPool[lastBase + OFF_VTX_START];
            int lastVtxCount = cmdPool[lastBase + OFF_VTX_COUNT];

            if (vtxStart == lastVtxStart + lastVtxCount) {
                cmdPool[lastBase + OFF_VTX_COUNT] = lastVtxCount + vtxCount;
                return;
            }
        }

        if (cmdCount >= MAX_COMMANDS) {
            throw new IllegalStateException("CgUiDrawList command capacity exceeded: " + MAX_COMMANDS);
        }

        int base = cmdCount * CMD_STRIDE;
        cmdPool[base + OFF_BATCH_SLOT] = batchSlot;
        cmdPool[base + OFF_SCISSOR_X]  = scissorX;
        cmdPool[base + OFF_SCISSOR_Y]  = scissorY;
        cmdPool[base + OFF_SCISSOR_W]  = scissorW;
        cmdPool[base + OFF_SCISSOR_H]  = scissorH;
        cmdPool[base + OFF_VTX_START]  = vtxStart;
        cmdPool[base + OFF_VTX_COUNT]  = vtxCount;
        cmdPool[base + OFF_FLAGS]      = CMD_KIND_TEXT;
        renderStateRefs[cmdCount] = renderState;
        textTextureIds[cmdCount] = textTextureId;
        textPxRanges[cmdCount] = textPxRange;

        lastRenderState = renderState;
        lastCmdKind = CMD_KIND_TEXT;
        lastBatchSlot = batchSlot;
        lastScissorX = scissorX;
        lastScissorY = scissorY;
        lastScissorW = scissorW;
        lastScissorH = scissorH;
        lastTextTextureId = textTextureId;
        lastTextPxRange = textPxRange;
        lastCommandIndex = cmdCount;
        cmdCount++;
    }

    /** Matches two pxRange values, treating NaN == NaN as true. */
    private static boolean floatMatch(float a, float b) {
        if (Float.isNaN(a) && Float.isNaN(b)) return true;
        return a == b;
    }

    // ── Replay access (package-private for CgUiDrawListExecutor) ────────

    public int commandCount() { return cmdCount; }
    public boolean isRecording() { return recording; }

    int batchSlot(int cmdIndex)  { return cmdPool[cmdIndex * CMD_STRIDE + OFF_BATCH_SLOT]; }
    int scissorX(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_SCISSOR_X]; }
    int scissorY(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_SCISSOR_Y]; }
    int scissorW(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_SCISSOR_W]; }
    int scissorH(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_SCISSOR_H]; }
    int vtxStart(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_VTX_START]; }
    int vtxCount(int cmdIndex)   { return cmdPool[cmdIndex * CMD_STRIDE + OFF_VTX_COUNT]; }
    int cmdKind(int cmdIndex)    { return cmdPool[cmdIndex * CMD_STRIDE + OFF_FLAGS]; }
    CgRenderState renderState(int cmdIndex)  { return renderStateRefs[cmdIndex]; }
    int textTextureId(int cmdIndex)          { return textTextureIds[cmdIndex]; }
    float textPxRange(int cmdIndex)          { return textPxRanges[cmdIndex]; }
}
