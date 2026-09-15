package com.crystalgui.app.uibuilder.canvas;

import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.config.control.SelectControl;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ToolbarForm;
import com.crystalgui.widget.config.control.BooleanControl;
import com.crystalgui.widget.control.Button;

/**
 * The strip above the canvas: what size, at what scale, in which theme, and design or preview.
 *
 * <pre>{@code
 * BuilderToolbar bar = new BuilderToolbar(surface);
 * }</pre>
 *
 * <p>Four questions, and each is one a designer changes while working rather than a setting. The canvas
 * <b>preset</b> is the document's own — resizing the page rewrites it — while {@code uiScale}, theme and
 * preview are the viewer's and are never written to the document.</p>
 *
 * <p>Each is a field bound to where its answer lives, so the strip shows the state however it was changed
 * — a key, a command, another window switching the theme.</p>
 */
public final class BuilderToolbar extends UIElement {

    public static final Name NAME = Name.of("buildertoolbar");

    public static final String BAR_CLASS = "__builder-toolbar__";

    /**
     * On the preview field, always.
     *
     * <p>Everything else on this bar describes the page you are editing. Preview is the one control that
     * changes what the editor IS, so it sits apart from them, which is where IntelliJ puts the same switch
     * on a Markdown file.</p>
     */
    public static final String PREVIEW_CLASS = "__preview__";

    /** The scales Minecraft itself offers, which is what a document will be seen at. */
    private static final int[] UI_SCALES = {1, 2, 3, 4};

    /** Offered when a document declares no {@code preview.sizes} of its own. */
    private static final float[][] FALLBACK_PRESETS = {{800f, 480f}, {427f, 240f}, {1280f, 720f}};

    private final BuilderSurfaceHost host;

    private final List<float[]> presets = new ArrayList<>();

    private final Configurator preview;

    private final Configurator sizeRow;

    /** What the toolbar drives. Narrow on purpose: a toolbar may not reach the whole editor. */
    public interface BuilderSurfaceHost {

        Artboard artboard();

        boolean isDesignMode();

        void setDesignMode(boolean design);
    }

    public BuilderToolbar(BuilderSurfaceHost host) {
        super(NAME);
        this.host = host;
        addClass(BAR_CLASS);
        buildPresets();

        ToolbarForm form = ToolbarForm.into(this);
        sizeRow = form.prop(ConfigDescriptor.select("size", "Size", presetLabels()),
                Property.derived(this::presetShown, this::choosePreset));
        // THE DOCUMENT'S SIZES ARE EDITABLE NOW, from the Document tab: read once, an added size never reached this
        // list until the editor was reopened.
        whileConnected(() -> host.artboard().model().onChanged().connect(this::followPresets));
        form.prop(ConfigDescriptor.select("scale", "Scale", scaleLabels()),
                Property.derived(() -> scaleLabel(host.artboard().uiScale()), this::chooseScale));
        UiThemeManager themes = UiThemeManager.getInstance();
        form.prop(ConfigDescriptor.select("theme", "Theme", themeIds()),
                Property.derived(themes::activeThemeId, themes::setTheme)
                        .announcedBy(refresh -> themes.onChanged.connect(refresh)));
        preview = form.prop(ConfigDescriptor.bool("preview", "Preview").toggle(true),
                Property.derived(() -> !host.isDesignMode(), this::setPreview));
        preview.addClass(PREVIEW_CLASS);
    }

    /** The sizes the Size dropdown lists, as it labels them — for a test. */
    public List<String> sizeOptions() {
        return presetLabels();
    }

    /** The preview toggle's button — for a test. */
    public Button previewButton() {
        return ((BooleanControl) preview.control()).toggleButton();
    }

    /** Switches between using the document and designing it. */
    public void setPreview(boolean previewing) {
        host.setDesignMode(!previewing);
    }

    /** The preset the page is at, or null for a size no preset names. */
    @Nullable
    private String presetShown() {
        Artboard artboard = host.artboard();
        for (float[] size : presets) {
            if (size[0] == artboard.boardWidth() && size[1] == artboard.boardHeight()) return labelOf(size);
        }
        return null;
    }

    private void choosePreset(@Nullable String label) {
        for (float[] size : presets) {
            if (labelOf(size).equals(label)) {
                host.artboard().setSize(size[0], size[1]);
                return;
            }
        }
    }

    /**
     * The scale a player would see the document at.
     *
     * <p>On the ARTBOARD's transform, not the canvas zoom: zoom is how close you are standing and scale
     * is how big the pixels are, and a document that only looks right when you are zoomed in is a
     * document that is wrong. Layout underneath stays in logical pixels either way — {@code transform}
     * never reflows.</p>
     */
    private void chooseScale(@Nullable String label) {
        for (int each : UI_SCALES) {
            if (scaleLabel(each).equals(label)) host.artboard().setUiScale(each);
        }
    }

    /** The sizes offered when a document declares none, as a person reads them — what an empty size list means. */
    public static List<String> defaultSizeLabels() {
        List<String> labels = new ArrayList<>(FALLBACK_PRESETS.length);
        for (float[] size : FALLBACK_PRESETS) labels.add(Math.round(size[0]) + "×" + Math.round(size[1]));
        return labels;
    }

    private List<String> presetLabels() {
        List<String> labels = new ArrayList<>(presets.size());
        for (float[] size : presets) labels.add(labelOf(size));
        return labels;
    }

    private static String labelOf(float[] size) {
        return Math.round(size[0]) + " x " + Math.round(size[1]);
    }

    private static List<String> scaleLabels() {
        List<String> labels = new ArrayList<>(UI_SCALES.length);
        for (int each : UI_SCALES) labels.add(scaleLabel(each));
        return labels;
    }

    private static String scaleLabel(float scale) {
        return Math.round(scale) + "x";
    }

    private static List<String> themeIds() {
        List<String> ids = new ArrayList<>();
        for (UiTheme installed : ThemeRegistry.themes()) ids.add(installed.id());
        return ids;
    }

    /** Re-reads the document's sizes, and re-lists them when they changed. */
    private void followPresets() {
        List<String> before = presetLabels();
        presets.clear();
        buildPresets();
        List<String> after = presetLabels();
        if (after.equals(before) || !(sizeRow.control() instanceof SelectControl select)) return;
        Dropdown dropdown = select.dropdown();
        dropdown.clearOptions();
        for (String label : after) dropdown.addOption(label);
        String shown = presetShown();
        if (shown != null) dropdown.select(shown);
    }

    /** The document's own sizes when it names any, and a workable set when it does not. */
    private void buildPresets() {
        JsonElement declared = host.artboard().model().header().get("preview");
        if (declared != null && declared.isJsonObject()) {
            JsonElement sizes = declared.getAsJsonObject().get("sizes");
            if (sizes != null && sizes.isJsonArray()) {
                for (JsonElement each : sizes.getAsJsonArray()) {
                    if (!each.isJsonArray()) continue;
                    JsonArray pair = each.getAsJsonArray();
                    if (pair.size() >= 2) {
                        presets.add(new float[]{pair.get(0).getAsFloat(), pair.get(1).getAsFloat()});
                    }
                }
            }
        }
        if (presets.isEmpty()) {
            for (float[] fallback : FALLBACK_PRESETS) presets.add(fallback);
        }
    }
}
