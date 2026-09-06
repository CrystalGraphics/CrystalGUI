package com.crystalgui.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgui.core.CrystalGuiCore;

/**
 * <b>A probe for the nested render-target path, off unless asked for.</b>
 * {@code -Dcrystalgui.layer.probe=true}
 *
 * <p>It exists for one question, and it is the question every symptom in this path shares: content that
 * should be on screen is not. From the screen alone "the target never received it" and "the target
 * received it and the composite threw it away" are indistinguishable — both are a missing picture, both
 * throw nothing, and both look like the widget rather than the plumbing. Only a readback separates them,
 * which is why {@code glReadPixels} had to reach the backend at all.
 *
 * <p>So each layer is sampled at two moments: after its subtree has painted into it, and after it has
 * been composited back. Non-zero then zero is a composite fault; zero then zero is a draw fault; and a
 * framebuffer that is not complete is neither, which is worth knowing before reading either number.
 *
 * <p><b>Every call is a full pipeline stall</b> — a synchronous readback per layer per frame. That is
 * the point of the flag; nothing here may run in an ordinary frame.
 */
public final class CgUiLayerProbe {

    /** {@code -Dcrystalgui.layer.probe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgui.layer.probe");

    /**
     * Stop after this many lines. A stall per layer per frame makes the game unusable within seconds.
     */
    private static final int BUDGET = 2000;

    /**
     * Sample one frame in this many.
     *
     * <p><b>Without it the budget is spent before the thing under test exists.</b> The first run of this
     * probe burned all 400 lines in the opening seconds and reported every target empty — which was
     * TRUE and meant nothing, because a desktop with no window open draws nothing, so an empty frame
     * there is the correct answer. Startup is exactly when a probe is cheapest to trigger and least
     * worth reading.</p>
     */
    private static final int EVERY = 60;

    private static int emitted;
    private static long frames;

    /** Whether this frame is one of the sampled ones. Set once per frame so a frame is whole or absent. */
    private static boolean sampling;

    /** Called at the top of every UI frame; decides whether this one is sampled. */
    public static void frame() {
        sampling = ENABLED && (frames++ % EVERY == 0);
    }

    private CgUiLayerProbe() {}

    /**
     * Which element the layer being opened belongs to.
     *
     * <p>Every reading here is a patch of pixels, and a patch of pixels cannot say <em>whose</em> it is
     * -- so a black region on screen and a black layer in the log could only be correlated by counting
     * nesting depth and guessing. The painter knows the answer for free; it just had nowhere to put it.
     *
     * <p>A plain static rather than a parameter, because it has to reach {@code CgUiPaintContext}
     * through calls that have no business carrying a debug label ({@code beginLayerFbo} is public API).
     * Single-threaded by the frame-thread rule, so there is nothing to synchronise.</p>
     */
    private static String owner = "?";

    /** Set by the painter as it enters a box that needs a layer. */
    public static void owner(String describedBy) {
        if (ENABLED) owner = describedBy;
    }

    public static String owner() {
        return owner;
    }

    /** The owning box's rect in PHYSICAL pixels, top-left origin -- what the painter is about to draw. */
    private static float ownX, ownY, ownW, ownH;

    public static void ownerRect(float x, float y, float w, float h) {
        if (!ENABLED) return;
        ownX = x; ownY = y; ownW = w; ownH = h;
    }

    /**
     * What a target holds AT THE OWNING BOX'S OWN CENTRE, which is the only place worth reading when the
     * question is whether a shape landed where its element is.
     *
     * <p>The fixed centre/corner/top-left patches answer "is this target empty"; they cannot answer "is
     * this shape in the right place", because a screen-sized layer is mostly legitimately empty and an
     * element occupies a small part of it. A mask that is correct and one displaced by half a window
     * read identically at the layer's centre unless the element happens to be centred there.</p>
     */
    public static String atOwner(CgFrameBuffer fbo) {
        if (!ENABLED || !sampling) return "";
        if (fbo == null || ownW <= 0 || ownH <= 0) return "<no owner rect>";
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO)) {
            CgGlState.invalidateAllIfPresent();
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo.getId());
            while (CgGL.glGetError() != 0) { /* the caller's */ }
            int w = Math.min(PATCH, Math.max(1, fbo.getWidth()));
            int h = Math.min(PATCH, Math.max(1, fbo.getHeight()));
            // GL is bottom-left origin and the rect is top-left, so the row flips.
            int cx = Math.round(ownX + ownW / 2f) - w / 2;
            int cy = fbo.getHeight() - Math.round(ownY + ownH / 2f) - h / 2;
            cx = Math.max(0, Math.min(Math.max(0, fbo.getWidth() - w), cx));
            cy = Math.max(0, Math.min(Math.max(0, fbo.getHeight() - h), cy));
            // AND THE TOP-LEFT OF THE BOX, a few pixels in, which for anything that lays its content out
            // from the top is where the content IS. The centre answers a different question and answered
            // it misleadingly once already: an 8-line file in a 329px-tall editor is legitimately blank
            // at its own centre, so a centre patch reported the children layer empty when the text was
            // sitting 250px above the sample.
            int tx = Math.max(0, Math.min(Math.max(0, fbo.getWidth() - w), Math.round(ownX) + 4));
            int ty = Math.max(0, Math.min(Math.max(0, fbo.getHeight() - h),
                    fbo.getHeight() - Math.round(ownY) - h - 4));
            return "box(" + Math.round(ownX) + "," + Math.round(ownY) + " "
                    + Math.round(ownW) + "x" + Math.round(ownH) + ")"
                    + " centre=" + patch(cx, cy, w, h)
                    + " topOfContent=" + patch(tx, ty, w, h);
        } catch (Throwable unreadable) {
            return "<unreadable: " + unreadable + ">";
        }
    }

    /** A 32x32 patch is read rather than one pixel: a layer's centre is legitimately empty. */
    private static final int PATCH = 32;

    private static final ByteBuffer PIXELS =
            ByteBuffer.allocateDirect(PATCH * PATCH * 4).order(ByteOrder.nativeOrder());

    public static void log(String what) {
        if (!ENABLED || !sampling || emitted++ > BUDGET) return;
        CrystalGuiCore.LOGGER.info("[cgui-probe] {}", what);
    }

    /** Framebuffer completeness plus any pending GL error, for a target about to be drawn into. */
    public static String target(String label, CgFrameBuffer fbo) {
        if (!ENABLED || !sampling) return "";
        if (fbo == null) return label + "=<null>";
        return label + "=#" + fbo.getId() + " " + fbo.getWidth() + "x" + fbo.getHeight();
    }

    /**
     * What is actually IN a target: how many of a centre patch's pixels are non-zero, and the brightest
     * one. Zero coverage after a subtree has painted means the drawing never landed; full coverage that
     * does not survive the composite means the opposite.
     */
    public static String contents(CgFrameBuffer fbo) {
        if (!ENABLED || !sampling) return "";
        if (fbo == null) return "<null>";
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO)) {
            // THE PROBE HAS THE DISEASE IT IS INVESTIGATING. CgGL.glBindFramebuffer goes through the
            // state shadow, which eliminates a bind it believes is already in effect -- so without this
            // the readback below can silently sample whatever was ALREADY bound instead of the target
            // asked for, and every number here would be a confident measurement of the wrong thing.
            CgGlState.invalidateAllIfPresent();
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo.getId());
            // Anything already queued belongs to the caller, not to us; drained so the error reported
            // below is attributable to this readback.
            while (CgGL.glGetError() != 0) { /* drain */ }
            int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
            if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
                return "INCOMPLETE(0x" + Integer.toHexString(status) + ")";
            }
            int w = Math.min(PATCH, Math.max(1, fbo.getWidth()));
            int h = Math.min(PATCH, Math.max(1, fbo.getHeight()));
            // THE CORNER IS THE ONE THAT MATTERS, and sampling only the centre hid it for four runs.
            // blitLayer composites a layer FULL-SCREEN, and its contract says that is safe only because
            // "everywhere the layer's own content didn't draw stayed transparent from the initial clear".
            // The centre is where a window legitimately is, so it is opaque either way and says nothing.
            // A corner that is NOT transparent is the whole of symptoms 3 and 4: a full-screen composite
            // of it at fractional opacity darkens (or whitens) the entire screen while appearing to
            // animate nothing.
            String centre = patch(Math.max(0, (fbo.getWidth() - w) / 2),
                                  Math.max(0, (fbo.getHeight() - h) / 2), w, h);
            String corner = patch(0, 0, w, h);
            // AND THE TOP-LEFT, which is the only place the BACKDROP CAPTURE writes: it blits the scene
            // into a sub-rect at the target's top-left, so centre and corner both land in untouched
            // buffer. Read without it, the capture reports itself uniformly opaque black -- which is
            // exactly what a failed scene blit would look like, and is really the alpha-only clear
            // outside the region. It nearly cost a wrong root cause.
            String top = patch(0, Math.max(0, fbo.getHeight() - h), w, h);
            return "centre[" + centre + "] corner[" + corner + "] topLeft[" + top + "]";
        } catch (Throwable unreadable) {
            return "<unreadable: " + unreadable + ">";
        }
    }

    /**
     * The same reading taken of whatever framebuffer is CURRENTLY bound, with no rebinding.
     *
     * <p>For the one target there is no {@code CgFrameBuffer} for: Minecraft's own. This is the
     * discriminator {@code endFrame} already names in prose — a picture that is correct here while the
     * window shows a flat fill means the drawing was never the broken part, the presenting was.</p>
     */
    public static String bound(int width, int height) {
        if (!ENABLED || !sampling) return "";
        try {
            while (CgGL.glGetError() != 0) { /* the caller's, not ours */ }
            int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
            if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
                return "INCOMPLETE(0x" + Integer.toHexString(status) + ")";
            }
            int w = Math.min(PATCH, Math.max(1, width));
            int h = Math.min(PATCH, Math.max(1, height));
            return "centre[" + patch(Math.max(0, (width - w) / 2), Math.max(0, (height - h) / 2), w, h)
                    + "] corner[" + patch(0, 0, w, h)
                    + "] topLeft[" + patch(0, Math.max(0, height - h), w, h) + "]";
        } catch (Throwable unreadable) {
            return "<unreadable: " + unreadable + ">";
        }
    }

    /**
     * <b>What the driver ACTUALLY has bound, against what this code believes it drew into.</b>
     *
     * <p>The question a composite that quietly writes nothing comes down to. Every bind in this engine
     * goes through a shadow that eliminates one it thinks is redundant, so a restore that was elided
     * leaves the driver on the previous target while every layer of bookkeeping above it — including
     * this probe's own idea of the destination — still names the intended one. The draw then lands
     * somewhere real, just not where anybody is looking, which is indistinguishable from not drawing.</p>
     */
    public static String boundVsExpected(CgFrameBuffer expected) {
        if (!ENABLED || !sampling) return "";
        try {
            int live = CgGL.glGetInteger(CgGL.GL_FRAMEBUFFER_BINDING);
            int want = expected == null ? -1 : expected.getId();
            return live == want ? "dest=#" + live : "DEST MISMATCH live=#" + live + " expected=#" + want;
        } catch (Throwable cannotTell) {
            return "dest=<unreadable>";
        }
    }

    /** One patch of the bound READ framebuffer, as coverage plus the brightest sample in it. */
    private static String patch(int x, int y, int w, int h) {
        PIXELS.clear();
        CgGL.glReadPixels(x, y, w, h, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, PIXELS);
        int error = CgGL.glGetError();

        int covered = 0, maxA = 0, maxRgb = 0;
        for (int i = 0; i < w * h; i++) {
            int r = PIXELS.get(i * 4) & 0xFF;
            int g = PIXELS.get(i * 4 + 1) & 0xFF;
            int b = PIXELS.get(i * 4 + 2) & 0xFF;
            int a = PIXELS.get(i * 4 + 3) & 0xFF;
            if ((r | g | b | a) != 0) covered++;
            if (a > maxA) maxA = a;
            maxRgb = Math.max(maxRgb, Math.max(r, Math.max(g, b)));
        }
        return covered + "/" + (w * h) + " maxA=" + maxA + " maxRGB=" + maxRgb
                + (error == 0 ? "" : " glError=0x" + Integer.toHexString(error));
    }

    /**
     * <b>What the DRIVER's blend state actually is</b>, read from GL rather than from any shadow.
     *
     * <p>The one question a pixel readback cannot answer. Compositing a fully transparent source over a
     * destination is a no-op under every `over` blend and a total erase under none -- so a destination
     * that goes black under a transparent source says only that the blend was not what the material
     * asked for. Whether that is a disabled test, a replace, or the wrong factors is a fact about the
     * driver, and this engine keeps a CPU shadow that eliminates calls it believes are redundant, so
     * asking the shadow would return the answer that is already suspect.</p>
     */
    public static String blend() {
        if (!ENABLED || !sampling) return "";
        try {
            boolean on = CgGL.glGetBoolean(CgGL.GL_BLEND);
            return "blend=" + (on ? "ON" : "OFF")
                    + " srcRGB=0x" + Integer.toHexString(CgGL.glGetInteger(CgGL.GL_BLEND_SRC_RGB))
                    + " dstRGB=0x" + Integer.toHexString(CgGL.glGetInteger(CgGL.GL_BLEND_DST_RGB))
                    + " srcA=0x" + Integer.toHexString(CgGL.glGetInteger(CgGL.GL_BLEND_SRC_ALPHA))
                    + " dstA=0x" + Integer.toHexString(CgGL.glGetInteger(CgGL.GL_BLEND_DST_ALPHA));
        } catch (Throwable unreadable) {
            return "blend=<unreadable>";
        }
    }

    /**
     * <b>Which texture unit is active and what is bound to it</b>, read from the driver.
     *
     * <p>The last thing that can make a correct blend of a correct source erase its destination:
     * a sampler reading a unit nothing is bound to. GL answers such a read with <b>(0, 0, 0, 1)</b> --
     * opaque black -- so under premultiplied `over` it is not a missing image, it is an eraser. And it
     * is the failure this loader is uniquely exposed to, because Blaze3D caches both the active unit
     * and each unit's binding while CrystalGraphics writes raw GL that Blaze3D never sees.</p>
     */
    public static String textureUnit() {
        if (!ENABLED || !sampling) return "";
        try {
            int unit = CgGL.glGetInteger(CgGL.GL_ACTIVE_TEXTURE) - CgGL.GL_TEXTURE0;
            return "activeUnit=" + unit + " bound2D=" + CgGL.glGetInteger(CgGL.GL_TEXTURE_BINDING_2D);
        } catch (Throwable unreadable) {
            return "textureUnit=<unreadable>";
        }
    }

    /**
     * <b>What is bound on UNIT 0 specifically</b>, which is the unit every UI shader samples.
     *
     * <p>{@link #textureUnit()} reports the ACTIVE unit, and that is a different question: the engine
     * leaves the active unit on its own depth-sampler slot, so a reading of "unit 28 holds the texture I
     * wanted" says nothing about what the shader will actually read. Only unit 0 does.
     *
     * <p>Perturbs the active unit for the length of the read and puts it back. Acceptable in a probe and
     * nowhere else; the shadow is dropped first so the restore cannot itself be elided.</p>
     */
    public static String unit0() {
        if (!ENABLED || !sampling) return "";
        try {
            int was = CgGL.glGetInteger(CgGL.GL_ACTIVE_TEXTURE);
            CgGlState.invalidateAllIfPresent();
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            int on0 = CgGL.glGetInteger(CgGL.GL_TEXTURE_BINDING_2D);
            CgGL.glActiveTexture(was);
            CgGlState.invalidateAllIfPresent();
            return "unit0Holds=" + on0;
        } catch (Throwable unreadable) {
            return "unit0Holds=<unreadable>";
        }
    }

    /**
     * <b>The driver's scissor</b>, which is the last thing that can stop a full-target quad filling one.
     *
     * <p>A clip rect is in SCREEN pixels, so one inherited into a target with its own coordinate space
     * does not clip, it corrupts -- and the disable that is supposed to prevent that goes through the
     * same shadow that elides calls it believes redundant. A target written only in the middle, with the
     * edges left at the clear, is what that looks like.</p>
     */
    public static String scissor() {
        if (!ENABLED || !sampling) return "";
        try {
            boolean on = CgGL.glGetBoolean(CgGL.GL_SCISSOR_TEST);
            if (!on) return "scissor=OFF";
            java.nio.IntBuffer box = java.nio.ByteBuffer.allocateDirect(16)
                    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            CgGL.glGetInteger(CgGL.GL_SCISSOR_BOX, box);
            return "scissor=ON(" + box.get(0) + "," + box.get(1)
                    + " " + box.get(2) + "x" + box.get(3) + ")";
        } catch (Throwable unreadable) {
            return "scissor=<unreadable>";
        }
    }

    /** Whatever the driver has queued, named at a point where it can still be attributed. */
    public static String errors(String where) {
        if (!ENABLED) return "";
        int first = CgGL.glGetError();
        if (first == 0) return "";
        StringBuilder all = new StringBuilder(" glError@" + where + "=0x" + Integer.toHexString(first));
        for (int drained = 0; drained < 8; drained++) {
            int next = CgGL.glGetError();
            if (next == 0) break;
            all.append(",0x").append(Integer.toHexString(next));
        }
        return all.toString();
    }
}
