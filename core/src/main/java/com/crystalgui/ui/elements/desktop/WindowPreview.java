package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.MouseEvent;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

/**
 * The panel a taskbar entry raises on hover — Windows' thumbnail preview.
 *
 * <p>An icon, the window's title, a close button, and a live picture of the window itself. It is one
 * panel that MOVES between entries rather than one per entry, which is both what Windows does and the
 * only way the slide between two neighbouring entries can be a single continuous motion.</p>
 *
 * <h3>The title is elided and carries its own tooltip</h3>
 *
 * <p>A preview is about as wide as the picture, which is narrower than plenty of window titles — so the
 * title clips, and Windows answers that with a tooltip above the panel carrying the full text. Ours
 * hangs it off the label, which is where the engine's tooltips anchor. Retained and re-texted rather than
 * re-attached, because {@code Tooltip.attach} ADDS a listener pair: calling it again per window would
 * leave the first tooltip live with the old title on it.</p>
 *
 * <h3>What it does not do</h3>
 *
 * <p>No light dismiss and no close watcher. A preview is governed by the pointer — it appears when you
 * rest on an entry and goes when you leave both the entry and the panel — so joining the popover stack
 * would make Escape and a stray click fight the hover for control of it.</p>
 */
public class WindowPreview extends UIElement {

    public static final String PREVIEW_CLASS = "__window-preview__";
    public static final String HEADER_CLASS = "__preview-header__";
    public static final String TITLE_CLASS = "__preview-title__";
    public static final String CLOSE_CLASS = "__preview-close__";
    public static final String ICON_CLASS = "__pre-icon__";

    private final UIElement header = new UIElement();
    private final UIElement icon = new UIElement();
    private final UIText title = new UIText("");
    private final Button close = new Button("");
    private final WindowThumbnail thumbnail = new WindowThumbnail();
    private final Tooltip titleTooltip;

    @Nullable
    private WindowFrame frame;

    /** The panel was clicked — the taskbar activates the window. */
    public final Signal.Action onActivated = new Signal.Action();

    /** The window was closed from here, so whoever is showing this should stop. */
    public final Signal.Action onClosed = new Signal.Action();

    public WindowPreview() {
        addClass(PREVIEW_CLASS);

        icon.addClass(ICON_CLASS);
        // Unhittable, like every composite part: click-focus targets the exact element hit, so a
        // hittable icon would swallow the press that activates the window.
        icon.setHitTest(false);
        title.addClass(TITLE_CLASS);
        title.setHitTest(false);

        close.addClass(CLOSE_CLASS);
        close.attachListener(() -> {
            WindowFrame target = frame;
            if (target != null) target.requestClose();
            onClosed.emit();
        });

        header.addClass(HEADER_CLASS);
        header.addChild(icon);
        header.addChild(title);
        header.addChild(close);
        addInternalChild(header);
        addInternalChild(thumbnail);

        titleTooltip = Tooltip.attach(title, "");

        // A PRESS ANYWHERE THAT IS NOT THE CLOSE BUTTON activates the window. On the bubble phase, so a
        // press on the button itself is that button's -- the two booleans are additive, and target-only
        // would never hear a press that landed on a child at all.
        events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            if (isWithin(event.getTarget(), close)) return;
            onActivated.emit();
        }, false, true);
    }

    private static boolean isWithin(@Nullable UIElement element, UIElement ancestor) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    /**
     * Shows or hides the picture according to whether there is one.
     *
     * <p>Cheap and idempotent, and called per frame while a preview is up: a window can be minimised
     * <em>while</em> its own preview is open — that is what pressing its entry does — and the panel has
     * to stop claiming a picture it can no longer draw.</p>
     *
     * <p>Collapsing rather than showing an empty box, because an empty box reads as a window that
     * renders nothing rather than as a window that is not there to render.</p>
     */
    public void syncThumbnail() {
        boolean has = thumbnail.hasPicture();
        if (has == thumbnailShown) return;
        thumbnailShown = has;
        StyleGroup.importantPipeline(thumbnail.getStyle().getLayoutGroup(),
                l -> l.display(has ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    private boolean thumbnailShown = true;

    /** Points the preview at a window. Cheap enough to call while it is moving between entries. */
    public WindowPreview setFrame(@Nullable WindowFrame frame) {
        this.frame = frame;
        thumbnail.setFrame(frame);
        String text = frame == null ? "" : frame.getTitle();
        title.setText(text);
        titleTooltip.setText(text);
        applyIcon(frame == null ? null : frame.iconName());
        syncThumbnail();
        return this;
    }

    /**
     * The window's icon, drawn as this slot's own overlay.
     *
     * <p><b>On the slot, not in a child of it.</b> The slot is what the sheet sizes — {@code __pre-icon__}
     * is 10x10 — and an overlay is painted into its element's box, so a fresh child with no size of its
     * own is a 0x0 box with an icon in it: nothing appears, and nothing about the tree says why. Exactly
     * what {@code Taskbar.applyIcon} does for the entry beneath this.</p>
     *
     * <p>Through {@link CgUiSvg#ofIcon}, never {@code of(path)}: that is what binds the light/dark
     * variant at draw time, and the one time a caller reached past it every {@code icon()} in every
     * stylesheet drew the light file forever.</p>
     */
    private void applyIcon(@Nullable String iconName) {
        CgUiSvg glyph = iconName == null ? null : CgUiSvg.ofIcon(iconName);
        icon.setDisplayed(glyph != null);
        if (glyph == null) return;
        StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(), g -> g.overlay(glyph));
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
