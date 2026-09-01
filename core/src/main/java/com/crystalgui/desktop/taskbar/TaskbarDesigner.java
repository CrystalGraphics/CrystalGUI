package com.crystalgui.desktop.taskbar;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.render.texture.CgUiGlass;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.box.Box;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.form.ColorSelector;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.text.UIText;
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
 * <p>Dragging the island moves it with {@link UINode#setTransform}, which Taffy never sees. That is
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
    private final UINode island;
    private final CgUiGlass glass = new CgUiGlass();

    /**
     * The tone: the accent wash's colour, alpha included, seeded from the bar's glow.
     *
     * <p>ONE VALUE FOR THE FAMILY. The bar, its hover preview and the switcher each carry the wash from
     * a pin of their own (a component token never chains off another component's), and they ship equal
     * -- so the picker writes all three glows live and the pasted CSS carries all three pins, or the
     * first hover after picking a tone would show a preview in the old one.</p>
     */
    private int tone = 0x333574F0;
    private final List<UINode> glows;

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

    private TaskbarDesigner(Taskbar taskbar, Desktop desktop) {
        this.taskbar = taskbar;
        // Every one of these is a final field of a live widget, so none can be absent — a desktop always
        // has a switcher and a taskbar always has its one preview panel. @see #tone
        this.glows = List.of(taskbar.glow(), taskbar.previewPanel().glow(), desktop.switcher().glow());
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
    public static @Nullable WindowFrame open(UIDocument window) {
        if (window == null) return null;
        Desktop desktop = Desktop.ifPresent(window);
        if (desktop == null) return null;
        Taskbar taskbar = desktop.taskbar();
        if (taskbar == null) return null;

        TaskbarDesigner designer = new TaskbarDesigner(taskbar, desktop);
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
        desktop.addWindow(frame);
        // AFTER openWindow, not before: adding a window is what places and sizes it, so a size written
        // first is simply overwritten. @see Desktop#placeByCascade
        // CLAMPED TO THE WORK AREA, not a fixed size. 640 logical is 1280 physical at the default
        // uiScale of 2, against a work area 540 logical tall -- so the first version opened a window
        // taller than the desktop it was on and pushed its own buttons off the bottom of the screen.
        float maxH = desktop.box().height();
        frame.resizeTo(400f, maxH > 0f ? Math.min(460f, maxH - 60f) : 460f);
        return frame;
    }

    // ── the panel ────────────────────────────────────────────────────────────────────────────────

    private UINode build() {
        seedFromCascade();

        UINode content = new UINode();
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

        body.append(heading("Shape"));
        body.append(toggle("Width follows content", widthAuto, on -> {
            widthAuto = on;
            applyGeometry();
        }));
        body.append(slider("Width", 80f, 1600f, islandWidth, "%.0f", v -> {
            islandWidth = v;
            widthAuto = false;
            applyGeometry();
        }));
        body.append(slider("Height", 12f, 96f, islandHeight, "%.0f", v -> {
            islandHeight = v;
            applyGeometry();
        }));
        body.append(slider("Corner radius", 0f, 48f, radius, "%.0f", v -> {
            radius = v;
            applyGeometry();
        }));
        body.append(slider("Padding", 0f, 32f, padding, "%.0f", v -> {
            padding = v;
            applyGeometry();
        }));
        body.append(slider("Gap", 0f, 32f, gap, "%.0f", v -> {
            gap = v;
            applyGeometry();
        }));

        body.append(heading("Position"));
        body.append(note("Right-drag the island itself, or nudge it here."));
        body.append(slider("Offset X", -1200f, 1200f, offsetX, "%.0f", v -> {
            offsetX = v;
            applyOffset();
        }));
        body.append(slider("Offset Y", -900f, 900f, offsetY, "%.0f", v -> {
            offsetY = v;
            applyOffset();
        }));

        body.append(heading("Backdrop"));
        body.append(slider("Blur", 0f, 40f, glass.getBlurRadius(), "%.0f", v -> {
            glass.setBlurRadius(v);
            refreshReadout();
        }));
        body.append(slider("Bezel", 0f, 40f, glass.getBezel(), "%.0f", v -> {
            glass.setBezel(v);
            refreshReadout();
        }));
        body.append(slider("Index of refraction", 1f, 2.5f, glass.getIor(), "%.2f", v -> {
            glass.setIor(v);
            refreshReadout();
        }));
        body.append(slider("Specular", 0f, 1.5f, glass.getSpecular(), "%.2f", v -> {
            glass.setSpecular(v);
            refreshReadout();
        }));
        body.append(note("Rim is the hairline at the boundary; glow is the broad falloff. "
                + "The rim should dominate \u2014 a highlight made mostly of glow reads as bloom."));
        body.append(slider("Rim", 0f, 1f, glass.getEdgeHighlight(), "%.2f", v -> {
            glass.setEdgeHighlight(v);
            refreshReadout();
        }));
        body.append(slider("Rim width", 0f, 12f, glass.getEdgeWidth(), "%.1f", v -> {
            glass.setEdgeWidth(v);
            refreshReadout();
        }));
        body.append(slider("Rim evenness", 0f, 1f, glass.getRimAmbient(), "%.2f", v -> {
            glass.setRimAmbient(v);
            refreshReadout();
        }));
        body.append(slider("Glow", 0f, 1f, glass.getGlow(), "%.2f", v -> {
            glass.setGlow(v);
            refreshReadout();
        }));
        body.append(slider("Chromatic", 0f, 1f, glass.getChromatic(), "%.2f", v -> {
            glass.setChromatic(v);
            refreshReadout();
        }));
        body.append(slider("Noise", 0f, 0.25f, glass.getNoise(), "%.3f", v -> {
            glass.setNoise(v);
            refreshReadout();
        }));
        body.append(slider("Saturation", 0f, 3f, glass.getSaturation(), "%.2f", v -> {
            glass.setSaturation(v);
            refreshReadout();
        }));
        // THE ONE THAT WAS MISSING. The seed copied every other parameter and not this, so the designer
        // opened with the bar at luminosity 0 -- a plain alpha tint -- while the sheet ran it at 1, and
        // a tint alpha tuned here was tuned against a material the sheet does not draw.
        body.append(note("Luminosity is the Windows layer: how much of the backdrop's BRIGHTNESS the "
                + "tint's replaces, hue kept. 1 is Mica \u2014 a temperature, never a picture; 0 is a "
                + "plain alpha tint, where the tint's alpha alone decides what shows through."));
        body.append(slider("Luminosity", 0f, 1f, glass.getLuminosity(), "%.2f", v -> {
            glass.setLuminosity(v);
            refreshReadout();
        }));

        body.append(heading("Tint"));
        body.append(note("The colour laid over the blur. ALPHA IS THE ONE THAT MATTERS \u2014 it is how "
                + "much of the tint sits over the backdrop, and the easiest thing here to overdo."));
        body.append(tintPicker());

        body.append(heading("Tone"));
        body.append(note("The accent wash under the entries \u2014 and under the hover preview and the "
                + "switcher, which take the same tone. Alpha is how loud it is; the sheet ships 20%."));
        body.append(tonePicker());

        // THE READOUT SCROLLS WITH THE CONTROLS; only the buttons are pinned. It is eight lines of CSS
        // and it grows, so below the scroll region it simply fell off the bottom of the window, taking
        // Copy CSS with it whenever the panel was short.
        readout = new UIText("");
        readout.addClass("__designer-readout__");
        body.append(heading("CSS"));
        body.append(readout);

        content.append(body);
        content.append(actions());
        refreshReadout();
        return content;
    }

    private UINode actions() {
        UINode row = new UINode();
        row.addClass("__designer-actions__");

        Button copy = new Button("Copy CSS");
        copy.onPressed.connect(() -> {
            CgPlatform.input().setClipboard(css());
            CrystalGuiCore.LOGGER.info("Taskbar CSS copied to the clipboard:\n{}", css());
        });
        row.append(copy);

        Button reset = new Button("Reset");
        reset.onPressed.connect(() -> {
            for (Runnable r : resets) r.run();
            refreshReadout();
        });
        row.append(reset);
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
                 .setRimAmbient(live.getRimAmbient()).setLuminosity(live.getLuminosity());
        }
        // The tone is the glow's middle stop -- the sheet's gradient is transparent / tone / transparent.
        Object wash = taskbar.glow().getStyle().getComputed(StylePropertyRegistry.BACKGROUND);
        if (wash instanceof CgUiGradient gradient && gradient.stops().size() >= 2) {
            tone = gradient.stops().get(gradient.stops().size() / 2).argb();
        }
        // BEFORE the IMPORTANT writes below, or this reads back the designer's own value.
        if (island.getStyle().getComputed(LayoutProperties.MIN_WIDTH) instanceof TaffyDimension floor) {
            autoMinWidth = floor;
        }
        // The floors say what a missing size means; a null box means the same. The designer opens
        // from a command, so the island it is measuring may not have been laid out on that frame.
        Box islandBox = island.box();
        islandWidth = Math.max(80f, islandBox == null ? 0f : islandBox.width());
        islandHeight = Math.max(12f, islandBox == null ? 0f : islandBox.height());
        // THE RADIUS IS THE SHEET'S, NEVER THE FIELD'S DEFAULT. applyGeometry() below writes every
        // geometry value at IMPORTANT, so anything this method does not seed is imposed on the bar the
        // moment the tuner opens -- the exact failure the javadoc above warns about, reached without
        // touching a slider. The bar ships SQUARE and the field defaulted to 8, so merely opening the
        // designer rounded the corners of a full-width bar: at each end the glass's arc cut away from
        // the screen edge and the raw, unblurred world showed through the notch, with the __edge__
        // hairline -- square and full width -- running straight over the top of it.
        //
        // NULL MEANS ZERO, never "keep the default": getComputed answers null for a property nothing
        // has written, and for a radius that is the sheet saying there is none. Reading it back also
        // makes a SECOND open honest, since the first one's IMPORTANT write is what it now finds.
        //
        // PADDING AND GAP ARE KNOWINGLY STILL UNSEEDED, and are the same gap one step quieter: the bar
        // ships 3px top/bottom and 8px left/right while this panel has ONE padding slider, so no single
        // seeded number can be the sheet's -- opening the tuner moves the bar's height by 2px whichever
        // value is chosen. Gap is harmless (the taskbar's only in-flow child is the entries row, and the
        // 2px the entries use is that row's own). Both want a per-edge control, not a better seed.
        radius = island.getStyle().getComputed(BorderRadiusProperties.TOP_LEFT_X) instanceof LengthPercent r
                ? r.resolve(islandWidth)
                : 0f;
        StyleGroup.inlinePipeline(island.getStyle().getGeneralGroup(), g -> g.background(glass));
        applyGeometry();
    }

    private void applyGeometry() {
        StyleGroup.inlinePipeline(island.getStyle().getLayoutGroup(), l -> {
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
        StyleGroup.inlinePipeline(island.getStyle().getGeneralGroup(),
                g -> g.borderRadius(radius));
        refreshReadout();
    }

    /**
     * Writes the tone into every glow at IMPORTANT origin -- the sheet's own stops, the picked colour
     * in the middle. A fresh gradient per element, since a drawable is handed its element's radii
     * immediately before it draws and three elements sharing one would be fine today and a trap later.
     */
    private void applyTone() {
        for (int i = 0; i < glows.size(); i++) {
            // ONE TONE, TWO AXES -- and the tuner has to draw both or it shows a look the sheet does not,
            // the same fault the luminosity seed had. The BAR washes across, because a strip's centre of
            // gravity is the cluster of entries in the middle of it. A PANEL is a header with a picture
            // under it and washes DOWN from its top edge, so the tint lands on the chrome and never on the
            // thumbnail; across, it was a blue hotspot in the middle of both. @see ua/desktop.css
            boolean isBar = i == 0;
            CgUiGradient wash = isBar
                    ? new CgUiGradient(90f, List.of(
                            new CgUiGradient.Stop(0.18f, 0x00000000),
                            new CgUiGradient.Stop(0.50f, tone),
                            new CgUiGradient.Stop(0.82f, 0x00000000)))
                    : new CgUiGradient(180f, List.of(
                            new CgUiGradient.Stop(0f, tone),
                            new CgUiGradient.Stop(1f, 0x00000000)));
            StyleGroup.inlinePipeline(glows.get(i).getStyle().getGeneralGroup(),
                    g -> g.background(wash));
        }
        refreshReadout();
    }

    /** @see TaskbarDesigner class javadoc — a transform, so the work area never moves. */
    private void applyOffset() {
        // THROUGH THE CASCADE, not through the box. A designer's offset is a resting VALUE somebody is
        // choosing, so it belongs where a stylesheet could also have written it -- `Box.setTransform` is
        // the compositor channel, which sits ABOVE the cascade and is for a timeline that will withdraw
        // itself. @see WindowAnimation#write
        StyleGroup.inlinePipeline(island.getStyle().getGeneralGroup(),
                g -> g.transform(offsetX == 0f && offsetY == 0f
                        ? UITransform.IDENTITY : UITransform.translate(offsetX, offsetY)));
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
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            UIDocument window = island.document();
            if (window == null) return;
            float startX = offsetX, startY = offsetY;
            Drag.start(
                    island, event.getPosition().x(), event.getPosition().y(),
                    CgMouseCodes.RIGHT_BUTTON, null, 0f,
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
                + "                      saturation %.2f, luminosity %.2f, glow %.2f, edge %.2f, edge-width %.1f,%n"
                + "                      rim-ambient %.2f, chromatic %.2f, fallback %s);%n",
                glass.getBlurRadius(), hex(glass.getTint()), glass.getBezel(), glass.getIor(),
                glass.getSpecular(), glass.getNoise(), glass.getSaturation(), glass.getLuminosity(),
                glass.getGlow(), glass.getEdgeHighlight(), glass.getEdgeWidth(),
                glass.getRimAmbient(), glass.getChromatic(), hex(glass.getFallbackColor())));
        sb.append("}\n");
        // THE TONE IS A THEME PIN, not a rule: it goes in crystal-dark.css / crystal-light.css, one value
        // for the three surfaces that share the bar's material.
        sb.append(String.format(Locale.ROOT,
                "theme {%n    --taskbar-glow: %1$s;%n    --preview-glow: %1$s;%n    --switcher-glow: %1$s;%n}%n",
                hex(tone)));
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
    private UINode tintPicker() {
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

    /** The tone, the same picker as the tint: alpha is most of what is being chosen. */
    private UINode tonePicker() {
        int initial = tone;
        ColorSelector picker = new ColorSelector();
        picker.addClass("__designer-tint__");
        picker.setColor(initial);
        picker.onColorChanged.connect(argb -> {
            tone = argb;
            applyTone();
        });
        resets.add(() -> picker.setColor(initial));
        return picker;
    }

    private UINode slider(String label, float min, float max, float initial,
                             String format, Consumer<Float> apply) {
        UINode row = new UINode();
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

        row.append(name);
        row.append(slider);
        row.append(value);
        return row;
    }

    private UINode toggle(String label, boolean initial, Consumer<Boolean> apply) {
        UINode row = new UINode();
        row.addClass("__designer-row__");
        Checkbox box = new Checkbox();
        box.setChecked(initial);
        box.onCheckedChanged.connect(apply::accept);
        UIText name = new UIText(label);
        name.addClass("__designer-label__");
        resets.add(() -> box.setChecked(initial));
        row.append(box);
        row.append(name);
        return row;
    }

    private static UINode heading(String text) {
        UIText t = new UIText(text);
        t.addClass("__designer-heading__");
        return t;
    }

    private static UINode note(String text) {
        UIText t = new UIText(text);
        t.addClass("__designer-note__");
        return t;
    }
}
