package com.crystalgui.widget.dnd;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.DragPreview;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The capsule that follows the pointer during a drag: an icon and a label, drawn from a {@link DragPreview}. Nothing
 * constructs one to carry a drag; the drag's preview is drawn by {@link DragGhostPresenter} in the document, or by the
 * shell over a window's client. Styled through {@code .__drag-ghost__} and its parts.
 *
 * <pre>{@code
 * ghost.apply(DragPreview.of("crystalgui:folder", "Problems").styled("__row-ghost__"));
 * }</pre>
 *
 * <ul>
 *   <li>Out of flow and hidden are written from Java at construction: the first layout runs before any rule has matched,
 *       and a text latches its width on that first measurement.</li>
 *   <li>{@link #apply} is idempotent per {@link DragPreview#version}.</li>
 * </ul>
 */
public final class DragGhost extends UIElement {

    public static final Name NAME = Name.of("dragghost");

    /** One shared class, so a theme styles every ghost in the engine once. */
    public static final String GHOST_CLASS = "__drag-ghost__";

    /** {@code .__drag-ghost__::part(pre-icon)} in a sheet. */
    public static final String ICON_PART = "pre-icon";
    /** {@code .__drag-ghost__::part(label)}. */
    public static final String LABEL_PART = "label";

    /** On the ghost while its label sits to the <em>left</em> of the icon. */
    public static final String FLIPPED_CLASS = "__flipped__";

    /** On the ghost while it is carrying an icon but no label. */
    public static final String UNLABELLED_CLASS = "__unlabelled__";

    /** On the ghost while it carries no icon: the label alone, with no empty box beside it. */
    public static final String ICONLESS_CLASS = "__iconless__";

    private final UIElement icon = new UIElement();
    private final UIText label = new UIText("");

    private @Nullable DragPreview applied;
    private int appliedVersion = -1;
    private String appliedStyle = "";

    public DragGhost() {
        super(NAME);
        addClass(GHOST_CLASS);
        // A ghost parked in a hittable widget is hittable until a drag registers it.
        setHitTest(false);

        ShadowRoot shadow = attachShadow();
        icon.set(Attribute.PART, ICON_PART);
        icon.setHitTest(false);
        label.set(Attribute.PART, LABEL_PART);
        shadow.append(icon);
        shadow.append(label);

        // THE ATTRIBUTE, NOT A `display` VALUE: showing clears the attribute and says nothing about the cascade, so a
        // ghost hidden by a rule and shown by the attribute came out displayed and still resolving `display: none`.
        setDisplayed(false);
        StyleGroup.inlinePipeline(getStyle().getLayoutGroup(), l -> l.positionType(TaffyPosition.ABSOLUTE));
    }

    /** Draws {@code preview}: its icon, label, style and flip. Cheap when it is the one already drawn and unchanged. */
    public void apply(DragPreview preview) {
        if (preview == applied && preview.version() == appliedVersion) return;
        applied = preview;
        appliedVersion = preview.version();

        String style = preview.style();
        if (!style.equals(appliedStyle)) {
            if (!appliedStyle.isEmpty()) removeClass(appliedStyle);
            if (!style.isEmpty()) addClass(style);
            appliedStyle = style;
        }
        if (!label.getText().equals(preview.label())) label.setText(preview.label());
        showIcon(preview);
        boolean unlabelled = preview.label().isEmpty();
        if (hasClass(UNLABELLED_CLASS) != unlabelled) toggleClass(UNLABELLED_CLASS, unlabelled);
        if (hasClass(FLIPPED_CLASS) != preview.isFlipped()) toggleClass(FLIPPED_CLASS, preview.isFlipped());
    }

    private void showIcon(DragPreview preview) {
        CgUiDrawable glyph = preview.glyph();
        if (glyph == null) {
            String name = preview.iconName();
            CgUiSvg resolved = name == null || name.isEmpty() ? null : CgUiSvg.ofIcon(name);
            glyph = resolved == null ? CgUiDrawable.EMPTY : resolved;
        }
        CgUiDrawable drawn = glyph;
        int tint = preview.tint();
        StyleGroup.inlinePipeline(icon.getStyle().getGeneralGroup(), g -> g.overlay(drawn).overlayTint(tint));
        boolean shown = drawn != CgUiDrawable.EMPTY;
        StyleGroup.inlinePipeline(icon.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        // THE BOX IS THE ICON'S, so it goes with the icon: a tinted square holding nothing reads as a glyph that
        // failed to load.
        if (hasClass(ICONLESS_CLASS) == shown) toggleClass(ICONLESS_CLASS, !shown);
    }
}
