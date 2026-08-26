package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.texture.CgUiGlass;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.MouseEvent;
import dev.vfyjxf.taffy.style.TaffyDimension;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A live tuner for the taskbar: its geometry, its position, and every parameter of its backdrop.
 *
 * <p>This is a DESIGN TOOL, not a settings screen. Its output is not a saved preference but a line of
 * CSS — the <em>Copy CSS</em> button puts a paste-ready {@code taskbar { ... }} block on the
 * clipboard, so the loop from "drag a slider until it looks right" to "commit the value" has no
 * transcription step in it. A tuner whose numbers have to be copied down by hand is a tuner whose numbers
 * get copied down wrong.</p>
 *
 * <h3>Why it writes at IMPORTANT origin, and owns its own drawable</h3>
 *
 * <p>The island's look comes from {@code ua/desktop.css}, so every write here has to outrank a stylesheet
 * — that is what {@link StyleGroup#importantPipeline} is for. The backdrop is the same problem one level
 * down: the cascade owns the {@link CgUiGlass} that {@code glass(...)} parsed, and mutating someone
 * else's instance works only for as long as nothing re-resolves it. So the designer installs a glass of
 * its own, seeded from whatever the sheet had, and mutates that.</p>
 *
 * <h3>Position is a TRANSFORM, deliberately</h3>
 *
 * <p>Dragging the island moves it with {@link UIElement#setTransform}, which Taffy never sees. That is
 * not a shortcut, it is the only option that does not break the compositor: the taskbar is <b>laid out</b>
 * as a bottom bar precisely so the window layer's box IS the work area, and a strip that could be
 * absolutely positioned anywhere would take that derivation — and every maximised window with it. A
 * transform lets the island be dragged anywhere on screen while the strip it lives in stays exactly where
 * the work-area maths needs it.</p>
 *
 * <p>Height and width are the opposite case and DO write layout, because a taller taskbar genuinely
 * should leave less work area — that is the thing being designed.</p>
 */
public final class TaskbarDesigner {

    /** Class on the designer's own window, so a theme can style it out of the way. */
    public static final String WINDOW_CLASS = "__taskbar-designer__";

    private final Taskbar taskbar;
    private final UIElement island;
    private final CgUiGlass glass = new CgUiGlass();

    // Geometry, in logical px. Seeded from the sheet on the first frame the island has a box.
    private float islandWidth, islandHeight, radius = 8f, padding = 4f, gap = 4f;
    private float offsetX, offsetY;
    private boolean widthAuto = true;

    /**
     * The sheet's own {@code min-width} on the island, kept so "Width follows content" can hand it back.
     *
     * <p><b>A floor beats a width, so the designer has to own both.</b> {@code min-width} is a DIFFERENT
     * property from {@code width}, and `taskbar .__entries__` carries {@code min-width: 380px} to hide
     * the hotbar -- so an IMPORTANT {@code width} below that resolved to 380 and the Width slider was
     * dead across the whole 80..380 third of its travel. It moved, the readout changed, nothing did.
     * Worse, the emitted rule had the same problem in the sheet it was pasted into, four lines under the
     * floor that would go on beating it.</p>
     *
     * <p>Captured as a {@code TaffyDimension} rather than a float so the value goes back exactly as the
     * cascade gave it, and so no pixel number from the stylesheet is retyped in Java.</p>
     */
    private @Nullable TaffyDimension autoMinWidth;

    private final List<Runnable> resets = new ArrayList<>();
    private @Nullable UIText readout;

    private TaskbarDesigner(Taskbar taskbar) {
        this.taskbar = taskbar;
        // THE BAR, not the entries row. Since the strip became a full-width bar the glass, the height, the
        // padding and the radius are all the bar's own, and the entries row is a transparent flex row
        // inside it with nothing to tune; a designer aimed at the row would move sliders and change
        // nothing on screen. "island" survives as the field's name because every rule it drives still
        // reads as the thing the designer designs.
        this.island = taskbar;
    }

    /**
     * Opens the tuner for {@code window}'s taskbar, or returns {@code null} if it has no desktop yet.
     *
     * <p>Re-opening is not guarded: a second designer would fight the first for the same IMPORTANT slots,
     * so the caller closes any existing one. {@link DesktopCommands} does that by key.</p>
     */
    public static @Nullable WindowFrame open(UIWindow window) {
        if (window == null) return null;
        Desktop desktop = window.desktop();
        if (desktop == null) return null;
        Taskbar taskbar = desktop.taskbar();
        if (taskbar == null) return null;

        TaskbarDesigner designer = new TaskbarDesigner(taskbar);
        WindowFrame frame = new WindowFrame("Taskbar Designer");
        frame.addClass(WINDOW_CLASS);
        frame.setContent(designer.build());
        frame.setKey("taskbar-designer");
        // A SIZE, IN JAVA, and this is the exception to "no sizes in widgets" rather than a lapse: a
        // window's geometry is written by the compositor at IMPORTANT origin, so a stylesheet cannot
        // reach it. Without one the frame sizes to its content -- and its content is a column of
        // flex-grow children, which divide a height of zero. It came up 545x48 with every row correctly
        // laid out inside a body measuring 529x0: not a layout fault in the panel, a window with no box
        // for the panel to fill.
        designer.enableIslandDrag();
        window.openWindow(frame);
        // AFTER openWindow, not before: adding a window is what places and sizes it, so a size written
        // first is simply overwritten. @see Desktop#placeByCascade
        // CLAMPED TO THE WORK AREA, not a fixed size. 640 logical is 1280 physical at the default
        // uiScale of 2, against a work area 540 logical tall -- so the first version opened a window
        // taller than the desktop it was on and pushed its own buttons off the bottom of the screen.
        float maxH = desktop.getRuntimeCache().getHeight();
        frame.resizeTo(400f, maxH > 0f ? Math.min(460f, maxH - 60f) : 460f);
        return frame;
    }

    // ── the panel ────────────────────────────────────────────────────────────────────────────────

    private UIElement build() {
        seedFromCascade();

        UIElement content = new UIElement();
        content.addClass("__designer__");

        // A ScrollerView, FOR THE BARS. This was a plain element on the argument that scrolling is an
        // ordinary element capability driven by `overflow`, and that a tuning panel does not need
        // visible bars. Both halves are true and the conclusion was wrong: the wheel worked the whole
        // time, and what was missing was any indication that there was more panel below the fold.
        //
        // A scrollable region with no bar does not read as scrollable -- it reads as ENDING where it is
        // cut off, so every control past the frame's height was undiscoverable rather than merely out
        // of view. This window is the case that exposes it, because it opens clamped to the work area
        // (see open()), so on any short desktop it is scrolled from the moment it appears and there has
        // never been a first frame that showed the whole panel.
        //
        // ScrollerView is a drop-in here: it IS the viewport, children are direct children, and the
        // `overflow: auto` already on `.__designer-body__` is what decides that a bar appears only on
        // an axis that actually overflows.
        ScrollerView body = new ScrollerView();
        body.addClass("__designer-body__");

        body.addChild(heading("Shape"));
        body.addChild(toggle("Width follows content", widthAuto, on -> {
            widthAuto = on;
            applyGeometry();
        }));
        body.addChild(slider("Width", 80f, 1600f, islandWidth, "%.0f", v -> {
            islandWidth = v;
            widthAuto = false;
            applyGeometry();
        }));
        body.addChild(slider("Height", 12f, 96f, islandHeight, "%.0f", v -> {
            islandHeight = v;
            applyGeometry();
        }));
        body.addChild(slider("Corner radius", 0f, 48f, radius, "%.0f", v -> {
            radius = v;
            applyGeometry();
        }));
        body.addChild(slider("Padding", 0f, 32f, padding, "%.0f", v -> {
            padding = v;
            applyGeometry();
        }));
        body.addChild(slider("Gap", 0f, 32f, gap, "%.0f", v -> {
            gap = v;
            applyGeometry();
        }));

        body.addChild(heading("Position"));
        body.addChild(note("Right-drag the island itself, or nudge it here."));
        body.addChild(slider("Offset X", -1200f, 1200f, offsetX, "%.0f", v -> {
            offsetX = v;
            applyOffset();
        }));
        body.addChild(slider("Offset Y", -900f, 900f, offsetY, "%.0f", v -> {
            offsetY = v;
            applyOffset();
        }));

        body.addChild(heading("Backdrop"));
        body.addChild(slider("Blur", 0f, 40f, glass.getBlurRadius(), "%.0f", v -> {
            glass.setBlurRadius(v);
            refreshReadout();
        }));
        body.addChild(slider("Bezel", 0f, 40f, glass.getBezel(), "%.0f", v -> {
            glass.setBezel(v);
            refreshReadout();
        }));
        body.addChild(slider("Index of refraction", 1f, 2.5f, glass.getIor(), "%.2f", v -> {
            glass.setIor(v);
            refreshReadout();
        }));
        body.addChild(slider("Specular", 0f, 1.5f, glass.getSpecular(), "%.2f", v -> {
            glass.setSpecular(v);
            refreshReadout();
        }));
        body.addChild(note("Rim is the hairline at the boundary; glow is the broad falloff. "
                + "The rim should dominate \u2014 a highlight made mostly of glow reads as bloom."));
        body.addChild(slider("Rim", 0f, 1f, glass.getEdgeHighlight(), "%.2f", v -> {
            glass.setEdgeHighlight(v);
            refreshReadout();
        }));
        body.addChild(slider("Rim width", 0f, 12f, glass.getEdgeWidth(), "%.1f", v -> {
            glass.setEdgeWidth(v);
            refreshReadout();
        }));
        body.addChild(slider("Rim evenness", 0f, 1f, glass.getRimAmbient(), "%.2f", v -> {
            glass.setRimAmbient(v);
            refreshReadout();
        }));
        body.addChild(slider("Glow", 0f, 1f, glass.getGlow(), "%.2f", v -> {
            glass.setGlow(v);
            refreshReadout();
        }));
        body.addChild(slider("Chromatic", 0f, 1f, glass.getChromatic(), "%.2f", v -> {
            glass.setChromatic(v);
            refreshReadout();
        }));
        body.addChild(slider("Noise", 0f, 0.25f, glass.getNoise(), "%.3f", v -> {
            glass.setNoise(v);
            refreshReadout();
        }));
        body.addChild(slider("Saturation", 0f, 3f, glass.getSaturation(), "%.2f", v -> {
            glass.setSaturation(v);
            refreshReadout();
        }));

        body.addChild(heading("Tint"));
        body.addChild(note("The colour laid over the blur. ALPHA IS THE ONE THAT MATTERS \u2014 it is how "
                + "much of the tint sits over the backdrop, and the easiest thing here to overdo."));
        body.addChild(tintPicker());

        // THE READOUT SCROLLS WITH THE CONTROLS; only the buttons are pinned. It is eight lines of CSS
        // and it grows, so below the scroll region it simply fell off the bottom of the window, taking
        // Copy CSS with it whenever the panel was short.
        readout = new UIText("");
        readout.addClass("__designer-readout__");
        body.addChild(heading("CSS"));
        body.addChild(readout);

        content.addChild(body);
        content.addChild(actions());
        refreshReadout();
        return content;
    }

    private UIElement actions() {
        UIElement row = new UIElement();
        row.addClass("__designer-actions__");

        Button copy = new Button("Copy CSS");
        copy.onPressed.connect(() -> {
            CgPlatform.input().setClipboard(css());
            CrystalGuiCore.LOGGER.info("Taskbar CSS copied to the clipboard:\n{}", css());
        });
        row.addChild(copy);

        Button reset = new Button("Reset");
        reset.onPressed.connect(() -> {
            for (Runnable r : resets) r.run();
            refreshReadout();
        });
        row.addChild(reset);
        return row;
    }

    // ── applying ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the sheet's current values so the tuner opens on what is actually on screen.
     *
     * <p>Starting from hardcoded defaults instead would be the same class of lie the "copy CSS by hand"
     * step is: the first drag of any slider would snap the taskbar to a look nobody chose.</p>
     */
    private void seedFromCascade() {
        Object background = island.getStyle().getComputed(StylePropertyRegistry.BACKGROUND);
        if (background instanceof CgUiGlass live) {
            glass.setBlurRadius(live.getBlurRadius()).setTint(live.getTint())
                 .setSaturation(live.getSaturation()).setBezel(live.getBezel())
                 .setIor(live.getIor()).setSpecular(live.getSpecular())
                 .setNoise(live.getNoise()).setFallbackColor(live.getFallbackColor())
                 .setGlow(live.getGlow()).setEdgeHighlight(live.getEdgeHighlight())
                 .setEdgeWidth(live.getEdgeWidth()).setChromatic(live.getChromatic())
                 .setRimAmbient(live.getRimAmbient());
        }
        // BEFORE the IMPORTANT writes below, or this reads back the designer's own value.
        if (island.getStyle().getComputed(LayoutProperties.MIN_WIDTH) instanceof TaffyDimension floor) {
            autoMinWidth = floor;
        }
        islandWidth = Math.max(80f, island.getRuntimeCache().getWidth());
        islandHeight = Math.max(12f, island.getRuntimeCache().getHeight());
        StyleGroup.importantPipeline(island.getStyle().getGeneralGroup(), g -> g.background(glass));
        applyGeometry();
    }

    private void applyGeometry() {
        StyleGroup.importantPipeline(island.getStyle().getLayoutGroup(), l -> {
            // MIN-WIDTH TRAVELS WITH WIDTH. @see #autoMinWidth -- the sheet's floor outranks a width at
            // any origin, being a different property, so the slider is only authoritative if it writes
            // both. Auto mode hands the sheet's own floor back rather than releasing it to zero: "follows
            // content" should mean the shipped taskbar, and the floor is what keeps the strip wide enough
            // to cover the hotbar.
            if (widthAuto) {
                l.widthAuto();
                if (autoMinWidth != null) l.setMinWidth(autoMinWidth);
            } else {
                l.width(islandWidth).minWidth(islandWidth);
            }
            l.height(islandHeight);
            l.paddingAll(padding);
            l.gapAll(gap);
        });
        StyleGroup.importantPipeline(island.getStyle().getGeneralGroup(),
                g -> g.borderRadius(radius));
        refreshReadout();
    }

    /** @see TaskbarDesigner class javadoc — a transform, so the work area never moves. */
    private void applyOffset() {
        island.setTransform(offsetX == 0f && offsetY == 0f
                ? UITransform.IDENTITY : UITransform.translate(offsetX, offsetY));
        refreshReadout();
    }

    /**
     * Drag the island itself to move it.
     *
     * <p>On the CAPTURE phase, because the island is full of taskbar entries and every one of them
     * consumes a press — the same reason Alt-drag on a window frame captures rather than bubbles. It
     * takes the RIGHT button so an ordinary left click still activates the window it landed on: a tuner
     * that stops the thing it is tuning from working is not showing you the thing you are tuning.</p>
     */
    private void enableIslandDrag() {
        island.onMouseDown.attachListener((self, event) -> {
            if (event.getButtonId() != 1) return;
            UIWindow window = island.getAttachedWindow();
            if (window == null) return;
            float startX = offsetX, startY = offsetY;
            window.getInputHandler().getDragController().startDrag(
                    island, event.getPosition().x(), event.getPosition().y(), 1,
                    (mouseX, mouseY, sx, sy, deltaX, deltaY) -> {
                        offsetX = startX + deltaX;
                        offsetY = startY + deltaY;
                        applyOffset();
                    });
            event.stopPropagation();
        }, true, false);
    }

    // ── output ───────────────────────────────────────────────────────────────────────────────────

    /** The tuned state as a paste-ready rule for {@code ua/desktop.css}. */
    private String css() {
        StringBuilder sb = new StringBuilder("taskbar {\n");
        if (!widthAuto) {
            sb.append(String.format(Locale.ROOT, "    width: %.0fpx;%n", islandWidth));
            // NOT OPTIONAL: a floor beats a width, being a different property, so a pasted width has
            // to bring its own floor or whatever `min-width` the sheet carries goes on winning.
            sb.append(String.format(Locale.ROOT, "    min-width: %.0fpx;%n", islandWidth));
        }
        sb.append(String.format(Locale.ROOT, "    height: %.0fpx;%n", islandHeight));
        sb.append(String.format(Locale.ROOT, "    padding-all: %.0fpx;%n", padding));
        sb.append(String.format(Locale.ROOT, "    gap-all: %.0fpx;%n", gap));
        sb.append(String.format(Locale.ROOT, "    border-radius: %.0fpx;%n", radius));
        sb.append(String.format(Locale.ROOT,
                "    background: glass(blur %.0f, tint %s,%n"
                + "                      bezel %.0f, ior %.2f, specular %.2f, noise %.3f,%n"
                + "                      saturation %.2f, glow %.2f, edge %.2f, edge-width %.1f,%n"
                + "                      rim-ambient %.2f, chromatic %.2f, fallback %s);%n",
                glass.getBlurRadius(), hex(glass.getTint()), glass.getBezel(), glass.getIor(),
                glass.getSpecular(), glass.getNoise(), glass.getSaturation(),
                glass.getGlow(), glass.getEdgeHighlight(), glass.getEdgeWidth(),
                glass.getRimAmbient(), glass.getChromatic(), hex(glass.getFallbackColor())));
        sb.append("}\n");
        if (offsetX != 0f || offsetY != 0f) {
            sb.append(String.format(Locale.ROOT,
                    "/* dragged to %.0f, %.0f — a transform, NOT a position. A real move means "
                    + "re-docking the strip,%n   which changes the work area. */%n", offsetX, offsetY));
        }
        return sb.toString();
    }

    private void refreshReadout() {
        if (readout != null) readout.setText(css());
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    // ── control builders ─────────────────────────────────────────────────────────────────────────

    /**
     * The tint, as a real picker rather than four channel sliders.
     *
     * <p><b>Alpha lives in the colour</b>, which is why {@link ColorSelector} is the right widget and not
     * an approximation of one: it carries ARGB throughout and composites its swatches over a transparency
     * checkerboard, so a half-alpha tint <em>reads</em> as half-alpha while you are choosing it. Four
     * sliders can express the same number and cannot show you that, and this is a value judged by eye —
     * the whole reason the panel exists.</p>
     */
    private UIElement tintPicker() {
        int initial = glass.getTint();
        ColorSelector picker = new ColorSelector();
        picker.addClass("__designer-tint__");
        picker.setColor(initial);
        picker.onColorChanged.connect(argb -> {
            glass.setTint(argb);
            refreshReadout();
        });
        resets.add(() -> picker.setColor(initial));
        return picker;
    }

    private UIElement slider(String label, float min, float max, float initial,
                             String format, Consumer<Float> apply) {
        UIElement row = new UIElement();
        row.addClass("__designer-row__");

        UIText name = new UIText(label);
        name.addClass("__designer-label__");
        UIText value = new UIText(String.format(Locale.ROOT, format, initial));
        value.addClass("__designer-value__");

        Slider slider = new Slider();
        slider.addClass("__designer-slider__");
        slider.setRange(min, max).setValue(initial);
        slider.onValueChanged.connect(v -> {
            apply.accept(v);
            value.setText(String.format(Locale.ROOT, format, v));
        });
        resets.add(() -> slider.setValue(initial));

        row.addChild(name);
        row.addChild(slider);
        row.addChild(value);
        return row;
    }

    private UIElement toggle(String label, boolean initial, Consumer<Boolean> apply) {
        UIElement row = new UIElement();
        row.addClass("__designer-row__");
        com.crystalgui.ui.elements.Checkbox box = new com.crystalgui.ui.elements.Checkbox();
        box.setChecked(initial);
        box.onCheckedChanged.connect(apply::accept);
        UIText name = new UIText(label);
        name.addClass("__designer-label__");
        resets.add(() -> box.setChecked(initial));
        row.addChild(box);
        row.addChild(name);
        return row;
    }

    private static UIElement heading(String text) {
        UIText t = new UIText(text);
        t.addClass("__designer-heading__");
        return t;
    }

    private static UIElement note(String text) {
        UIText t = new UIText(text);
        t.addClass("__designer-note__");
        return t;
    }
}
