package com.crystalgui.core.render;

/**
 * Records UI draw commands in painter's order during DOM traversal.
 *
 * <p>Uses a packed fixed-capacity {@code int[]} command pool for zero-allocation
 * recording. Each command occupies {@value #CMD_STRIDE} ints in the pool, with
 * the corresponding {@link CgUiDrawState} reference stored in a parallel array.</p>
 *
 * <h3>Hot-path merge</h3>
 * <p>Consecutive commands with the same draw state (by reference), batch slot,
 * and scissor fields are merged by extending the last command's vertex count.
 * Merge detection uses cached {@code last*} fields — never re-reads
 * {@code drawStateRefs[cmdCount - 1]}.</p>
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
    // [4] scissorH   [5] vtxStart  [6] vtxCount  [7] reserved flags
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

    private final int[] cmdPool = new int[MAX_COMMANDS * CMD_STRIDE];
    private final CgUiDrawState[] drawStateRefs = new CgUiDrawState[MAX_COMMANDS];
    private int cmdCount;

    // Hot-path merge tracking
    private CgUiDrawState lastDrawState;
    private int lastBatchSlot;
    private int lastScissorX;
    private int lastScissorY;
    private int lastScissorW;
    private int lastScissorH;
    private int lastCommandIndex = -1;

    private boolean recording;

    // ── Frame lifecycle ─────────────────────────────────────────────────

    public void beginRecord() {
        if (recording) throw new IllegalStateException("Already recording");
        cmdCount = 0;
        lastDrawState = null;
        lastCommandIndex = -1;
        recording = true;
    }

    public void endRecord() {
        if (!recording) throw new IllegalStateException("Not recording");
        recording = false;
    }

    // ── Command recording ───────────────────────────────────────────────

    /**
     * Records a draw command or merges it with the previous command if all
     * merge conditions are met.
     *
     * @param drawState the cached draw state (merge uses reference identity)
     * @param batchSlot the batch slot index
     * @param scissorX  scissor rect X (0 if disabled)
     * @param scissorY  scissor rect Y (0 if disabled)
     * @param scissorW  scissor rect width (0 if disabled)
     * @param scissorH  scissor rect height (0 if disabled)
     * @param vtxStart  first vertex index in the slot's staging buffer
     * @param vtxCount  number of vertices for this command
     */
    public void record(CgUiDrawState drawState, int batchSlot,
                       int scissorX, int scissorY, int scissorW, int scissorH,
                       int vtxStart, int vtxCount) {
        if (!recording) throw new IllegalStateException("Not recording — call beginRecord() first");
        if (vtxCount <= 0) return;

        // Hot-path merge: extend last command if state, slot, and scissor all match
        // and vertex data is consecutive in the same batch slot
        if (lastCommandIndex >= 0
                && drawState == lastDrawState
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
        cmdPool[base + OFF_FLAGS]      = 0;
        drawStateRefs[cmdCount] = drawState;

        lastDrawState = drawState;
        lastBatchSlot = batchSlot;
        lastScissorX = scissorX;
        lastScissorY = scissorY;
        lastScissorW = scissorW;
        lastScissorH = scissorH;
        lastCommandIndex = cmdCount;
        cmdCount++;
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
    CgUiDrawState drawState(int cmdIndex) { return drawStateRefs[cmdIndex]; }
}
