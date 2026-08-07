package com.crystalgui.graph.shader;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.core.signal.ConnectionGroup;
import javax.annotation.Nullable;

/**
 * The Graph Inspector — Unity's panel <b>F</b>, as one dockable widget.
 *
 * <p>Reference: {@code docs/research/unity-inspector/07-full-window.png}.</p>
 *
 * <h3>Two tabs, because they answer different questions</h3>
 * <p>Unity splits {@code Node Settings} from {@code Graph Settings} and it is right to: one is contextual
 * on what you have selected and the other is global. Collapsing them into one scrolling panel means a
 * global setting slides off the bottom under whatever node happens to be selected, and you cannot reach
 * the queue without first deselecting.</p>
 *
 * <h3>This widget owns nothing but the frame</h3>
 * <p>Both tabs are {@code ConfiguratorPanel}s that bind themselves — the node tab to the graph's
 * selection, the settings tab to the document's own {@code Settings}. There is no state here and no
 * refresh to forget to call, which is the only reason a tabbed container is worth having as a class at
 * all rather than being assembled by its host.</p>
 */
public class ShaderGraphInspector extends UIElement {

    public static final String INSPECTOR_CLASS = "__graph-inspector__";

    public static final String NODE_TAB = "Node";
    public static final String GRAPH_TAB = "Graph";

    private final TabView tabs = new TabView();
    private final Tab nodeTabHolder;
    private final Tab graphTabHolder;

    private ShaderNodeInspector nodeTab;
    private ShaderGraphSettingsPanel settingsTab;

    /** Which editor this is currently showing, so pointing it at the same one twice costs nothing. */
    @Nullable
    private ShaderGraphEditor shown;

    /** This inspector's subscriptions to {@link #shown}, dropped when it is pointed elsewhere. */
    private final ConnectionGroup subscriptions = new ConnectionGroup();

    public ShaderGraphInspector(ShaderGraphEditor editor) {
        addClass(INSPECTOR_CLASS);
        markAsInternal();
        addInternalChild(tabs);

        nodeTabHolder = tabs.addTab(NODE_TAB);
        graphTabHolder = tabs.addTab(GRAPH_TAB);
        tabs.selectTab(nodeTabHolder);

        setEditor(editor);
    }

    /**
     * Points this inspector at another graph.
     *
     * <h3>Why one inspector retargeted, rather than one per graph</h3>
     *
     * <p>{@code CrystalEditor} kept a {@code Map<ShaderGraphEditor, ShaderGraphInspector>} and swapped
     * whole inspectors into a host element. That map was <b>never pruned</b>, so every graph opened in a
     * session stayed reachable through it — and once closing a tab began releasing documents, it was the
     * thing holding a disposed editor alive. It is the same shape as the {@code graphPaths} map that
     * {@code Resource} removed.</p>
     *
     * <p>Retargeting also keeps something the swap lost: <b>which tab you were on</b>. Node and Graph
     * are the two things you are alternating between, and switching graphs should not silently put you
     * back on Node.</p>
     *
     * <h3>The tabs are rebuilt, and that is honest</h3>
     *
     * <p>{@code ShaderNodeInspector} binds to a graph's selection and {@code ShaderGraphSettingsPanel}
     * builds its whole form from that document's settings, so "retarget" genuinely means new panels. What
     * is preserved is the <em>frame</em> — this element, its tabs, and the selected one — which is what a
     * caller holds a reference to and what the dock has in its tree.</p>
     */
    public void setEditor(ShaderGraphEditor editor) {
        if (editor == shown) return;
        // DROPPED FIRST. The old panels listen to the old graph's selection, and a stale ShaderNodeInspector
        // still wired to it would refresh from a graph nobody is looking at -- writing into elements that
        // are no longer in the tree.
        subscriptions.disconnectAll();
        shown = editor;

        // The BOARD is passed in, so a selected property fills the same tab a selected node does --
        // which is where Unity puts the property form too. Two sources, one subject.
        nodeTab = new ShaderNodeInspector(editor.graph(), editor.library(), editor::recompile,
                editor.blackboard());
        settingsTab = new ShaderGraphSettingsPanel(editor.graph().getDocument(), editor.mainPreview(),
                editor.graph().undoStack(), editor::lastCompile);

        nodeTabHolder.content().setOnlyChild(nodeTab);
        graphTabHolder.content().setOnlyChild(settingsTab);

        // The stats are the one thing on the settings tab that is derived rather than stored, so they
        // have to be told when a compile happens. Everything else follows its own store.
        subscriptions.add(editor.onStatusChanged.connect(status -> settingsTab.refreshStats()));
    }

    /** The graph this is currently showing, or null before it has been pointed at one. */
    @Nullable
    public ShaderGraphEditor shownEditor() {
        return shown;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public TabView tabs() {
        return tabs;
    }

    /** The Node Settings tab. */
    public ShaderNodeInspector nodeInspector() {
        return nodeTab;
    }

    /** The Graph Settings tab. */
    public ShaderGraphSettingsPanel graphSettings() {
        return settingsTab;
    }
}
