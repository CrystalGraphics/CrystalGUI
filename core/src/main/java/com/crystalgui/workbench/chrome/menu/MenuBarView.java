package com.crystalgui.workbench.chrome.menu;


import com.crystalgui.workbench.chrome.notification.NotificationBalloons;
import com.crystalgui.workbench.chrome.status.StatusBarView;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.data.DataKey;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuBuilder;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The main menu bar — File, Edit, View, … — as a query over {@link CommandRegistry}.
 *
 * <h3>It holds no items</h3>
 *
 * <p>A title is a {@link MenuId} and a label, and pressing it calls
 * {@link MenuBuilder#build}. Nothing here knows what is in the File menu, which is the whole point: a
 * feature adds {@code File ▸ New ▸ Shader Graph} with one {@code .menu(...)} call on its own command and
 * no reference to this class. That is the test the plan set for "seamless rather than parallel", and a bar
 * with a hard-coded item list would fail it while looking identical on day one.</p>
 *
 * <h3>The bar owns which menu is open, not the titles</h3>
 *
 * <p>Because hover-switching is a fact about the <em>bar</em>: with File open, moving onto Edit switches
 * without a click, which no title can decide on its own since it would have to know another title was
 * open. Same reason {@link Menu} owns submenu opening rather than each {@code MenuItem} owning its
 * own.</p>
 *
 * <h3>Press to open, not release</h3>
 *
 * <p>Every native menu bar opens on mouse-<b>down</b>, which is why a title is not a {@code Button}:
 * {@code Button} fires on mouse-up and plays a click sound, so a bar built from one would open a menu
 * only once the button came back up.</p>
 *
 * <p>Press-drag-release works, and it is not free: {@code MenuItem} inherits {@code Button}'s
 * {@code isWasPressTarget()} guard, which correctly refuses a release whose press landed elsewhere. The
 * menu opened from a live press is therefore <b>armed</b>, and a row honours a release whose press landed
 * on the title. @see Menu#armForRelease</p>
 *
 * <h3>Mnemonics</h3>
 *
 * <p>{@code addMenu(MAIN_FILE, "&File")} — the {@code &} marks the mnemonic and is stripped from the
 * label, the Windows convention VS Code also uses. Alt+F opens it from anywhere, and the letter is
 * underlined <b>only while Alt is held</b>, drawn through the CSS Custom Highlight API rather than by
 * splitting the label into three elements. {@code ::highlight(mnemonic)} in {@code default.css}.</p>
 */
public class MenuBarView extends UINode  {

    public static final Name NAME = Name.of("menubarview");

    /**
     * This engine's menu bar, for a command that needs to reach one.
     *
     * <p>Declared HERE rather than in {@code UiDataKeys}, because a {@link DataKey}'s type is the
     * thing it names — and {@code UiDataKeys.MENU_BAR} is a {@code DataKey<ui.elements.chrome
     * .MenuBarView>}, which this class is not. A shared key naming an engine class cannot be shared,
     * which is {@code ConfigDescriptor}'s lesson one level up: the DATA is neutral and the TYPE is
     * not, so the key belongs with the type.</p>
     */
    public static final DataKey<MenuBarView> MENU_BAR =
            DataKey.create("menuBar.new", MenuBarView.class);

    public static final String BAR_CLASS = "__menu-bar__";
    public static final String TITLE_CLASS = "__menu-title__";

    /** On the title whose menu is open. A class rather than {@code :active}, which means "pressed". */
    public static final String OPEN_CLASS = "__open__";

    /** The highlight name {@code ::highlight(mnemonic)} styles. @see com.crystalgui.ui.text.HighlightRegistry */
    public static final String MNEMONIC_HIGHLIGHT = "mnemonic";

    /** Fires when a menu opens or closes, carrying the open id or null. */
    public final Signal.Value<MenuId> onDidChangeOpenMenu = new Signal.Value<>();

    private final CommandRegistry registry;
    private final List<Title> titles = new ArrayList<>();

    /** The open chain, root first — attached to the window, discarded together. @see MenuBuilder#present */
    private final List<Menu> live = new ArrayList<>();

    @Nullable
    private Title openTitle;

    /** Whether the mnemonic underlines are currently drawn — the last answer {@link #tickFrame} saw. */
    private boolean altShown;

    public MenuBarView(CommandRegistry registry) {
        super(NAME);
        this.registry = registry;
        this.registry.contribute(MenuBarView.class, MainMenuCommands::register);
        addClass(BAR_CLASS);
        buildBurger();
        // NOT markAsInternal(). Whether this part is internal to its host is the host's decision -- the
        // workbench adds it with addInternalChild -- and stamping it here RECURSES over a subtree whose
        // titles are added afterwards, so its own host could never take it back out again. Same note
        // StatusBarView carries, and the reason removeSelf silently refused in the first draft.
    }

    /**
     * Adds a top-level menu.
     *
     * @param label the title, optionally with {@code &} before its mnemonic letter — {@code "&File"},
     *              {@code "&Edit"}. A literal ampersand is {@code "&&"}, as on Windows.
     */
    public MenuBarView addMenu(MenuId id, String label) {
        Title title = new Title(id, label);
        titles.add(title);
        append(title);
        return this;
    }

    /** The open menu's id, or null. */
    @Nullable
    public MenuId openMenu() {
        return openTitle == null ? null : openTitle.id;
    }

    public boolean isOpen() {
        return openTitle != null;
    }

    /** Every top-level menu, in bar order. */
    public List<MenuId> menus() {
        List<MenuId> out = new ArrayList<>(titles.size());
        for (Title title : titles) out.add(title.id);
        return out;
    }

    /**
     * Opens {@code id}'s menu, closing whatever was open.
     *
     * <p>Rebuilt on every open rather than kept: enablement, checkmarks and accelerators are all resolved
     * against the context at build time, so a retained menu would show the state of whenever it was last
     * constructed. It is also how a {@link com.crystalgui.core.command.MenuContributor} row can exist at
     * all.</p>
     */
    public void open(MenuId id) {
        Title title = titleFor(id);
        if (title != null) show(title);
    }

    /**
     * Closes whatever this bar has open — a title's menu <b>or the burger's</b>.
     *
     * <p>The early return used to be on {@code openTitle}, which is null while the burger's menu is up:
     * so collapsing the bar, or detaching it, left that chain attached to a host with nothing tracking
     * it. Every close path has to end at one place or the chain outlives what opened it.</p>
     */
    public void close() {
        Title closing = openTitle;
        // CLEARED FIRST, so the onClosed hook below sees no open title and does not re-enter. The same
        // ordering MenuBuilder.discard uses on the chain, and for the same reason.
        openTitle = null;
        MenuBuilder.discard(live);
        if (closing == null) return;
        closing.removeClass(OPEN_CLASS);
        onDidChangeOpenMenu.emit(null);
    }

    private void show(Title title) {
        show(title, false);
    }

    private void show(Title title, boolean fromPress) {
        UIDocument window = document();
        if (window == null) return;
        if (openTitle == title) return;
        close();

        Menu menu = MenuBuilder.build(title.id, registry, contextSource(window));
        // EVERY BAR MENU RESERVES THE MARK GUTTER, whether or not it holds a toggle.
        //
        // Reserving it per-menu is right for a CONTEXT menu — a standalone popup with no toggles
        // should not carry a dead column, which is the trade Menu#HAS_CHECKABLE_CLASS documents. A
        // menu BAR is the case that argument does not cover: its menus are a set the user opens one
        // after another in the same place, so Edit's labels starting 12px left of View's reads as a
        // wobble in the bar rather than as two menus with different contents. Both references align
        // them across the whole bar for that reason.
        //
        // The existing class rather than a new one: it already means "reserve the gutter", and the
        // per-item opt-in stays exactly as it was for everyone else.
        menu.addClass(Menu.HAS_CHECKABLE_CLASS);
        // AN EMPTY MENU IS NOT OPENED. A top-level title whose commands are all contributed by a feature
        // that is not loaded would otherwise open a zero-height popover -- which reads as the bar being
        // broken rather than as the menu being empty, because there is nothing on screen to see.
        if (menu.getItemCount() == 0) return;

        openTitle = title;
        title.addClass(OPEN_CLASS);
        live.addAll(MenuBuilder.present(menu, this, window));
        menu.onClosed.connect(() -> {
            // Guarded on this still being the open one: close() detaches by calling hide(), which fires
            // this again, and switching menus hides the outgoing one while the incoming is being built.
            if (openTitle == title) close();
        });
        // ANCHORED TO THE TITLE, so it flips and clamps like any other popup and nothing here writes
        // left/top. AnchoredPlacement is the only writer of those on a promoted popup -- see the
        // invariant; a second writer fights it every frame.
        //
        // The title is passed as the INVOKER as well as the anchor, which is what stops light dismiss
        // closing the menu on the very press that is about to reopen it -- the dropdown-button flicker,
        // and here it would make a second press on an open title unable to close it.
        // ARMED WHEN THE BUTTON IS STILL DOWN, so press-drag-release chooses the row it is released over.
        // Only from a press: hover-switching and Alt must not leave a menu that the next stray release
        // anywhere would activate. @see Menu#armForRelease
        if (fromPress) menu.armForRelease();
        menu.showFor(title, title);
        onDidChangeOpenMenu.emit(title.id);
    }

    // ── The burger ──────────────────────────────────────────────────────────────────────────────

    /** On the single button that replaces the titles when the bar is collapsed. */
    public static final String BURGER_CLASS = "__burger__";

    /** One of the three bars inside it. Geometry, so it works with no theme loaded. */
    public static final String BURGER_BAR_CLASS = "__burger-bar__";

    /** On the bar itself while collapsed, so a theme can restyle the whole row at once. */
    public static final String COLLAPSED_CLASS = "__collapsed__";

    private final UINode burger = new UINode();

    private boolean collapsed;

    /** null means "decide from the width"; non-null is a caller overriding that. @see #setCollapsed */
    @Nullable
    private Boolean collapseOverride;

    /** The width the titles need, measured the last time they were laid out expanded. */
    private float naturalWidth;

    private void buildBurger() {
        burger.addClass(BURGER_CLASS);
        // THREE ELEMENTS, not a glyph and not a new CgUiShape kind. The bundled fonts have no ☰ and it
        // renders as tofu -- the trap UIText records for U+2026 and ViewContainer for its close mark --
        // and three styled boxes need no renderer change while staying entirely themeable.
        for (int i = 0; i < 3; i++) {
            UINode stripe = new UINode();
            stripe.addClass(BURGER_BAR_CLASS);
            stripe.setHitTest(false);
            burger.append(stripe);
        }
        burger.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            toggleBurgerMenu();
            event.stopPropagation();
        }, false, true);
        append(burger);
        applyCollapsed(false);
    }

    /**
     * Collapses the bar to a single burger, or expands it — IntelliJ's New UI behaviour.
     *
     * <p>Calling this <b>overrides the automatic width check</b>, permanently for this bar. Pass null to
     * hand it back. Both are wanted: the automatic collapse is what a narrow window needs, and a user who
     * has chosen "always a burger" must not have it undone the moment the window is widened.</p>
     */
    public MenuBarView setCollapsed(@Nullable Boolean value) {
        this.collapseOverride = value;
        applyCollapsed(value != null ? value : shouldAutoCollapse());
        return this;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * Collapses when the titles no longer fit.
     *
     * <h3>Why this cannot oscillate</h3>
     *
     * <p>The bar is a stretched row, so its own width is the window's and <b>collapsing does not change
     * it</b> — which is what makes the comparison stable. The other half is that {@link #naturalWidth} is
     * only ever recorded while expanded: read while collapsed it would be zero (the titles are
     * {@code display: none}) and the bar could never decide to expand again.</p>
     */
    /**
     * A standing post-layout hook, which is what the {@code onLayoutChanged} override became.
     *
     * <p>Layout is one pass with no feedback into it here, so anything that READS a measured box goes
     * after it. Registered from {@link #connected} and owned by this node, so it is dropped when the
     * bar leaves the tree.</p>
     */
    private boolean measureNaturalWidth(float deltaSeconds) {
        if (!collapsed) {
            float measured = 0;
            for (Title title : titles) measured += title.laidOutWidth();
            if (measured > 0) naturalWidth = measured;
        }
        if (collapseOverride == null) applyCollapsed(shouldAutoCollapse());
        return true;
    }

    private boolean shouldAutoCollapse() {
        if (naturalWidth <= 0) return false;
        // The `<= 0` guard below is what an unmeasured bar means, and a null box is the same: hold
        // whatever collapse state we already had rather than deciding from nothing.
        Box box = box();
        float available = box == null ? 0f : box.contentBoxWidth();
        if (available <= 0) return collapsed;
        // Hysteresis: expanding needs a little more room than collapsing gave up, so a window dragged to
        // exactly the boundary does not flicker between the two on every frame.
        return collapsed ? available < naturalWidth + 8f : available < naturalWidth;
    }

    private void applyCollapsed(boolean value) {
        this.collapsed = value;
        if (value) addClass(COLLAPSED_CLASS);
        else removeClass(COLLAPSED_CLASS);
        // IMPORTANT origin, like every other Java-written geometry here: this is structure, not theme, and
        // a sheet must not be able to leave a collapsed bar showing both.
        StyleGroup.inlinePipeline(burger.getStyle().getLayoutGroup(),
                l -> l.display(value ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        for (Title title : titles) {
            StyleGroup.inlinePipeline(title.getStyle().getLayoutGroup(),
                    l -> l.display(value ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
        }
        if (value) close();
    }

    /**
     * The burger's menu: every top-level menu, as a submenu.
     *
     * <p>The same {@link MenuBuilder} calls the titles make, so a collapsed bar and an expanded one cannot
     * show different things — which is the whole reason the collapse is a <em>presentation</em> rather
     * than a second menu structure.</p>
     */
    private void toggleBurgerMenu() {
        UIDocument window = document();
        if (window == null) return;
        if (!live.isEmpty()) {
            MenuBuilder.discard(live);
            return;
        }
        Menu menu = new Menu();
        for (Title title : titles) {
            Menu child = MenuBuilder.build(title.id, registry, contextSource(window));
            if (child.getItemCount() == 0) continue;
            menu.addSubmenu(title.text.getText(), child);
        }
        if (menu.getItemCount() == 0) return;
        live.addAll(MenuBuilder.present(menu, this, window));
        menu.onClosed.connect(() -> MenuBuilder.discard(live));
        menu.armForRelease();
        menu.showFor(burger, burger);
    }

    // ── Keyboard across the bar ─────────────────────────────────────────────────────────────────

    /**
     * Left/Right with a menu open moves to the adjacent one — the ARIA menubar pattern.
     *
     * <p>Reached only when {@link Menu} declined the key: it consumes Left whenever it has a parent to
     * close back into, and consumes Right only when the focused row actually opened a submenu. So a Right
     * on an ordinary row falls through to here, and a Left inside a submenu does not — which is exactly
     * the split both references make.</p>
     *
     * <p>Wraps at both ends, like Tab traversal and like {@code Menu}'s own Up/Down.</p>
     */
    private boolean moveAlongBar(int step) {
        if (openTitle == null || titles.size() < 2) return false;
        int current = titles.indexOf(openTitle);
        if (current < 0) return false;
        for (int offset = 1; offset < titles.size(); offset++) {
            Title candidate = titles.get(Math.floorMod(current + step * offset, titles.size()));
            show(candidate);
            // A neighbour whose menu is empty is not opened at all, and stopping there would strand the
            // user on a title with nothing under it. Step past it instead.
            if (openTitle == candidate) return true;
        }
        return false;
    }

    /**
     * What commands resolve against — <b>the focus owner as of when the bar was invoked</b>.
     *
     * <h3>Why it is remembered and not read</h3>
     *
     * <p>A menu bar acts on whatever you were working in: File ▸ Save saves the active editor, Edit ▸ Undo
     * undoes in the focused document. But <b>the press that opens the menu has already destroyed that
     * answer.</b> {@code UIInputHandler.emitMouseDown} calls {@code emitAndLoseFocus} <em>before</em> it
     * dispatches, and a title is {@link FocusPolicy#NONE}, so nothing takes the focus it just gave up —
     * by the time this runs, {@code getFocusedElement()} is null.</p>
     *
     * <p>Falling back to the bar looks harmless and is not: the bar is a sibling <em>above</em> the
     * workbench content, so a context resolved from it can see {@code CrystalEditor} but not the dock, the
     * editor, the graph or the explorer. The symptom is precise and misleading — File ▸ Save stayed
     * enabled (it resolves against an ancestor of the bar) while Split Right, Next Tab, Close Panel, every
     * Graph entry and every Edit entry greyed out, so it read as those commands being broken rather than
     * as the context being wrong.</p>
     *
     * <p>So the bar tracks focus itself, which is also what IntelliJ does — its actions resolve against
     * the focus owner recorded when the menu was invoked. Anything inside the bar or inside a live
     * {@link Menu} is rejected, because a menu takes focus for its own rows the moment it opens and would
     * otherwise overwrite the very answer this exists to keep.</p>
     */
    private UINode contextSource(UIDocument window) {
        UINode focused = window.focus().focused();
        if (isUsableSource(focused)) return focused;
        if (isUsableSource(lastFocused)) return lastFocused;
        return this;
    }

    /** Whether {@code element} can stand for "what the user was working in". */
    private boolean isUsableSource(@Nullable UINode element) {
        // DETACHED IS UNUSABLE, and this is not defensive coding: the remembered element is routinely the
        // thing a command just closed -- a tab, a file row -- and a context resolved from a detached
        // subtree finds none of its ancestors, so every command silently greys again.
        if (element == null || element.document() == null) return false;
        for (UINode walk = element; walk != null; walk = walk.parent()) {
            if (walk == this || walk instanceof Menu) return false;
        }
        return true;
    }

    /** The last thing focused that was neither this bar nor a row of one of its menus. */
    @Nullable
    private UINode lastFocused;

    @Nullable
    private Title titleFor(MenuId id) {
        for (Title title : titles) {
            if (title.id == id) return title;
        }
        return null;
    }

    // ── Alt ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * <p>{@code onWindowChanged(previous, current)} has no counterpart — the node tree reports connect
     * and disconnect separately — and the split is faithful: the old hook closed unconditionally and
     * re-installed only when there was a window, which is exactly {@link #disconnected} then this.</p>
     */
    @Override
    protected void disconnected() {
        // A bar detached with a menu open leaves the chain parented to a host in a tree nobody is
        // painting -- invisible, still registered, and still the answer to openMenu().
        close();
    }

    @Override
    protected void connected() {
        close();
        document().animation().afterLayout(this, this::measureNaturalWidth);
        UIDocument current = document();
        if (current == null) return;
        // CAPTURE PHASE ON THE ROOT, which is the only way Alt+F can work from anywhere: a menu bar is
        // never focused, so a bubbling listener on the bar itself would hear nothing. Capture also puts it
        // ahead of a text field, which is correct -- Alt+letter is reserved for menus on every platform
        // that has them.
        //
        // GUARDED ON STILL BEING IN THIS WINDOW rather than detached later, because attachListener
        // returns the element and there is no way to take a listener off again. A bar moved to a second
        // window would otherwise keep answering Alt+F in the first one -- invisibly, since the menu it
        // opened would be built against a tree nobody is painting.
        current.events.getGroup(KeyboardEvent.Down.class)
                .attachListener((element, event) -> {
                    if (document() == current) onKeyDown(event);
                }, true, false);
        // THE FOCUS OWNER, REMEMBERED. @see #contextSource for why reading it at open time cannot work.
        // Capture on the root for the same reason as the keys: the bar is never focused, so it has to
        // watch the whole tree rather than wait to be told.
        current.events.getGroup(FocusEvent.Focus.class)
                .attachListener((element, event) -> {
                    if (document() != current) return;
                    UINode target = ((UINode) event.getTarget());
                    if (isUsableSource(target)) lastFocused = target;
                }, true, false);
        // LEFT/RIGHT ACROSS THE BAR, in the BUBBLE phase -- the opposite of the two above, and the
        // difference is the whole mechanism. Menu handles these first and consumes them when it has
        // something to do with them (Left with a parent to close into, Right on a row with a submenu);
        // what reaches here is only what it declined, which is precisely when "move to the next menu" is
        // the right answer. A capture listener would steal Right from every submenu in the bar.
        current.events.getGroup(KeyboardEvent.Down.class)
                .attachListener((element, event) -> {
                    if (document() != current || openTitle == null) return;
                    boolean moved = switch (event.getKeyCode()) {
                        case CgKeyCodes.KEY_LEFT -> moveAlongBar(-1);
                        case CgKeyCodes.KEY_RIGHT -> moveAlongBar(1);
                        default -> false;
                    };
                    if (moved) event.stopPropagation();
                }, false, true);
        // DISARMS THE DRAG-RELEASE on any release, wherever it lands. Without it a press that opened a
        // menu and released over nothing would leave the chain armed, and the NEXT unrelated release
        // inside it -- a plain click somewhere else entirely -- would activate a row. One-shot arming is
        // only one-shot if something reliably ends it.
        //
        // BUBBLE, NOT CAPTURE, and getting this backwards disables the feature outright: capture runs
        // root-to-target, so it would clear the flag before the row it was released over ever gets to
        // read it. Bubble runs after the target phase, which is where MenuItem's own handler lives.
        current.events.getGroup(MouseEvent.Up.class)
                .attachListener((element, event) -> {
                    for (Menu menu : new ArrayList<>(live)) menu.disarmForRelease();
                }, false, true);
        // A REGISTRATION PER WINDOW, ending itself once the bar is elsewhere. There is deliberately no
        // unregisterTicker -- a ticker leaves only by returning false -- so a shared `this` registration
        // could never say which window it was done with. Same idiom as NotificationBalloons, and for the
        // same reason.
        current.animation().every(this, delta -> document() == current && tickFrame(delta));
    }

    private void onKeyDown(KeyboardEvent.Down event) {
        if (!CgModifiers.hasAlt(event.getModifiers())) return;
        // NOT WHILE SOMEBODY IS TYPING. A mnemonic is a global affordance and a focused text field is a
        // local one, and the local one wins -- otherwise Alt+E in the editor's find bar opens the Edit menu
        // instead of toggling Preserve Case, and no per-field workaround can fix it because this listener
        // sees the key first. The same predicate `allowWhileTyping` already uses.
        UIDocument window = document();
        UINode focused = window == null ? null : window.focus().focused();
        if (focused != null && focused.consumesTextInput()) return;
        char typed = Character.toUpperCase(event.getCharacter());
        for (Title title : titles) {
            if (title.mnemonic != typed || typed == 0) continue;
            // TOGGLES, so Alt+F twice closes again rather than rebuilding the same menu -- and rebuilding
            // is not harmless: it would discard the chain the second Alt+F is being dispatched through.
            if (openTitle == title) close();
            else show(title);
            event.stopPropagation();
            event.preventDefault();
            return;
        }
    }

    /**
     * Shows and hides the mnemonic underlines with the Alt key.
     *
     * <p>Polled rather than driven by key events, because the interesting transition is the key going
     * <em>up</em> while focus is somewhere else entirely — and a key-up listener on the root would have to
     * be right about every path that can swallow one. Two integer reads a frame, and no state to get out
     * of step.</p>
     */
    public boolean tickFrame(float deltaSeconds) {
        boolean alt = CgModifiers.hasAlt(CgPlatform.input().getCurrentModifiers());
        if (alt == altShown) return true;
        altShown = alt;
        for (Title title : titles) title.showMnemonic(alt);
        return true;
    }

    // ── The titles ──────────────────────────────────────────────────────────────────────────────

    /** One top-level entry. Not a {@code Button} — see the class note on press-to-open. */
    private final class Title extends UINode {

        private final MenuId id;
        private final UIText text = new UIText("");
        private final char mnemonic;
        private final int mnemonicIndex;

        Title(MenuId id, String label) {
            this.id = id;
            addClass(TITLE_CLASS);
            // NOT FOCUSABLE. The bar is reached with Alt, never with Tab -- a menu bar in the tab order
            // puts six stops between the window and its first real control, which is the roving-tabindex
            // problem FocusPolicy already records for TabView.
            setFocusPolicy(FocusPolicy.NONE);

            String stripped = strip(label);
            this.mnemonicIndex = mnemonicIndexOf(label);
            this.mnemonic = mnemonicIndex < 0 ? 0
                    : Character.toUpperCase(stripped.charAt(mnemonicIndex));
            text.setText(stripped);
            text.setHitTest(false);
            append(text);

            onMouseDown.attachListener((element, event) -> {
                if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
                if (openTitle == Title.this) close();
                else show(Title.this, true);   // still held -- see Menu#armForRelease
                // CONSUMED, or the press also reaches light dismiss for the menu we just opened.
                event.stopPropagation();
            }, false, true);

            // HOVER-SWITCHING, and the guard is what makes it a switch rather than an opener: hovering a
            // title with nothing open does nothing at all, which is what every native bar does and the
            // thing people notice instantly when it is missing.
            onMouseEnter.attachListener((element, event) -> {
                if (openTitle != null && openTitle != Title.this) show(Title.this);
            }, false, true);
        }

        /** Its settled width, or zero while it has no box — a collapsed title has none. */
        float laidOutWidth() {
            Box box = box();
            return box == null ? 0f : box.width();
        }

        /**
         * Underlines the mnemonic letter, or stops.
         *
         * <p>A {@link TextRange} over one character rather than three elements — splitting the label into
         * before/letter/after would reflow the bar every time Alt is pressed, and worse, would re-shape
         * across two span boundaries, so the title would shift by a fraction of a pixel. That is the
         * invariant {@code UIText} already records about highlights not being spans.</p>
         */
        void showMnemonic(boolean show) {
            if (mnemonicIndex < 0) return;
            if (show) {
                text.highlights().set(MNEMONIC_HIGHLIGHT,
                        TextRange.of(mnemonicIndex, mnemonicIndex + 1));
            } else {
                text.highlights().remove(MNEMONIC_HIGHLIGHT);
            }
        }
    }

    // ── Label parsing ───────────────────────────────────────────────────────────────────────────

    /** {@code "&File"} → {@code "File"}, {@code "&&"} → {@code "&"}. */
    public static String strip(String label) {
        StringBuilder out = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            if (i + 1 < label.length() && label.charAt(i + 1) == '&') {
                out.append('&');
                i++;
            }
        }
        return out.toString();
    }

    /** Where the mnemonic lands in the STRIPPED label, or -1 when there is none. */
    public static int mnemonicIndexOf(String label) {
        int stripped = 0;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c != '&') {
                stripped++;
                continue;
            }
            if (i + 1 < label.length() && label.charAt(i + 1) == '&') {
                stripped++;
                i++;
                continue;
            }
            // The marker itself contributes nothing to the stripped label, so the letter after it sits at
            // exactly the count so far. Computing this from the RAW index is the obvious mistake and is
            // wrong for every mnemonic that is not the first character.
            return i + 1 < label.length() ? stripped : -1;
        }
        return -1;
    }

}
