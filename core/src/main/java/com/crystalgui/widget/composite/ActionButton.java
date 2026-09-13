package com.crystalgui.widget.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuBuilder;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * An icon button for an action — IntelliJ's {@code ActionButton}: a command it runs, or a menu it drops down.
 *
 * <pre>{@code
 * // runs a command against the tree, however far the button is from it
 * ActionButton collapse = ActionButton.command(TreeViewCommands.COLLAPSE_ALL)
 *         .icon("crystalgui:general/action/collapseAll")
 *         .context(tree);
 *
 * // drops a menu down, and wears the dropdown gutter saying so
 * ActionButton add = ActionButton.menu("New File or Directory…", MenuId.EXPLORER_NEW)
 *         .icon("crystalgui:general/action/add")
 *         .context(tree);
 * }</pre>
 *
 * <ul>
 *   <li>The tooltip is IntelliJ's help tooltip: the command's label, its <b>live</b> chord beside it from the
 *       context's keymap, and the {@linkplain #description description} or {@linkplain #hint hint} under
 *       them — re-read each time the pointer arrives, after {@link Tooltip#WAIT_CLASS}'s wait.</li>
 *   <li>A command button greys while its command is disabled, re-asked a few times a second while it is in a
 *       tree, and runs nothing then.</li>
 *   <li>{@code context} is what the command and the menu resolve against. Name the element the action is
 *       about; a button in a header is outside the view it acts on, and the data walk only goes outward.</li>
 *   <li>A {@code Button} by tag, carrying {@link #ACTION_CLASS}; the icon is written at DEFAULT origin, so a
 *       sheet may still replace it.</li>
 * </ul>
 */
public class ActionButton extends Button {

    /** On every action button. Not {@code __action__}, which a notification's and a banner's text buttons carry. */
    public static final String ACTION_CLASS = "__action-button__";

    /** How often a command button re-asks whether its command is enabled, in seconds. */
    private static final float REFRESH_SECONDS = 0.25f;

    private final CommandRegistry registry;

    @Nullable
    private final String commandId;

    @Nullable
    private final String menuLabel;

    @Nullable
    private final Supplier<ContextMenu> menu;

    @Nullable
    private Supplier<UIElement> context;

    @Nullable
    private String description;

    @Nullable
    private String hintCommand;

    @Nullable
    private String hintPhrase;

    private final Tooltip tooltip;

    /** The open menu and its submenus, while one is open. */
    private final List<Menu> live = new ArrayList<>();

    private boolean ticking;

    private float sinceRefresh;

    /** A button that runs {@code commandId}, from the global registry. */
    public static ActionButton command(String commandId) {
        return new ActionButton(CommandRegistry.global(), Objects.requireNonNull(commandId, "commandId"), null, null);
    }

    /** A button that drops down everything contributed to {@code menu}, named {@code label} in its tooltip. */
    public static ActionButton menu(String label, MenuId menu) {
        Objects.requireNonNull(menu, "menu");
        return menu(label, () -> ContextMenu.of(menu));
    }

    /** A button that drops down the menu {@code build} describes each time it opens. */
    public static ActionButton menu(String label, Supplier<ContextMenu> build) {
        return new ActionButton(CommandRegistry.global(), null, Objects.requireNonNull(label, "label"),
                Objects.requireNonNull(build, "build"));
    }

    protected ActionButton(CommandRegistry registry, @Nullable String commandId, @Nullable String menuLabel,
                           @Nullable Supplier<ContextMenu> menu) {
        super(Button.NAME, "");
        this.registry = registry;
        this.commandId = commandId;
        this.menuLabel = menuLabel;
        this.menu = menu;
        addClass(ACTION_CLASS);
        // NOT A FOCUS STOP, as IntelliJ's ActionButton is not: pressing one leaves the keys where they were, or
        // with whatever the press lands in around it.
        setFocusPolicy(FocusPolicy.NONE);
        if (menu != null) setDropdownMark(true);
        // BEFORE the tooltip's own listener, so the text is current when it shows.
        onMouseEnter.attachListener((element, event) -> refreshTooltip(), false, false);
        // A WAIT, as Hide has: a title line of glyphs is crossed on the way to the one wanted.
        tooltip = Tooltip.attach(this, "");
        tooltip.addClass(Tooltip.WAIT_CLASS);
        onPressed.connect(this::activate);
    }

    /** Draws {@code iconName} — {@code "crystalgui:general/action/add"} — as the button's glyph. */
    public ActionButton icon(String iconName) {
        CgUiSvg glyph = CgUiSvg.ofIcon(iconName);
        CgUiDrawable drawn = glyph == null ? CgUiDrawable.EMPTY : glyph;
        StyleGroup.defaultPipeline(getStyle().getGeneralGroup(), g -> g.overlay(drawn));
        return this;
    }

    /** What the command and the menu resolve against. This button itself when none is named. */
    public ActionButton context(UIElement element) {
        Objects.requireNonNull(element, "element");
        this.context = () -> element;
        return this;
    }

    /**
     * As {@link #context(UIElement)}, asked each time — for a view whose content is replaced under the button,
     * as a tool window that follows the active editor replaces its panel. A null answer means this button.
     */
    public ActionButton context(Supplier<UIElement> element) {
        this.context = Objects.requireNonNull(element, "element");
        return this;
    }

    /** A second tooltip line. */
    public ActionButton description(@Nullable String text) {
        this.description = text;
        return this;
    }

    /**
     * A second tooltip line naming another command's live chord — {@code {}} is replaced by it, and the line
     * is left out while that command has no chord.
     *
     * <pre>{@code
     * expand.hint(TreeViewCommands.EXPAND_ALL, "Press {} to expand all nodes");
     * }</pre>
     */
    public ActionButton hint(String commandId, String phrase) {
        this.hintCommand = Objects.requireNonNull(commandId, "commandId");
        this.hintPhrase = Objects.requireNonNull(phrase, "phrase");
        return this;
    }

    /** The command this runs, or null for a menu button. */
    @Nullable
    public String commandId() {
        return commandId;
    }

    /** Whether a press drops a menu down rather than running a command. */
    public boolean opensMenu() {
        return menu != null;
    }

    /** The tooltip, brought up to date: the label, the live chord as its shortcut, the description or hint. */
    public Tooltip tooltip() {
        refreshTooltip();
        return tooltip;
    }

    private UIElement source() {
        UIElement named = context == null ? null : context.get();
        return named != null ? named : this;
    }

    private void activate() {
        UIDocument window = document();
        if (menu != null) {
            // A SECOND PRESS CLOSES what the first opened: the button is the menu's invoker, so light dismiss
            // leaves the menu to it.
            if (!live.isEmpty()) {
                MenuBuilder.discard(live);
                return;
            }
            if (window == null) return;
            Menu built = menu.get().build(registry, source());
            if (built.getItemCount() == 0) return;
            UIElement before = window.focus().focused();
            live.addAll(MenuBuilder.present(built, this, window));
            built.onClosed.connect(() -> {
                MenuBuilder.discard(live);
                // THE KEYS GO BACK where they were: the menu took them for its rows, and a detached row is
                // nowhere a keymap or an undo scope can resolve from. ContextMenu.attach does the same.
                if (before != null && before.document() != null && window.focus().focusable(before)) {
                    window.focus().requestPointerFocus(before);
                }
            });
            built.showFor(this, this);
            return;
        }
        Command command = registry.get(commandId);
        if (command != null && command.isEnabled(CommandContext.of(source()))) {
            registry.run(commandId, CommandContext.of(source()));
        }
    }

    /** Greys the button while its command is disabled. A menu button is always live. */
    public void refreshEnabled() {
        if (commandId == null) return;
        Command command = registry.get(commandId);
        boolean enabled = command != null && command.isEnabled(CommandContext.of(source()));
        if (enabled != isEnabled()) setEnabled(enabled);
    }

    private void refreshTooltip() {
        String label = menuLabel;
        KeyChord chord = null;
        if (commandId != null) {
            Command command = registry.get(commandId);
            label = command == null ? commandId : command.getLabel();
            chord = Keymap.acceleratorFor(source(), commandId);
        }
        String detail = description;
        KeyChord hinted = hintCommand == null ? null : Keymap.acceleratorFor(source(), hintCommand);
        if (hinted != null) detail = hintPhrase.replace("{}", hinted.toString());
        if (!label.equals(tooltip.getBaseText())) tooltip.setText(label);
        String shown = chord == null ? "" : chord.toString();
        if (!shown.equals(tooltip.getShortcut())) tooltip.setShortcut(shown);
        String line = detail == null ? "" : detail;
        if (!line.equals(tooltip.getDescription())) tooltip.setDescription(line);
    }

    @Override
    protected void connected() {
        super.connected();
        refreshEnabled();
        UIDocument window = document();
        if (commandId == null || ticking || window == null) return;
        ticking = true;
        window.animation().every(this, this::tick);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        // CLEARED, or a button shown again after a detach never ticks: the service drops the hook with it.
        ticking = false;
        MenuBuilder.discard(live);
    }

    private boolean tick(float deltaSeconds) {
        sinceRefresh += deltaSeconds;
        if (sinceRefresh < REFRESH_SECONDS) return true;
        sinceRefresh = 0f;
        refreshEnabled();
        return true;
    }
}
