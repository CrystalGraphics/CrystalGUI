package com.crystalgui.desktop.taskbar;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.DesktopCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.render.texture.CgUiBackdropFilter;
import com.crystalgui.render.texture.CgUiGradient;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.backdrop.BackdropFilterValue;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.style.property.visual.border.BorderRadiusProperties;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.config.PanelForm;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.control.Button;
import dev.vfyjxf.taffy.style.TaffyDimension;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

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
 * down: the cascade owns the {@link CgUiBackdropFilter} that {@code backdrop-filter} parsed, and mutating someone
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
    private final CgUiBackdropFilter glass = new CgUiBackdropFilter();

    /**
     * The tone: the accent wash's colour, alpha included, seeded from the bar's glow.
     *
     * <p>ONE VALUE FOR THE FAMILY. The bar, its hover preview and the switcher each carry the wash from
     * a pin of their own (a component token never chains off another component's), and they ship equal
     * -- so the picker writes all three glows live and the pasted CSS carries all three pins, or the
     * first hover after picking a tone would show a preview in the old one.</p>
     */
    private int tone = 0x333574F0;
    private final List<UIElement> glows;

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

    /** The tuned state as CSS, re-stated whenever anything here moves. @see #refreshReadout */
    private final Property<String> readout = Property.of("");

    private final ConfiguratorPanel body = new ConfiguratorPanel();

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

    private UIElement build() {
        seedFromCascade();

        UIElement content = new UIElement();
        content.addClass("__designer__");

        // A ConfiguratorPanel, which is a ScrollerView -- FOR THE BARS. This window opens clamped to the
        // work area, so on a short desktop it is scrolled from the moment it appears, and a scrollable
        // region with no bar reads as ENDING where it is cut off.
        body.addClass("__designer-body__");
        PanelForm form = body.form();

        form.header("Shape");
        form.prop(ConfigDescriptor.bool("widthAuto", "Width follows content"), Property.derived(
                () -> widthAuto, on -> {
                    widthAuto = on;
                    applyGeometry();
                }));
        slider(form, "Width", 80f, 1600f, 0, () -> islandWidth, v -> {
            islandWidth = v;
            widthAuto = false;
            applyGeometry();
        });
        slider(form, "Height", 12f, 96f, 0, () -> islandHeight, v -> {
            islandHeight = v;
            applyGeometry();
        });
        slider(form, "Corner radius", 0f, 48f, 0, () -> radius, v -> {
            radius = v;
            applyGeometry();
        });
        slider(form, "Padding", 0f, 32f, 0, () -> padding, v -> {
            padding = v;
            applyGeometry();
        });
        slider(form, "Gap", 0f, 32f, 0, () -> gap, v -> {
            gap = v;
            applyGeometry();
        });

        form.header("Position");
        form.note("Right-drag the island itself, or nudge it here.");
        slider(form, "Offset X", -1200f, 1200f, 0, () -> offsetX, v -> {
            offsetX = v;
            applyOffset();
        });
        slider(form, "Offset Y", -900f, 900f, 0, () -> offsetY, v -> {
            offsetY = v;
            applyOffset();
        });

        form.header("Backdrop");
        glassSlider(form, "Blur", 0f, 40f, 0, glass::getBlurRadius, glass::setBlurRadius);
        glassSlider(form, "Bezel", 0f, 40f, 0, glass::getBezel, glass::setBezel);
        glassSlider(form, "Index of refraction", 1f, 2.5f, 2, glass::getIor, glass::setIor);
        glassSlider(form, "Specular", 0f, 1.5f, 2, glass::getSpecular, glass::setSpecular);
        form.note("Rim is the hairline at the boundary; glow is the broad falloff. "
                + "The rim should dominate \u2014 a highlight made mostly of glow reads as bloom.");
        glassSlider(form, "Rim", 0f, 1f, 2, glass::getEdgeHighlight, glass::setEdgeHighlight);
        glassSlider(form, "Rim width", 0f, 12f, 1, glass::getEdgeWidth, glass::setEdgeWidth);
        glassSlider(form, "Rim evenness", 0f, 1f, 2, glass::getRimAmbient, glass::setRimAmbient);
        glassSlider(form, "Glow", 0f, 1f, 2, glass::getGlow, glass::setGlow);
        glassSlider(form, "Chromatic", 0f, 1f, 2, glass::getChromatic, glass::setChromatic);
        glassSlider(form, "Noise", 0f, 0.25f, 3, glass::getNoise, glass::setNoise);
        glassSlider(form, "Saturation", 0f, 3f, 2, glass::getSaturation, glass::setSaturation);
        // THE ONE THAT WAS MISSING. The seed copied every other parameter and not this, so the designer
        // opened with the bar at luminosity 0 -- a plain alpha tint -- while the sheet ran it at 1, and
        // a tint alpha tuned here was tuned against a material the sheet does not draw.
        form.note("Luminosity is the Windows layer: how much of the backdrop's BRIGHTNESS the "
                + "tint's replaces, hue kept. 1 is Mica \u2014 a temperature, never a picture; 0 is a "
                + "plain alpha tint, where the tint's alpha alone decides what shows through.");
        glassSlider(form, "Luminosity", 0f, 1f, 2, glass::getLuminosity, glass::setLuminosity);

        form.header("Tint");
        form.note("The colour laid over the blur. ALPHA IS THE ONE THAT MATTERS \u2014 it is how "
                + "much of the tint sits over the backdrop, and the easiest thing here to overdo.");
        form.prop(ConfigDescriptor.color("tint", "Tint"), Property.derived(glass::getTintArgb, argb -> {
            glass.setTintArgb(argb);
            refreshReadout();
        }));

        form.header("Tone");
        form.note("The accent wash under the entries \u2014 and under the hover preview and the "
                + "switcher, which take the same tone. Alpha is how loud it is; the sheet ships 20%.");
        form.prop(ConfigDescriptor.color("tone", "Tone"), Property.derived(() -> tone, argb -> {
            tone = argb;
            applyTone();
        }));

        // THE READOUT SCROLLS WITH THE CONTROLS; only the buttons are pinned. It is eight lines of CSS
        // and it grows, so below the scroll region it simply fell off the bottom of the window, taking
        // Copy CSS with it whenever the panel was short.
        form.header("CSS");
        refreshReadout();
        form.prop(ConfigDescriptor.note("css"), readout).addClass("__designer-readout__");

        content.append(body);
        content.append(actions());
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
        row.append(copy);

        Button reset = new Button("Reset");
        reset.onPressed.connect(this::reset);
        row.append(reset);
        return row;
    }

    /**
     * Every field back to what the bar had when the tuner opened.
     *
     * <p>LAST TO FIRST: Width's own write turns "Width follows content" off, so restoring it after the
     * checkbox would leave a bar that followed its content showing a fixed width.</p>
     */
    private void reset() {
        List<ConfigControl> controls = new ArrayList<>(body.controls().values());
        for (int i = controls.size() - 1; i >= 0; i--) {
            if (controls.get(i) instanceof ValueControl<?> field) field.restoreBoundValue();
        }
        refreshReadout();
    }

    // ── applying ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the sheet's current values so the tuner opens on what is actually on screen.
     *
     * <p>Starting from hardcoded defaults instead would be the same class of lie the "copy CSS by hand"
     * step is: the first drag of any slider would snap the taskbar to a look nobody chose.</p>
     */
    /** The line ending the generated sheet uses, named so it survives an edit. */
    private static final String LINE = System.lineSeparator();

    private void seedFromCascade() {
        Object background = island.getStyle().getComputed(StylePropertyRegistry.BACKDROP_FILTER);
        if (background instanceof CgUiBackdropFilter live) {
            glass.setBlurRadius(live.getBlurRadius()).setTintArgb(live.getTintArgb())
                 .setSaturation(live.getSaturation()).setBezel(live.getBezel())
                 .setIor(live.getIor()).setSpecular(live.getSpecular())
                 .setNoise(live.getNoise()).setFallbackColorArgb(live.getFallbackColorArgb())
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
        StyleGroup.inlinePipeline(island.getStyle().getGeneralGroup(), g -> g.backdropFilter(glass));
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
                        ? Transform.IDENTITY : Transform.translate(offsetX, offsetY)));
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
        sb.append(String.format(Locale.ROOT, "    padding: %.0fpx;%n", padding));
        sb.append(String.format(Locale.ROOT, "    gap: %.0fpx;%n", gap));
        sb.append(String.format(Locale.ROOT, "    border-radius: %.0fpx;%n", radius));
        // THROUGH THE VALUE'S OWN WRITER, so the panel cannot spell the grammar differently
        // from the parser that has to read it back. @see BackdropFilterValue#write
        sb.append("    backdrop-filter: ").append(BackdropFilterValue.write(glass)).append(";" + LINE);
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
        readout.set(css());
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X",
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    // ── control builders ─────────────────────────────────────────────────────────────────────────────

    /** A slider over one of the tuner's own numbers. */
    private void slider(PanelForm form, String label, float min, float max, int decimals,
                        DoubleSupplier read, Consumer<Float> apply) {
        form.prop(ConfigDescriptor.number(label, label).range(min, max).decimals(decimals),
                Property.derived(read::getAsDouble, v -> apply.accept(v.floatValue())));
    }

    /** A slider over one of the glass's own parameters, which re-states the CSS as it moves. */
    private void glassSlider(PanelForm form, String label, float min, float max, int decimals,
                             FloatSupplier read, Consumer<Float> write) {
        slider(form, label, min, max, decimals, read::get, v -> {
            write.accept(v);
            refreshReadout();
        });
    }

    /** A {@code float} getter, which {@code java.util.function} does not have. */
    @FunctionalInterface
    private interface FloatSupplier {
        float get();
    }
}
