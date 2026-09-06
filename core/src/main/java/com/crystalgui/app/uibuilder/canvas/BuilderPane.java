package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * What a {@code .cgui} tab actually contains: the toolbar, and the plane under it.
 *
 * <p>A tab is one element, and the canvas is no longer the whole of one — so this is what
 * {@code DocumentEditor.view()} answers.</p>
 *
 * <h3>It answers the plane's data keys, and that is not a convenience</h3>
 *
 * <p>A {@code DataContext} walks <b>outward</b> from its source and the workbench seeds the inspector
 * with the active editor's view. Wrapping the plane in a column therefore put the plane one step the
 * wrong way down the tree: the walk starts here and never descends, so the builder's selection, document
 * and editor would all become unreachable the moment a toolbar was added above them. Delegating is what
 * keeps the wrapper invisible to everything that asks.</p>
 */
public final class BuilderPane extends UIElement implements DataProvider {

    public static final Name NAME = Name.of("builderpane");

    public static final String PANE_CLASS = "__builder-pane__";

    private final BuilderSurface surface;

    BuilderPane(BuilderToolbar toolbar, BuilderSurface surface) {
        super(NAME);
        this.surface = surface;
        addClass(PANE_CLASS);
        // AT DEFAULT ORIGIN, which is below the user-agent sheet, so `ua/uibuilder.css` still decides and
        // this only stops the pane collapsing where no sheet is installed. `SurfaceEditor` states its own
        // fill the same way and for the same reason; without it the plane inherited a 100% height inside
        // a column that also holds a toolbar, and pushed itself out of the tab.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f).flexDirection(FlexDirection.COLUMN));
        StyleGroup.defaultPipeline(surface.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).height(0f).flexGrow(1f));
        append(toolbar, surface);
    }

    /** The plane, for a caller that has the pane and wants what is in it. */
    public BuilderSurface surface() {
        return surface;
    }

    @Override
    @Nullable
    public Object getData(DataKey<?> key) {
        return surface.getData(key);
    }
}
