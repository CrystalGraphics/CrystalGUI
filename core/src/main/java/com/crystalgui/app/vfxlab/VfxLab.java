package com.crystalgui.app.vfxlab;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.demo.CgVfxDemoControls;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.PlatformPort;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.config.ConfigForm;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.ToolbarForm;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/**
 * A workbench for effects: pick one from the {@link CgVfxRegistry}, play it in the lab's 3D viewport, and edit its
 * parameters while it plays. Any host with a world renderer runs it, from a window on its desktop.
 *
 * <pre>{@code
 * VfxLab lab = new VfxLab();
 * VfxLab.openOn(desktop, lab);                                  // again to bring a closed window back
 * // on the render thread: the effects play while the world fires this...
 * CgRenderStage.Registration frames = CgWorldRenderer.get().onFrame(view -> lab.render(seconds));
 * // ...and the viewport draws after the frame's world, before the desktop paints
 * lab.drawViewport(worldWidth, worldHeight);
 * // closing for good, on the render thread:
 * frames.close();
 * lab.delete();
 * }</pre>
 *
 * <ul>
 *   <li>The panels may run on the document's thread: what they do to effects is queued for {@link #render}.</li>
 *   <li>Effects play in lab space, {@link #LAB_Y} below the world on a floor of their own: past every far plane of the
 *       host's camera, and the host's draws past the viewport's.</li>
 *   <li>Edits reach every effect the lab spawned that is still playing, and every one it spawns after.</li>
 *   <li>A colour is edited as a swatch and an intensity, Unity's HDR colour: the swatch is the colour over its
 *       brightest channel, the intensity that channel. Curves and shakes are listed only.</li>
 *   <li>Copy puts the edited values on the clipboard as {@code .set(...)} lines for a look's builder.</li>
 *   <li>The Rendering tab sets the world's HDR scene and bloom and the blast's flash and impact frame for the whole run, the
 *       harness HUD's switches: {@link CgVfxDemoControls} is shared with its keys.</li>
 * </ul>
 */
public class VfxLab extends UIElement {

    public static final Name NAME = Name.of("vfxlab");

    /** The window's key on a desktop: one lab a desktop. */
    public static final String WINDOW_KEY = "crystalgui:vfx-lab";

    /** Lab space's floor: effects spawn above the origin of it, aimed along +x. */
    public static final double LAB_Y = -8192.0;

    private static final String FLOOR_SHADER = "crystalgraphics:shaders/demo/editor_grid.shader";
    private static final String SKY_SHADER = "crystalgraphics:shaders/demo/editor_sky.shader";
    private static final Matrix4fc SKY_SCALE = new Matrix4f().scale(600f);
    private static final float MAX_SPEED = 10f;
    /** Metres the floor's grid fades out by, from a quarter of it; the plane spans past it, so its edge never shows. */
    private static final float FLOOR_FADE = 500f;
    /** Seconds a status message stays. */
    private static final double MESSAGE_SECONDS = 3.0;

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final List<CgVfxEffect> live = new ArrayList<>();
    private final VfxViewport viewport = new VfxViewport();
    private final CgMesh floor = CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.plane(m, 1, 1, FLOOR_FADE * 2.5f, FLOOR_FADE * 2.5f));
    private final CgMesh sky = CgMeshShapes.sphere(24, 32);
    @Nullable
    private CgMaterial floorMaterial, skyMaterial;

    private final SearchField search = new SearchField();
    private final TreeView<Object> tree = new TreeView<>(new Effects());
    private final UIElement inspector = new UIElement();
    private final UIText title = new UIText("No effect");
    private final UIText id = new UIText("");
    private final UIElement stateDot = new UIElement();
    private final UIText stateText = new UIText("");
    private final UIText countText = new UIText("");
    private final UIText messageText = new UIText("");
    private final UIText effectText = new UIText("");
    private final UIText speedText = new UIText("");
    /** Puts every value back to the look's; shown while any differs. */
    private final UIElement resetAll = new UIElement();
    /** Each row's reset mark, by its parameter. */
    private final List<CgVfxParam> markedParams = new ArrayList<>();
    private final List<UIElement> marks = new ArrayList<>();
    private final Button pause = iconButton("lab-pause", "Pause", this::togglePause);
    private final Button loop = iconButton("lab-loop", "Loop: play the effect again once it ends", this::toggleLoop);
    /** Lab space under a night sky; {@link #nightMix} eases toward it on the render thread. */
    private volatile boolean night;
    private float nightMix;
    /** Bloom's intensity while switched off, given back when it comes on. */
    private volatile float bloomIntensity = CgBloom.INTENSITY;
    /** The Rendering rows' reset marks and when each shows; checked every frame, since a harness key may move one. */
    private final List<UIElement> renderMarks = new ArrayList<>();
    private final List<BooleanSupplier> renderChanged = new ArrayList<>();
    @Nullable
    private ConfiguratorPanel params;
    @Nullable
    private volatile CgVfxRegistry.Entry selected;
    /** What the inspector edits, and the look's own values it resets to. */
    @Nullable
    private volatile CgVfxValues values, defaults;
    private volatile boolean looping, paused;
    private volatile float speed = 1f;
    /** Seconds of its own an effect plays before the lab lets go ({@link CgVfxEffect#stop}); 0 holds until Stop. */
    private volatile float hold = 4f;
    /** Metres along +x to the point on the floor effects aim at: where a beam hits and blasts. */
    private volatile float target = 30f;
    /** The lab's clock: the host's, scaled by the speed and held while paused. */
    private double labClock, hostClock = Double.NaN;
    private volatile int playing;
    private String message = "";
    private double messageUntil;

    public VfxLab() {
        super(NAME);

        UIElement toolbar = new UIElement();
        toolbar.addClass("lab-toolbar");
        toolbar.append(iconButton("lab-play", "Play", this::play), pause,
                iconButton("lab-stop", "Stop: let every effect end", () -> pending.add(this::stopAll)),
                iconButton("lab-restart", "Restart", () -> pending.add(this::restart)), separator(), loop, separator());
        UIElement speedCell = new UIElement();
        speedCell.addClass("lab-speed");
        ToolbarForm.into(speedCell).prop(ConfigDescriptor.number("speed", "Speed").unit("×").decimals(2).scrubRate(0.01),
                Property.derived(() -> (double) speed, v -> speed = (float) Math.max(0.0, Math.min(MAX_SPEED, v))));
        UIElement holdCell = new UIElement();
        holdCell.addClass("lab-speed");
        ToolbarForm.into(holdCell).prop(ConfigDescriptor.number("hold", "Hold").unit("s").decimals(1).scrubRate(0.05)
                        .tooltip("Seconds an effect plays before it is let go: a beam's blast. 0 holds until Stop"),
                Property.derived(() -> (double) hold, v -> hold = (float) Math.max(0.0, v)));
        UIElement targetCell = new UIElement();
        targetCell.addClass("lab-speed");
        ToolbarForm.into(targetCell).prop(ConfigDescriptor.number("target", "Target").unit("m").decimals(0).scrubRate(0.2)
                        .tooltip("How far along the floor effects aim: where a beam hits and blasts"),
                Property.derived(() -> (double) target, v -> target = (float) Math.max(1.0, v)));
        toolbar.append(speedCell, holdCell, targetCell, slack(),
                iconButton("lab-copy", "Copy the edited values as .set(...) lines", this::copy),
                iconButton("lab-clear", "Clear every effect", () -> pending.add(this::clearAll)), separator(),
                iconButton("lab-camera", "Reset camera", viewport::resetCamera));

        UIElement effects = new UIElement();
        effects.addClass("lab-effects");
        search.setPlaceholder("Search effects");
        // Shown blurred too: an empty box with no hint reads as nothing at all.
        search.field().setPlaceholderShownUnfocused(true);
        search.onQueryChanged.connect(this::refreshTree);
        tree.addClass("lab-tree");
        tree.setItemHeight(20f);
        tree.setRenderer(new EffectRows());
        tree.onSelectionChanged.connect(this::picked);
        // Double-click or Enter on an effect plays it from the start.
        tree.onRowActivated.connect(this::activated);
        effects.append(search, tree);

        inspector.addClass("lab-inspector");
        UIElement head = new UIElement();
        head.addClass("lab-head");
        title.addClass("lab-title");
        id.addClass("lab-id");
        UIElement names = new UIElement();
        names.addClass("lab-names");
        names.append(title, id);
        resetAll.addClass("lab-reset");
        resetAll.addClass("lab-reset-all");
        Tooltip.attach(resetAll, "Reset every value to the effect's own");
        resetAll.onMouseDown.attachListener((element, event) -> {
            resetEverything();
            event.stopPropagation();
        }, false, true);
        resetAll.setDisplayed(false);
        head.append(names, resetAll);
        inspector.append(head);

        TabView side = new TabView();
        side.addClass("lab-side");
        side.addTab("Effect").content().append(inspector);
        side.addTab("Rendering").content().append(renderingPanel());
        side.selectIndex(0);

        SplitView panes = new SplitView();
        panes.addClass("lab-panes");
        panes.addPane();
        panes.paneContent(0, effects).paneContent(1, viewport).paneContent(2, side);
        panes.setWeights(0.2f, 0.55f, 0.25f);
        panes.setPaneSizeLimits(0, 120f, 360f);
        panes.setPaneSizeLimits(2, 200f, 520f);

        UIElement status = new UIElement();
        status.addClass("lab-status");
        stateDot.addClass("lab-state-dot");
        messageText.addClass("lab-message");
        effectText.addClass("lab-status-effect");
        status.append(stateDot, stateText, statusSeparator(), countText, slack(), messageText, effectText,
                statusSeparator(), speedText);
        append(toolbar, rule(), panes, rule(), status);

        refreshTree();
        List<CgVfxRegistry.Entry> entries = CgVfxRegistry.entries();
        if (!entries.isEmpty()) select(entries.get(0));
        onConnected(() -> document().animation().every(this, delta -> {
            showStatus();
            for (int i = 0; i < renderMarks.size(); i++) renderMarks.get(i).setDisplayed(renderChanged.get(i).getAsBoolean());
            return true;
        }));
    }

    /** Shows {@code lab} in its window on {@code desktop}, or brings back the window already there. */
    public static WindowFrame openOn(Desktop desktop, VfxLab lab) {
        WindowFrame existing = desktop.registry().byKey(WINDOW_KEY);
        if (existing != null) {
            desktop.activate(existing, true);
            return existing;
        }
        WindowFrame frame = new WindowFrame("VFX Lab");
        frame.setKey(WINDOW_KEY);
        frame.setPolicy(WindowPolicy.HIDE_ON_CLOSE);
        frame.setContent(lab);
        desktop.addWindow(frame);
        // After addWindow, which places and sizes it.
        frame.resizeTo(960f, 560f);
        return frame;
    }

    /**
     * Runs what the panels asked, spawns again with Loop on once the last effect has died, then updates and submits
     * the effects and lab space's floor and sky. Once a frame on the render thread, from a
     * {@link CgWorldRenderer#onFrame} listener.
     */
    public void render(double seconds) {
        for (Runnable action; (action = pending.poll()) != null; ) action.run();
        live.removeIf(effect -> effect.state() == CgVfxEffect.State.DEAD);
        float letGo = hold;
        if (letGo > 0f) {
            for (CgVfxEffect effect : live) {
                if (effect.state() == CgVfxEffect.State.PLAYING && effect.age() >= letGo) effect.stop();
            }
        }
        if (live.isEmpty() && looping && !paused) spawn();
        double elapsed = Double.isNaN(hostClock) ? 0.0 : seconds - hostClock;
        if (!paused) labClock += elapsed * speed;
        hostClock = seconds;
        CgWorldRenderer world = CgWorldRenderer.get();
        vfx.update(labClock);
        vfx.submit(world);
        playing = live.size();
        if (floorMaterial == null) {
            floorMaterial = CgMaterial.newInstance(FLOOR_SHADER).applyProperties(b -> b.set1f("_Fade", FLOOR_FADE));
            skyMaterial = CgMaterial.newInstance(SKY_SHADER);
        }
        easeNight((float) elapsed);
        double x = viewport.eyeX(), z = viewport.eyeZ();
        // Under the eye, so flying never reaches its edge; the grid's lines are absolute and hold still.
        if (world.prepare(floorMaterial)) {
            world.draw(floor, floorMaterial).at(x, LAB_Y, z).layer(CgSortLayer.BACKGROUND).submit();
        }
        // Round the eye.
        if (world.prepare(skyMaterial)) {
            world.draw(sky, skyMaterial).at(x, LAB_Y + 1.5 + viewport.eyeY(), z).transform(SKY_SCALE)
                    .layer(CgSortLayer.BACKGROUND).submit();
        }
    }

    /** Moves the sky and floor a step toward day or night: the whole change in about 0.4 s. */
    private void easeNight(float elapsed) {
        float to = night ? 1f : 0f;
        if (nightMix == to || floorMaterial == null || skyMaterial == null) return;
        float step = elapsed * 2.5f;
        nightMix = Math.abs(to - nightMix) <= step ? to : nightMix + Math.signum(to - nightMix) * step;
        float mix = nightMix;
        floorMaterial.applyProperties(b -> b.set1f("_Night", mix));
        skyMaterial.applyProperties(b -> b.set1f("_Night", mix));
    }

    /**
     * Draws the viewport, on the render thread after the frame's world firing; {@code width} x {@code height} is that
     * firing's size. Nothing while the window is hidden.
     */
    public void drawViewport(int width, int height) {
        if (viewport.box() == null) return;
        viewport.draw(width, height, 0.0, LAB_Y + 1.5, 0.0);
    }

    /** Releases the effects' meshes and the viewport's target. On the render thread, when the lab closes for good. */
    public void delete() {
        vfx.delete();
        viewport.release();
    }

    // ── Playback, on the render thread ──────────────────────────────────────

    private void spawn() {
        CgVfxRegistry.Entry entry = selected;
        CgVfxValues edited = values;
        if (entry == null || edited == null) return;
        CgVfxEffect effect = entry.spawn(entry.look(), 0.0, LAB_Y + 1.5, 0.0, target, LAB_Y, 0.0);
        effect.ground(LAB_Y);
        for (CgVfxParam param : edited.schema().params()) apply(effect, edited, param);
        if (effect instanceof CgEnergyWave wave) CgVfxDemoControls.get().apply(wave);
        live.add(vfx.play(effect));
    }

    private void restart() {
        clearAll();
        spawn();
    }

    private void stopAll() {
        for (CgVfxEffect effect : live) effect.stop();
    }

    private void clearAll() {
        for (CgVfxEffect effect : live) effect.kill();
        live.clear();
    }

    private static void apply(CgVfxEffect effect, CgVfxValues edited, CgVfxParam param) {
        if (param.size() == 1) {
            effect.set(param, edited.get(param));
        } else if (param.size() == 4) {
            effect.set(param, edited.get(param, 0), edited.get(param, 1), edited.get(param, 2), edited.get(param, 3));
        }
    }

    // ── Toolbar and status ──────────────────────────────────────────────────

    /** Resumes when paused, spawning only if nothing is left to resume; otherwise spawns another. */
    private void play() {
        boolean resumed = paused;
        if (resumed) togglePause();
        pending.add(() -> {
            if (!resumed || live.isEmpty()) spawn();
        });
    }

    private void togglePause() {
        paused = !paused;
        pause.toggleClass("lab-on", paused);
    }

    private void toggleLoop() {
        looping = !looping;
        loop.toggleClass("lab-on", looping);
    }

    private void showStatus() {
        int count = playing;
        boolean live = count > 0 && !paused;
        stateDot.toggleClass("lab-live", live);
        stateDot.toggleClass("lab-held", paused);
        stateText.setText(paused ? "Paused" : count > 0 ? "Playing" : "Idle");
        countText.setText(count == 1 ? "1 effect" : count + " effects");
        boolean telling = System.nanoTime() / 1e9 < messageUntil;
        messageText.setDisplayed(telling);
        if (telling) messageText.setText(message);
        CgVfxRegistry.Entry entry = selected;
        effectText.setText(entry == null ? "" : entry.name());
        speedText.setText(String.format(Locale.ROOT, "%.2f×", speed));
    }

    private void tell(String text) {
        message = text;
        messageUntil = System.nanoTime() / 1e9 + MESSAGE_SECONDS;
    }

    private void copy() {
        CgVfxValues edited = values;
        if (edited == null) return;
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (CgVfxParam param : edited.schema().params()) {
            if (param.size() == 1) {
                out.append(".set(").append(param.name()).append(", ").append(format(edited.get(param))).append("f)\n");
            } else if (param.size() == 4) {
                out.append(".set(").append(param.name());
                for (int c = 0; c < 4; c++) out.append(", ").append(format(edited.get(param, c))).append('f');
                out.append(")\n");
            } else {
                continue;
            }
            count++;
        }
        PlatformPort.current().setClipboard(out.toString());
        tell("Copied " + count + " values");
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    /** The run's render switches; its rows poll while shown, so a harness key's change shows too. */
    private ConfiguratorPanel renderingPanel() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.addClass("lab-params");
        CgWorldRenderer world = CgWorldRenderer.get();
        CgBloom bloom = CgPostStack.get().bloom();
        CgVfxDemoControls controls = CgVfxDemoControls.get();

        ConfigForm environment = panel.form().group("Environment");
        environment.prop(ConfigDescriptor.select("sky", "Sky", List.of("Day", "Night"))
                        .tooltip("Lab space's sky and floor: a bright day, or a starry night that lets glows read"),
                Property.derived(() -> night ? "Night" : "Day", v -> night = "Night".equals(v)));

        ConfigForm post = panel.form().group("Post processing");
        post.prop(ConfigDescriptor.bool("hdr", "HDR scene")
                        .tooltip("Effects drawn into a linear 16-bit scene and composited back"),
                Property.derived(() -> world.hdrScene(), on -> pending.add(() -> world.hdrScene(on))));
        Configurator glow = post.prop(ConfigDescriptor.number("glow", "Glow").range(0f, 20f).decimals(2)
                        .tooltip("How much a glowing layer adds into the HDR scene"),
                Property.derived(() -> (double) world.sceneEmission(),
                        v -> pending.add(() -> world.sceneEmission((float) (double) v))));
        resetTo(glow, () -> world.sceneEmission() != CgWorldRenderer.SCENE_EMISSION,
                () -> world.sceneEmission(CgWorldRenderer.SCENE_EMISSION));
        post.prop(ConfigDescriptor.bool("bloom", "Bloom"), Property.derived(() -> bloom.intensity() > 0f, on -> pending.add(() -> {
            if (!on && bloom.intensity() > 0f) bloomIntensity = bloom.intensity();
            bloom.intensity(on ? bloomIntensity : 0f);
        })));
        Configurator intensity = post.prop(ConfigDescriptor.number("bloomIntensity", "Intensity").range(0f, 20f).decimals(2),
                Property.derived(() -> (double) (bloom.intensity() > 0f ? bloom.intensity() : bloomIntensity), v -> pending.add(() -> {
                    // Never 0: off is the switch above's.
                    bloomIntensity = (float) Math.max(0.01, v);
                    if (bloom.intensity() > 0f) bloom.intensity(bloomIntensity);
                })));
        resetTo(intensity, () -> (bloom.intensity() > 0f ? bloom.intensity() : bloomIntensity) != CgBloom.INTENSITY, () -> {
            bloomIntensity = CgBloom.INTENSITY;
            if (bloom.intensity() > 0f) bloom.intensity(CgBloom.INTENSITY);
        });
        post.prop(ConfigDescriptor.select("bloomBlend", "Blend", List.of("Display", "Linear"))
                        .tooltip("Display: added in the target's encoding. Linear: in linear light"),
                Property.derived(() -> bloom.linear() ? "Linear" : "Display",
                        v -> pending.add(() -> bloom.linear("Linear".equals(v)))));

        ConfigForm impact = panel.form().group("Impact");
        impact.prop(ConfigDescriptor.select("flash", "Flash", List.of("Double", "Single", "Off"))
                        .tooltip("The blast's flash: the wave's own double pulse, one pulse, or none"),
                Property.derived(() -> label(controls.flash().name().toLowerCase(Locale.ROOT)), v -> pending.add(
                        () -> controls.flash(CgVfxDemoControls.Flash.valueOf(v.toUpperCase(Locale.ROOT))))));
        impact.prop(ConfigDescriptor.bool("impactFrame", "Impact frame")
                        .tooltip("The inked beats and hitstop before the blast bursts"),
                Property.derived(() -> controls.impact(), on -> pending.add(() -> controls.impact(on))));
        impact.prop(ConfigDescriptor.bool("billows", "Smoke clouds")
                        .tooltip("Off keeps them simulated but undrawn, so the dome shows whole"),
                Property.derived(() -> controls.billows(), on -> pending.add(() -> controls.billows(on))));

        return panel;
    }

    /** A reset arrow on a Rendering row, shown while {@code changed}; {@code reset} runs on the render thread. */
    private void resetTo(Configurator row, BooleanSupplier changed, Runnable reset) {
        UIElement mark = new UIElement();
        mark.addClass("lab-reset");
        Tooltip.attach(mark, "Reset to the engine's default");
        mark.onMouseDown.attachListener((element, event) -> {
            pending.add(reset);
            event.stopPropagation();
        }, false, true);
        row.inline().append(mark);
        mark.setDisplayed(changed.getAsBoolean());
        renderMarks.add(mark);
        renderChanged.add(changed);
    }

    // ── The effect tree ─────────────────────────────────────────────────────

    /** Categories, then their effects, in registration order; filtered by the search. */
    private final class Effects implements TreeDataSource<Object> {
        @Override
        public List<Object> roots() {
            List<Object> categories = new ArrayList<>();
            for (CgVfxRegistry.Entry entry : CgVfxRegistry.entries()) {
                if (matches(entry) && !categories.contains(entry.category())) categories.add(entry.category());
            }
            return categories;
        }

        @Override
        public List<Object> children(Object parent) {
            List<Object> entries = new ArrayList<>();
            for (CgVfxRegistry.Entry entry : CgVfxRegistry.entries()) {
                if (entry.category().equals(parent) && matches(entry)) entries.add(entry);
            }
            return entries;
        }

        @Override
        public boolean hasChildren(Object item) {
            return item instanceof String;
        }

        private boolean matches(CgVfxRegistry.Entry entry) {
            String query = search.getText().toLowerCase(Locale.ROOT);
            return query.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(query) || entry.id().contains(query)
                    || entry.category().toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private static final class EffectRow extends UIElement {
        final UIElement twisty = new UIElement();
        final UIText label = new UIText("");

        EffectRow() {
            addClass("lab-tree-row");
            twisty.addClass("__twisty__");
            label.addClass("lab-tree-label");
            append(twisty, label);
        }
    }

    private static final class EffectRows implements TreeRenderer<Object> {
        @Override
        public UIElement createTemplate() {
            return new EffectRow();
        }

        @Override
        public void bind(Object item, TreeRow<Object> row, int index, UIElement template) {
            EffectRow view = (EffectRow) template;
            boolean category = item instanceof String;
            view.label.setText(category ? (String) item : ((CgVfxRegistry.Entry) item).name());
            view.toggleClass("lab-category", category);
        }
    }

    private void refreshTree() {
        tree.refresh();
        // Every category open: the list is short, and a search should show what it found.
        tree.setExpandedItems(new ArrayList<>(tree.roots()));
    }

    private void picked(Set<Integer> indices) {
        if (indices.isEmpty()) return;
        Object item = tree.rowAt(indices.iterator().next()).item();
        if (item instanceof CgVfxRegistry.Entry entry && entry != selected) select(entry);
    }

    private void activated(@Nullable Integer index) {
        if (index == null) return;
        TreeRow<Object> row = tree.rowAt(index);
        if (row == null || !(row.item() instanceof CgVfxRegistry.Entry entry)) return;
        if (entry != selected) select(entry);
        if (paused) togglePause();
        pending.add(this::restart);
    }

    // ── The inspector ───────────────────────────────────────────────────────

    private void select(CgVfxRegistry.Entry entry) {
        CgVfxValues edited = entry.look().copyValues();
        selected = entry;
        values = edited;
        defaults = entry.look().copyValues();
        pending.add(this::clearAll);
        title.setText(entry.name());
        id.setText(entry.id());

        // A new panel per effect: its rows carry reset marks no refill would take off.
        if (params != null) inspector.remove(params);
        markedParams.clear();
        marks.clear();
        resetAll.setDisplayed(false);
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.addClass("lab-params");
        params = panel;
        inspector.append(panel);

        ConfigForm form = panel.form();
        ConfigForm colours = form.group("Colours");
        ConfigForm numbers = form.group("Values");
        ConfigForm curves = form.group("Curves", true);
        ConfigForm shakes = form.group("Camera shakes", true);
        for (CgVfxParam param : edited.schema().params()) {
            if (param.size() == 4) {
                colour(colours, edited, param);
            } else if (param.size() == 1) {
                number(numbers, edited, param);
            } else {
                (param.isCurve() ? curves : shakes).prop(ConfigDescriptor.info(param.name(), label(param.name())),
                        Property.of(param.isCurve() ? "Curve" : "Shake"));
            }
        }
    }

    private void number(ConfigForm form, CgVfxValues edited, CgVfxParam param) {
        float start = edited.get(param);
        UIElement reset = new UIElement();
        Configurator row = form.prop(ConfigDescriptor.number(param.name(), label(param.name())).decimals(3)
                        .scrubRate(Math.max(0.001, Math.abs(start) * 0.005)),
                Property.derived(() -> (double) edited.get(param), v -> {
                    edited.set(param, (float) (double) v);
                    edited(param, reset);
                }));
        withReset(row, reset, param);
    }

    /** One row: the swatch, its intensity beside it, and the reset mark. */
    private void colour(ConfigForm form, CgVfxValues edited, CgVfxParam param) {
        UIElement reset = new UIElement();
        Configurator row = form.prop(ConfigDescriptor.color(param.name(), label(param.name())),
                Property.derived(() -> swatch(edited, param), argb -> {
                    float k = intensity(edited, param);
                    edited.set(param, ((argb >> 16) & 0xFF) / 255f * k, ((argb >> 8) & 0xFF) / 255f * k,
                            (argb & 0xFF) / 255f * k, (argb >>> 24) / 255f);
                    edited(param, reset);
                }));
        NumberControl intensity = new NumberControl(ConfigDescriptor.number(param.name() + ".intensity", "Intensity")
                .tooltip("Intensity: the swatch's brightest channel").decimals(2).scrubRate(0.01), 1.0);
        intensity.addClass("lab-intensity");
        intensity.bind(Property.derived(() -> (double) intensity(edited, param), v -> {
            float from = intensity(edited, param), to = (float) Math.max(0.0, v);
            edited.set(param, edited.get(param, 0) / from * to, edited.get(param, 1) / from * to,
                    edited.get(param, 2) / from * to, edited.get(param, 3));
            edited(param, reset);
        }));
        row.inline().append(intensity);
        withReset(row, reset, param);
    }

    private void withReset(Configurator row, UIElement reset, CgVfxParam param) {
        reset.addClass("lab-reset");
        reset.onMouseDown.attachListener((element, event) -> {
            CgVfxValues edited = values, original = defaults;
            if (edited == null || original == null) return;
            if (param.size() == 1) {
                edited.set(param, original.get(param));
            } else {
                edited.set(param, original.get(param, 0), original.get(param, 1), original.get(param, 2),
                        original.get(param, 3));
            }
            edited(param, reset);
            event.stopPropagation();
        }, false, true);
        row.inline().append(reset);
        reset.setDisplayed(false);
        markedParams.add(param);
        marks.add(reset);
    }

    private void resetEverything() {
        CgVfxValues edited = values, original = defaults;
        if (edited == null || original == null) return;
        for (int i = 0; i < markedParams.size(); i++) {
            CgVfxParam param = markedParams.get(i);
            if (param.size() == 1) {
                edited.set(param, original.get(param));
            } else {
                edited.set(param, original.get(param, 0), original.get(param, 1), original.get(param, 2),
                        original.get(param, 3));
            }
            edited(param, marks.get(i));
        }
    }

    /** After an edit: the mark shows while the value differs from the look's, and playing effects take it. */
    private void edited(CgVfxParam param, UIElement reset) {
        CgVfxValues edited = values, original = defaults;
        if (edited == null || original == null) return;
        boolean changed = false;
        for (int c = 0; c < param.size(); c++) changed |= edited.get(param, c) != original.get(param, c);
        reset.setDisplayed(changed);
        boolean any = false;
        for (UIElement mark : marks) any |= mark.isDisplayed();
        resetAll.setDisplayed(any);
        pending.add(() -> {
            for (CgVfxEffect effect : live) apply(effect, edited, param);
        });
    }

    private static float intensity(CgVfxValues values, CgVfxParam param) {
        return Math.max(1f, Math.max(values.get(param, 0), Math.max(values.get(param, 1), values.get(param, 2))));
    }

    private static int swatch(CgVfxValues values, CgVfxParam param) {
        float k = intensity(values, param);
        return channel(values.get(param, 3)) << 24 | channel(values.get(param, 0) / k) << 16
                | channel(values.get(param, 1) / k) << 8 | channel(values.get(param, 2) / k);
    }

    private static int channel(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    /** {@code blastSparkCore} as {@code Blast spark core}. */
    private static String label(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                out.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                out.append(' ').append(Character.toLowerCase(c));
            } else {
                out.append(c == '_' ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static UIElement separator() {
        UIElement line = new UIElement();
        line.addClass("lab-separator");
        return line;
    }

    /** A toolbar button drawn as its icon alone, {@code kind} naming the icon in the sheet. */
    private static Button iconButton(String kind, String tooltip, Runnable action) {
        Button button = new Button();
        button.addClass("lab-icon");
        button.addClass(kind);
        Tooltip.attach(button, tooltip);
        button.attachListener(action);
        return button;
    }

    private static UIElement slack() {
        UIElement slack = new UIElement();
        slack.addClass("lab-slack");
        return slack;
    }

    /** A hairline across the lab: an element, since a one-edge border draws nothing here. */
    private static UIElement rule() {
        UIElement rule = new UIElement();
        rule.addClass("lab-rule");
        return rule;
    }

    private static UIElement statusSeparator() {
        UIElement line = new UIElement();
        line.addClass("lab-status-sep");
        return line;
    }
}
