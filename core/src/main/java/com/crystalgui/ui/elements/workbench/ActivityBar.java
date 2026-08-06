package com.crystalgui.ui.elements.workbench;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rail of tool-window buttons down the edge of the workbench — IntelliJ's tool window stripe, and
 * VS Code's Activity Bar.
 *
 * <h3>What it is a view of</h3>
 *
 * <p>One button per <b>singleton</b> panel type. That filter is the whole definition and it is already
 * stated on the panel type: {@link DockPanelDescriptor#isSingleton()} distinguishes "one instance, hidden
 * and reshown" from "a document, of which there are many". Documents never appear on a bar in either
 * editor — they live in the editor area and are reached by opening a file — so a rail listing them would
 * grow without bound and duplicate the tab strip.</p>
 *
 * <h3>Every button runs a command</h3>
 *
 * <p>The bar holds no toggling logic of its own: a click runs {@code view.<typeId>} through the
 * {@link CommandRegistry}, which is the same command a keybinding or a menu item would run. This is
 * VS Code's arrangement exactly — the Activity Bar item for the Explorer and {@code Ctrl+Shift+E} both
 * invoke {@code workbench.view.explorer} — and it is the difference between a bar that <em>presents</em>
 * a capability and one that is a second, subtly different way to reach it. The second kind is where
 * "the button works but the shortcut doesn't" comes from.</p>
 *
 * <h3>State is polled, and that is the cheap option here</h3>
 *
 * <p>A button is {@code :checked} while its panel is open. That fact lives in the dock layout, which
 * changes for reasons the bar cannot see — a panel closed from its own tab, a layout restored, a drag
 * that emptied a leaf. Rather than have the dock announce every one of those, each frame asks the
 * workbench whether each panel is open: a handful of {@code leafContaining} walks over a tree that is a
 * few nodes deep, against a set of buttons that is single digits. {@code addClass}/{@code removeClass}
 * both no-op when nothing changed, so a settled frame touches no element and invalidates no style.</p>
 *
 * <p>The comparison worth making is with the tab presentation seam, which went the other way: there, the
 * work per refresh was building a drawable, which is not free, and the change had an owner who knew when
 * it happened. Here it is an integer comparison and there is no such owner.</p>
 *
 * <h3>Deliberately not here yet</h3>
 *
 * <p>Overflow into a {@code …} menu, drag-to-reorder, right-click hide, activity badges, and
 * {@code Alt+1..9}. All are real parts of both originals and none of them changes the shape above: the
 * overflow menu needs more tool windows than exist to be anything but empty, and a badge is an overlay on
 * a button that already knows which panel it is.</p>
 */
public class ActivityBar extends UIElement {

    /** The rail itself, so a theme can give it a width and a background. */
    public static final String BAR_CLASS = "__activity-bar__";

    /** One tool-window button. */
    public static final String ITEM_CLASS = "__activity-item__";

    /** Command ids are {@code view.} plus the panel type — {@code view.project}, {@code view.problems}. */
    public static final String COMMAND_PREFIX = "view.";

    /**
     * How far a tooltip sits off the rail, in logical pixels.
     *
     * <p>In Java rather than CSS because it is an argument to placement, not a box property — nothing in
     * the cascade positions a promoted popup, and {@code AnchoredPlacement} is deliberately the only thing
     * that writes {@code left}/{@code top} on one.</p>
     */
    private static final float TOOLTIP_GAP = 4f;

    private final Workbench workbench;

    /** Panel type → its button, so a refresh updates rather than rebuilds. */
    private final Map<String, Button> buttons = new LinkedHashMap<>();

    public ActivityBar(Workbench workbench) {
        this.workbench = workbench;
        addClass(BAR_CLASS);
        markAsInternal();
    }

    /** The rail builds its own buttons; it holds nothing a caller puts there. */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public static String commandIdFor(String typeId) {
        return COMMAND_PREFIX + typeId;
    }

    /**
     * Registers a toggle command for every singleton panel type, and builds a button for each.
     *
     * <p>Idempotent per type, so it is safe to call again after a host registers more panels — which it
     * does: {@code CrystalEditor} adds its inspector and emitted-source panels after the workbench is
     * constructed. A type that arrives late gets its button on the next call rather than never.</p>
     */
    public void sync(CommandRegistry commands) {
        for (DockPanelDescriptor descriptor : workbench.panels().descriptors()) {
            if (!descriptor.isSingleton()) continue;
            String typeId = descriptor.typeId();

            // The COMMAND is ensured before the button-exists check, not after. Conflating the two makes
            // "already has a button" mean "already has a command", which is only true while there is
            // exactly one command registry -- and it is the second registry that silently gets an
            // incomplete command set, with a rail of buttons that look fine and a palette missing half
            // its entries.
            String commandId = commandIdFor(typeId);
            if (!commands.contains(commandId)) {
                commands.register(Command.of(commandId, descriptor.title())
                                         .run(() -> workbench.togglePanel(typeId)));
            }
            if (buttons.containsKey(typeId)) continue;

            ItemButton button = new ItemButton(workbench, typeId);
            button.addClass(ITEM_CLASS);
            // Not in the tab sequence. This is the ARIA roving-tabindex case the engine already models:
            // a rail of eight buttons is one Tab press to skip past, not eight -- and every one of them
            // is reachable from the command palette anyway, which is the accessible path that matters.
            button.setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
            applyIcon(button, descriptor.icon());
            // The label is the tooltip, because the button is icon-only. Without it the rail is a column
            // of glyphs with no way to learn what they are -- which is the one complaint the New UI's
            // icon-only stripe reliably attracts.
            // TO THE SIDE, not below. The rail is one button wide, so a tooltip below covers the next
            // button down -- the one you were about to read. Both editors put stripe tooltips beside the
            // button for that reason. AnchoredPlacement still flips it when there is no room, so a rail
            // anchored right gets them on the left with no extra configuration.
            Tooltip.attach(button, descriptor.title())
                    .setSide(AnchoredPlacement.Side.RIGHT)
                    .setGap(TOOLTIP_GAP);
            button.attachListener(() -> commands.run(commandId));

            buttons.put(typeId, button);
            addInternalChild(button);
        }
        refresh();
    }

    /** Lets every button's {@code :checked} state be re-evaluated if the dock has moved under it. */
    public void refresh() {
        for (Button button : buttons.values()) ((ItemButton) button).revalidate();
    }

    /**
     * A button whose {@code :checked} state <b>is</b> whether its panel is open.
     *
     * <h3>Derived, never stored</h3>
     *
     * <p>{@code UIElement.isChecked()} is bound to the {@code :checked} pseudo-class, so overriding it is
     * the whole implementation — the same one line that makes {@code tab:checked} work. Deriving rather
     * than storing matters here more than usual: a panel can close for reasons the bar never sees (its own
     * tab's close button, a layout restore, a drag that empties a leaf), and a stored flag would be wrong
     * until something thought to correct it.</p>
     *
     * <p><b>The invalidation is the part that is easy to miss.</b> A pseudo-class is only re-evaluated when
     * something says the element's identity may have changed; without that the selector is matched once
     * and the rail stays lit for a panel that is long closed. This is the trap {@code nodeport:blank}
     * already documents. {@link #revalidate()} therefore compares against the last answer and invalidates
     * only on a change — so a settled frame costs one boolean comparison per button and touches nothing.</p>
     */
    private static final class ItemButton extends Button {

        private final Workbench workbench;
        private final String typeId;
        private boolean lastKnownOpen;

        ItemButton(Workbench workbench, String typeId) {
            super("");
            this.workbench = workbench;
            this.typeId = typeId;
            this.lastKnownOpen = workbench.isPanelOpen(typeId);
        }

        @Override
        public boolean isChecked() {
            return workbench.isPanelOpen(typeId);
        }

        void revalidate() {
            boolean open = workbench.isPanelOpen(typeId);
            if (open == lastKnownOpen) return;
            lastKnownOpen = open;
            invalidateStyleMatch();
        }
    }

    private static void applyIcon(Button button, String iconName) {
        if (iconName == null) return;
        CgUiSvg glyph = CgUiSvg.of(FileIconTheme.toResourcePath(FileIconTheme.withVariant(iconName)));
        if (glyph == null) return;
        UIElement slot = new UIElement();
        // Unhittable, so the press lands on the button rather than on its own icon -- click-focus targets
        // the exact element hit, never the nearest focusable ancestor.
        slot.setHitTest(false);
        StyleGroup.defaultPipeline(slot.getStyle().getGeneralGroup(),
                g -> g.overlay(glyph));
        button.setPreIcon(slot);
    }
}
