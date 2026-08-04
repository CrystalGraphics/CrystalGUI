package com.crystalgui.graph.shader;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;

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
    private final ShaderNodeInspector nodeTab;
    private final ShaderGraphSettingsPanel settingsTab;

    public ShaderGraphInspector(ShaderGraphEditor editor) {
        addClass(INSPECTOR_CLASS);

        // The BOARD is passed in, so a selected property fills the same tab a selected node does --
        // which is where Unity puts the property form too. Two sources, one subject.
        nodeTab = new ShaderNodeInspector(editor.graph(), editor.library(), editor::recompile,
                editor.blackboard());
        settingsTab = new ShaderGraphSettingsPanel(editor.graph().getDocument(), editor.mainPreview(),
                editor.graph().undoStack(), editor::lastCompile);

        markAsInternal();
        addInternalChild(tabs);

        Tab node = tabs.addTab(NODE_TAB);
        node.content().addChild(nodeTab);
        Tab graph = tabs.addTab(GRAPH_TAB);
        graph.content().addChild(settingsTab);
        tabs.selectTab(node);

        // The stats are the one thing on the settings tab that is derived rather than stored, so they
        // have to be told when a compile happens. Everything else follows its own store.
        editor.onStatusChanged.connect(status -> settingsTab.refreshStats());
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
