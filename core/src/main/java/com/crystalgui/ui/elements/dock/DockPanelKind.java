package com.crystalgui.ui.elements.dock;

/**
 * What a registered panel type <b>is</b> — the distinction a boolean could not carry.
 *
 * <h3>Why {@code isSingleton()} had to go</h3>
 *
 * <p>It answered "is this a tool window", and {@code ActivityBar} was a view over exactly that predicate:
 * <em>"one button per singleton panel type. That filter is the whole definition."</em> Which was true, and
 * is why the rail lists <b>panels</b> rather than containers — the thing that makes it impossible to group
 * two tool windows into one sidebar pane, or to drag a view from the sidebar to the bottom panel.</p>
 *
 * <p>The Parts model needs three kinds, and two of them were sharing {@code singleton == true}. A boolean
 * cannot be extended without picking which of the new pair inherits the old meaning, and every existing
 * call site would silently take that pick.</p>
 *
 * @see DockRegion where a panel of a given kind belongs
 */
public enum DockPanelKind {

    /**
     * One instance per resource, in the {@link DockRegion#EDITOR} region — a file, a shader graph, a
     * generated source view.
     *
     * <p>Never on the activity bar in either reference: documents are reached by opening a file, and a
     * rail listing them would grow without bound and duplicate the tab strip.</p>
     */
    DOCUMENT,

    /**
     * One instance, hidden and reshown, living inside a container — VS Code's {@code ViewPane},
     * IntelliJ's {@code Content}.
     *
     * <p>What {@code singleton == true} used to mean, and what {@code isSingleton()} still reports, so
     * every existing call site keeps its meaning exactly.</p>
     */
    VIEW,

    /**
     * A group of {@link #VIEW}s sharing a region — VS Code's {@code ViewContainer}, IntelliJ's
     * {@code ToolWindow} with its {@code ContentManager}.
     *
     * <p><b>This is the kind that did not exist</b>, and its absence is why the rail lists panels. A
     * container is what an activity bar button toggles, what a region shows one of at a time, and what a
     * badge belongs to — which is why activity badges could not be built until now.</p>
     *
     * <p>A container holds its views; a view never names its container. See {@link ViewId}.</p>
     */
    CONTAINER
}
