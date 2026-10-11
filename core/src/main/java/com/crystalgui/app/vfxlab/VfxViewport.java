package com.crystalgui.app.vfxlab;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.service.Input;
import com.crystalgui.ui.service.PlatformPort;
import com.crystalgui.widget.text.UIText;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The lab's 3D view: a camera of its own, the world stages fired again from it into a framebuffer this element paints.
 * It moves as Roblox Studio's and Unity's scene views do: drag looks around, the wheel dollies, and once clicked WASD
 * flies where it looks, Space up and Shift down. {@link VfxLab} owns it.
 */
final class VfxViewport extends UIElement {

    static final Name NAME = Name.of("vfxviewport");

    private static final CgRenderStage[] WORLD = {CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT};
    private static final float LOOK_PER_PIXEL = 0.005f;
    private static final float PITCH_LIMIT = 1.55f;
    /** Metres a second. */
    private static final float FLY_SPEED = 12f;
    /** Ctrl's multiple of the speed. */
    private static final float SPRINT = 4f;
    /** Metres a wheel notch. */
    private static final float DOLLY = 2f;
    /** Draws a replaced target outlives its replacement: the UI may still present a frame recorded against it. */
    private static final int RETIRE_DRAWS = 4;
    /** Nanoseconds the frame rate is counted over. */
    private static final long FPS_WINDOW = 500_000_000L;
    private static final float START_YAW = 0.62f, START_PITCH = -0.23f;
    private static final float START_X = 10f, START_Y = 4f, START_Z = 14f;
    /** The axis gizmo's centre from the top-right corner, and its arms' length. */
    private static final float GIZMO_INSET = 30f, GIZMO_ARM = 18f;
    /** x red, y blue, z green, as the grid draws its axes. */
    private static final int[] GIZMO_COLORS = {0xFFFF3352, 0xFF4F8DFF, 0xFF8CDB00};

    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private final Matrix4f hostView = new Matrix4f(), hostProjection = new Matrix4f();
    /** Yaw 0 looks along -z; positive pitch looks up. Opens on the point {@link #draw} is given, from 18 m away. */
    private volatile float yaw = START_YAW, pitch = START_PITCH, aspect = 1f;
    /** The eye, from the point {@link #draw} is given. */
    private volatile float eyeX = START_X, eyeY = START_Y, eyeZ = START_Z;
    private float dragYaw, dragPitch;
    @Nullable
    private CgFrameBuffer target;
    @Nullable
    private volatile CgTexture picture;
    private final List<CgFrameBuffer> retired = new ArrayList<>();
    private final List<Long> retiredAt = new ArrayList<>();
    private long draws;
    private final UIText stats = new UIText("");
    private long fpsSince, fpsDraws;
    private volatile int fps, particles;

    VfxViewport() {
        super(NAME);
        setFocusPolicy(FocusPolicy.CLICK);
        UIText hint = new UIText("Drag to look  ·  click, then WASD to fly, Ctrl to sprint  ·  Space / Shift up and down  ·  wheel to dolly");
        hint.addClass("lab-hint");
        hint.setHitTest(false);
        stats.addClass("lab-stats");
        stats.setHitTest(false);
        append(hint, stats);
        onMouseDown.attachListener((element, event) -> {
            // Space on the focused viewport is flying up, not a press: no drag no pointer will end.
            if (event.getDetail() == Input.KEYBOARD_DETAIL) return;
            document().focus().requestFocus(this);
            dragYaw = yaw;
            dragPitch = pitch;
            Drag.start(this, event.getPosition().x(), event.getPosition().y(), new Drag.Listener() {
                @Override
                public void onDragUpdate(float x, float y, float startX, float startY, float deltaX, float deltaY) {
                    yaw = dragYaw - deltaX * LOOK_PER_PIXEL;
                    pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, dragPitch - deltaY * LOOK_PER_PIXEL));
                }
            });
            event.stopPropagation();
        }, false, true);
        onMouseScroll.attachListener((element, event) -> {
            // A positive notch is the wheel rolled down: back.
            move(event.getScroll() > 0 ? -DOLLY : DOLLY, 0f, 0f);
            event.stopPropagation();
        }, false, true);
        // The picture moves every frame, which the tree cannot see.
        onConnected(() -> document().animation().every(this, delta -> {
            if (isFocused()) fly(delta);
            stats.setText(String.format(Locale.ROOT, "%d fps  ·  %,d particles", fps, particles));
            repaint();
            return true;
        }));
    }

    /** Puts the eye back where the viewport opened. */
    void resetCamera() {
        yaw = START_YAW;
        pitch = START_PITCH;
        eyeX = START_X;
        eyeY = START_Y;
        eyeZ = START_Z;
    }

    /** Ctrl+W and the rest are sprinting while the viewport has focus, never the keymap's chords. */
    @Override
    public boolean claimsChord(int key, int modifiers) {
        return key == CgKeyCodes.KEY_W || key == CgKeyCodes.KEY_A || key == CgKeyCodes.KEY_S || key == CgKeyCodes.KEY_D
                || key == CgKeyCodes.KEY_SPACE || key == CgKeyCodes.KEY_LSHIFT;
    }

    /** Moves the eye on the held keys. */
    private void fly(float delta) {
        PlatformPort keys = PlatformPort.current();
        float step = FLY_SPEED * delta * (CgModifiers.hasCtrl(keys.modifiers()) ? SPRINT : 1f);
        move(axis(keys, CgKeyCodes.KEY_W, CgKeyCodes.KEY_S) * step, axis(keys, CgKeyCodes.KEY_D, CgKeyCodes.KEY_A) * step,
                axis(keys, CgKeyCodes.KEY_SPACE, CgKeyCodes.KEY_LSHIFT) * step);
    }

    /** Moves the eye: {@code forward} where it looks, {@code right} level to its right, {@code up} straight up. */
    private void move(float forward, float right, float up) {
        float sinYaw = (float) Math.sin(yaw), cosYaw = (float) Math.cos(yaw);
        float cosPitch = (float) Math.cos(pitch), sinPitch = (float) Math.sin(pitch);
        eyeX += -sinYaw * cosPitch * forward + cosYaw * right;
        eyeY += sinPitch * forward + up;
        eyeZ += -cosYaw * cosPitch * forward - sinYaw * right;
    }

    private static float axis(PlatformPort keys, int positive, int negative) {
        return (keys.isKeyDown(positive) ? 1f : 0f) - (keys.isKeyDown(negative) ? 1f : 0f);
    }

    /**
     * Draws the world from the eye, relative to ({@code x}, {@code y}, {@code z}), into the viewport's target, on the render
     * thread after the frame's own world firing, whose camera it puts back. {@code width} x {@code height} is that
     * firing's size: the world renderer keeps its targets at one size, so the view renders at it and is scaled into
     * the element.
     */
    void draw(int width, int height, double x, double y, double z) {
        if (width <= 0 || height <= 0) return;
        draws++;
        long now = System.nanoTime();
        if (++fpsDraws == 1) fpsSince = now;
        if (now - fpsSince >= FPS_WINDOW) {
            fps = Math.round((fpsDraws - 1) * 1e9f / (now - fpsSince));
            fpsDraws = 1;
            fpsSince = now;
        }
        while (!retired.isEmpty() && draws - retiredAt.get(0) > RETIRE_DRAWS) {
            retired.remove(0).delete();
            retiredAt.remove(0);
        }
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            if (target != null) {
                retired.add(target);
                retiredAt.add(draws);
            }
            target = CgFrameBuffer.createOwned("vfx_lab_viewport", width, height, CgFrameBufferFormat.RGBA8_WITH_DEPTH);
        }
        // editor_grid.shader's grey.
        target.clearColor(0.239f, 0.239f, 0.239f, 1f);
        target.clearDepth();

        double atX = x + eyeX, atY = y + eyeY, atZ = z + eyeZ;
        float cosPitch = (float) Math.cos(pitch);
        view.setLookAt(0f, 0f, 0f, (float) -Math.sin(yaw) * cosPitch, (float) Math.sin(pitch),
                (float) -Math.cos(yaw) * cosPitch, 0f, 1f, 0f);

        CgHostFrame first = WORLD[0].host();
        CgHostView host = first.view();
        float tick = first.partialTick();
        int hostWidth = first.width(), hostHeight = first.height(), hostTarget = first.mainFramebuffer();
        double hostX = host.x(), hostY = host.y(), hostZ = host.z();
        hostView.set(host.view());
        hostProjection.set(host.projection());
        // The host's projection keeps its field of view and depth convention; only the aspect is the element's.
        if (hostProjection.m00() != 0f) {
            projection.set(hostProjection).m00(hostProjection.m00() * width / height / aspect);
        } else {
            projection.setPerspective((float) Math.toRadians(60), aspect, 0.05f, 1000f);
        }

        // A stage draws into the bound framebuffer; the host frame's id alone does not move it.
        target.bind();
        for (CgRenderStage stage : WORLD) {
            stage.host().set(0f, width, height, target.getId()).view().set(atX, atY, atZ, view, projection);
            stage.fire();
        }
        for (CgRenderStage stage : WORLD) {
            stage.host().set(tick, hostWidth, hostHeight, hostTarget).view().set(hostX, hostY, hostZ, hostView, hostProjection);
        }
        target.unbind();
        picture = target.getColorTexture(0);
        particles = CgVfxSystem.particlesDrawn(target.getId());
    }

    /** Where the eye is, from the point {@link #draw} is given. */
    float eyeX() {
        return eyeX;
    }

    float eyeY() {
        return eyeY;
    }

    float eyeZ() {
        return eyeZ;
    }

    /** Frees the target. On the render thread. */
    void release() {
        picture = null;
        if (target != null) target.delete();
        target = null;
        for (CgFrameBuffer old : retired) old.delete();
        retired.clear();
        retiredAt.clear();
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        super.paintContent(ctx, box);
        float w = box.width(), h = box.height();
        if (w <= 0f || h <= 0f) return;
        aspect = w / h;
        CgTexture texture = picture;
        // v flipped: the framebuffer's origin is bottom-left.
        if (texture != null) ctx.drawImage(texture, 0f, 0f, w, h, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
        paintGizmo(ctx, w - GIZMO_INSET, GIZMO_INSET);
    }

    /** The world's axes as the eye sees them, from ({@code cx}, {@code cy}). */
    private void paintGizmo(CgUiPaintContext ctx, float cx, float cy) {
        float sinYaw = (float) Math.sin(yaw), cosYaw = (float) Math.cos(yaw);
        float sinPitch = (float) Math.sin(pitch), cosPitch = (float) Math.cos(pitch);
        // Each axis on the eye's right and up: right is (cos yaw, 0, -sin yaw), up is right x forward.
        float[] right = {cosYaw, 0f, -sinYaw};
        float[] up = {sinYaw * sinPitch, cosPitch, cosYaw * sinPitch};
        for (int axis = 0; axis < 3; axis++) {
            float x = cx + right[axis] * GIZMO_ARM, y = cy - up[axis] * GIZMO_ARM;
            ctx.curve().line(cx, cy, x, y).width(1f).color(GIZMO_COLORS[axis]).submit();
            ctx.fillRect(x - 2f, y - 2f, 4f, 4f, GIZMO_COLORS[axis]);
        }
    }
}
