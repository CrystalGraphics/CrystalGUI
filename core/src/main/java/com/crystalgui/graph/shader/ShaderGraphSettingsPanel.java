package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgPreviewMesh;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.Configurator;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.SettingsConfigurator;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Graph Settings tab — what the shader <b>is</b>, plus how you are looking at it.
 *
 * <p>Unity reference: the {@code Graph Settings} tab in
 * {@code docs/research/unity-inspector/07-full-window.png}.</p>
 *
 * <h3>The shader rows are generated, not written</h3>
 * <p>{@link ShaderGraphSettings} declares three {@code Setting}s and {@link SettingsConfigurator} turns
 * them into bound rows — label, control kind, default, tooltip and undo all come from the declaration.
 * Adding a fourth setting is one line in one file and it appears here. That is the same relationship the
 * command palette has with {@code CommandRegistry}, and the whole reason declarations are data.</p>
 *
 * <h3>Two groups, because they are two different kinds of thing</h3>
 * <p><b>Shader</b> is document state: it changes the emitted file, it is saved, and Ctrl+Z reaches it.
 * <b>Preview</b> is view state — which shape you are looking at and whether it is shaded — so it is
 * written directly and is deliberately not undoable. That is the same line {@code GraphSelection} and the
 * editor's folding already draw, and the 6.3.12 test asserting the preview mesh never reaches the encoded
 * document is what keeps it drawn in the right place.</p>
 *
 * <p>The preview rows are a <b>second host for state the Main Preview's context menu already owns</b>,
 * not a copy of it. Both read and write the one panel, so changing the mesh here moves the tick in that
 * menu — a copy would have let the two disagree, which is exactly the bug the settings gear exists to
 * stop being written by hand each time.</p>
 */
public class ShaderGraphSettingsPanel extends ConfiguratorPanel {

    public static final String PANEL_CLASS = "__graph-settings__";

    private final GraphDocument document;

    @Nullable
    private final MainPreviewPanel preview;

    /** The last compile, read lazily — the panel is built once and its stats are refreshed in place. */
    private final Supplier<CgShaderEmitter.Result> lastCompile;

    private final List<Configurator> statRows = new ArrayList<>();

    public ShaderGraphSettingsPanel(GraphDocument document, @Nullable MainPreviewPanel preview,
                                    @Nullable UndoStack undo,
                                    Supplier<CgShaderEmitter.Result> lastCompile) {
        this.document = document;
        this.preview = preview;
        this.lastCompile = lastCompile;
        addClass(PANEL_CLASS);

        add(ConfigDescriptor.header("Shader"), null);
        SettingsConfigurator.build(this, document.settings(), SettingsLayer.DOCUMENT,
                ShaderGraphSettings.all(), undo);

        addPreviewGroup();
        addCompileGroup();

        // The compile stats follow the graph. The SHADER rows deliberately do not re-read here: they
        // already follow their own store through SettingsConfigurator, and rebuilding them on every
        // document change would destroy whichever control was being dragged at the time.
        document.onChanged.connect(this::refreshStats);
    }

    // ── Preview: view state ─────────────────────────────────────────────────

    private void addPreviewGroup() {
        if (preview == null) return;
        ConfiguratorGroup group = group("Preview");
        addChild(group);

        List<String> meshes = new ArrayList<>();
        for (CgPreviewMesh shape : CgPreviewMesh.values()) meshes.add(shape.label());

        Configurator mesh = addTo(group.content(),
                ConfigDescriptor.select("preview.mesh", "Mesh", meshes)
                        .tooltip("Which shape the Main Preview draws. Not saved with the graph."),
                preview.mesh().label());
        if (mesh != null) {
            mesh.control().changed.connect(value -> {
                CgPreviewMesh chosen = meshNamed(String.valueOf(value));
                if (chosen != null) preview.setMesh(chosen);
            });
        }

        Configurator lit = addTo(group.content(),
                ConfigDescriptor.bool("preview.lighting", "Lighting")
                        .tooltip("Viewport shading, not a lighting model — see CgShaderEmitter.Shading."),
                preview.isLit());
        if (lit != null) {
            lit.control().changed.connect(value -> preview.setLit(Boolean.TRUE.equals(value)));
        }
    }

    @Nullable
    private static CgPreviewMesh meshNamed(String label) {
        for (CgPreviewMesh shape : CgPreviewMesh.values()) {
            if (shape.label().equals(label)) return shape;
        }
        return null;
    }

    // ── Compile: read-only ──────────────────────────────────────────────────

    /**
     * What the last emit produced.
     *
     * <p>Every number here already exists on {@code CgShaderEmitter.Result} and is currently only visible
     * as a status line that the next message overwrites. A graph that will not compile is the
     * <em>normal</em> state while one is being built, so having the error count somewhere permanent is
     * worth a group.</p>
     */
    private void addCompileGroup() {
        ConfiguratorGroup group = group("Compile", true);
        addChild(group);
        statRows.clear();
        statRows.add(stat(group, "Nodes"));
        statRows.add(stat(group, "Edges"));
        statRows.add(stat(group, "Varyings"));
        statRows.add(stat(group, "Characters"));
        statRows.add(stat(group, "Errors"));
        refreshStats();
    }

    @Nullable
    private Configurator stat(ConfiguratorGroup group, String label) {
        // INFO, not a disabled TEXT row: a compile count is a fact, and a text field drew it as something
        // to type into -- which it also genuinely was, since disabling the wrapper never reached the
        // field inside it. Still writable programmatically, which is the whole point here: these change
        // on every emit.
        return addTo(group.content(), ConfigDescriptor.info("compile." + label, label), "");
    }

    /** Updates the numbers in place. Never rebuilds — see the constructor. */
    public void refreshStats() {
        CgShaderEmitter.Result result = lastCompile == null ? null : lastCompile.get();
        setStat(0, String.valueOf(document.nodeCount()));
        setStat(1, String.valueOf(document.edges().size()));
        setStat(2, result == null ? "—" : String.valueOf(result.varyings().size()));
        setStat(3, result == null ? "—" : String.valueOf(result.source().length()));
        setStat(4, result == null ? "—" : String.valueOf(result.errors().size()));
    }

    private void setStat(int index, String value) {
        if (index >= statRows.size()) return;
        Configurator row = statRows.get(index);
        if (row != null) row.control().setValueObject(value);
    }
}
