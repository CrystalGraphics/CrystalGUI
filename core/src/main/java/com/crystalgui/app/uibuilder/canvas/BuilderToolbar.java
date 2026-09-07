package com.crystalgui.app.uibuilder.canvas;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.style.theme.UiThemeManager;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dropdown;

/**
 * The strip above the canvas: what size, at what scale, in which theme, and design or preview.
 *
 * <pre>{@code
 * BuilderToolbar bar = new BuilderToolbar(surface);
 * }</pre>
 *
 * <p>Four questions, and each is one a designer changes while working rather than a setting. The canvas
 * <b>preset</b> is the document's own — resizing the page rewrites it, so the presets are authored by
 * dragging rather than typed into a file — while {@code uiScale}, theme and preview are the viewer's and
 * are never written to the document.</p>
 *
 * <p>It builds no chrome of its own beyond a row: the widgets are ordinary ones and {@code
 * ua/uibuilder.css} says what a builder toolbar looks like.</p>
 */
public final class BuilderToolbar extends UIElement {

    public static final Name NAME = Name.of("buildertoolbar");

    public static final String BAR_CLASS = "__builder-toolbar__";

    /** On the preview button while preview is on. */
    public static final String ACTIVE_CLASS = "__active__";

    /**
     * On the preview button, always.
     *
     * <p>Everything else on this bar describes the page you are editing — its size, its scale, its
     * theme. Preview is the one control that changes what the editor IS, so it sits apart from them,
     * which is where IntelliJ puts the same switch on a Markdown file.</p>
     */
    public static final String PREVIEW_CLASS = "__preview__";

    /** The scales Minecraft itself offers, which is what a document will be seen at. */
    private static final int[] UI_SCALES = {1, 2, 3, 4};

    /** Offered when a document declares no {@code preview.sizes} of its own. */
    private static final float[][] FALLBACK_PRESETS = {{800f, 480f}, {427f, 240f}, {1280f, 720f}};

    /** Fires with the preview state — true while the UI is being USED rather than designed. */
    public final Signal.Value<Boolean> onDidTogglePreview = new Signal.Value<>();

    private final BuilderSurfaceHost host;

    private final ConnectionGroup connections = new ConnectionGroup();

    private final List<float[]> presets = new ArrayList<>();

    private final Dropdown preset = new Dropdown("Size");
    private final Dropdown scale = new Dropdown("Scale");
    private final Dropdown theme = new Dropdown("Theme");
    private final Button preview = new Button("Preview");

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
        for (float[] size : presets) {
            preset.addOption(Math.round(size[0]) + " x " + Math.round(size[1]));
        }
        preset.select(0);
        connections.add(preset.onSelectionChanged.connect(this::choosePreset));

        for (int each : UI_SCALES) scale.addOption(each + "x");
        scale.select(0);
        connections.add(scale.onSelectionChanged.connect(this::chooseScale));

        for (UiTheme installed : ThemeRegistry.themes()) theme.addOption(installed.id());
        String active = UiThemeManager.getInstance().activeThemeId();
        if (active != null) theme.select(active);
        connections.add(theme.onSelectionChanged.connect(this::chooseTheme));

        preview.addClass(PREVIEW_CLASS);
        preview.onPressed.connect(() -> setPreview(host.isDesignMode()));

        append(preset, scale, theme, preview);
    }

    /** The sizes on offer, first being the one a document opens at. */
    public List<float[]> presets() {
        return List.copyOf(presets);
    }

    /** For a test, and for the command that toggles it from a key. */
    public Button previewButton() {
        return preview;
    }

    /**
     * @see #onDidTogglePreview
     *
     * <p>The button carries its own state, and that is not decoration. Preview's visible effect is that
     * the document's widgets become live — so on a document with nothing to press, a toggle with no
     * appearance of its own looks like a button that does nothing at all.</p>
     */
    public void setPreview(boolean previewing) {
        if (host.isDesignMode() != previewing) return;
        host.setDesignMode(!previewing);
        showPreviewState(previewing);
        onDidTogglePreview.emit(previewing);
    }

    /** Reads the state back off the host — for a toggle driven from the command rather than the button. */
    public void syncPreviewState() {
        showPreviewState(!host.isDesignMode());
    }

    private void showPreviewState(boolean previewing) {
        if (previewing) preview.addClass(ACTIVE_CLASS);
        else preview.removeClass(ACTIVE_CLASS);
        preview.setText(previewing ? "Previewing" : "Preview");
    }

    private void choosePreset(int index) {
        if (index < 0 || index >= presets.size()) return;
        float[] size = presets.get(index);
        host.artboard().setSize(size[0], size[1]);
    }

    /**
     * The scale a player would see the document at.
     *
     * <p>On the ARTBOARD's transform, not the canvas zoom: zoom is how close you are standing and scale
     * is how big the pixels are, and a document that only looks right when you are zoomed in is a
     * document that is wrong. Layout underneath stays in logical pixels either way — {@code transform}
     * never reflows.</p>
     */
    private void chooseScale(int index) {
        if (index < 0 || index >= UI_SCALES.length) return;
        host.artboard().setUiScale(UI_SCALES[index]);
    }

    private void chooseTheme(int index) {
        List<String> ids = theme.getOptions();
        if (index < 0 || index >= ids.size()) return;
        UiThemeManager.getInstance().setTheme(ids.get(index));
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

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }
}
