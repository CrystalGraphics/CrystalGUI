package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The MRU window switcher — {@code Mod+Tab}.
 *
 * <p>Ported in shape from GNOME Shell's {@code js/ui/switcherPopup.js} and {@code js/ui/altTab.js},
 * which is GPL: constants and behaviour only, not a line of its code. Windows' Alt+Tab agrees on every
 * point below, which is what makes them conventions rather than one project's choices.</p>
 *
 * <h3>MRU, not z-order — and this is the whole reason the switcher exists beside the taskbar</h3>
 *
 * <p>A minimised window has left the stacking order entirely, so a z-ordered list either omits it or
 * invents a position for it. The registry keeps most-recently-<em>activated</em> order for exactly this,
 * and the switcher is the only thing that reads it. The taskbar reads open order instead, because a bar
 * whose entries move on every activation is unusable — the two orders are both real and neither is
 * derivable from the other.</p>
 *
 * <h3>The 150ms delay is the feature, not a loading spinner</h3>
 *
 * <p>The panel is invisible for {@link #POPUP_DELAY_NANOS} after the chord is pressed, and a release
 * inside that window commits without ever having drawn anything. That is what makes the tap-to-bounce
 * gesture work: {@code Mod+Tab} released immediately swaps to the last window with no flash of UI, and
 * holding the modifier a moment longer is what asks to <em>see</em> the list. Every switcher anyone has
 * used behaves this way, and one without the delay reads as flickering rather than as fast.</p>
 *
 * <h3>Selection starts on the SECOND entry</h3>
 *
 * <p>The first entry is the window you are already in, so starting there would make the common gesture a
 * no-op. Forward starts at index 1, backward at the end. With only one window there is nothing to move
 * to and it starts at 0.</p>
 *
 * <h3>Tiles, because a switcher without pictures is a menu</h3>
 *
 * <p>Windows' Alt+Tab is a grid of live thumbnails with the window's icon and title above each, and the
 * picture is the whole point: titles collide (three editors, four browser tabs) and a thumbnail is how
 * anyone actually recognises the window they want. Each tile reuses {@link WindowThumbnail}, which is
 * the same machinery the taskbar's hover preview draws through — so a <b>minimised</b> window shows the
 * snapshot taken on its way out rather than a blank box, which is exactly the case a switcher exists to
 * cover and the one a live-only mirror cannot.</p>
 *
 * <h3>While it is up, it gets first refusal on the keyboard</h3>
 *
 * <p>GNOME takes a real modal grab ({@code Main.pushModal}) for the life of the gesture, and the reason
 * is the same one that made {@code Ctrl+Tab} not work here at all: arrow keys reach the focused element,
 * and a focused editor moves its caret with them. So the keys the switcher acts on are intercepted
 * <b>ahead of dispatch</b>, on the rung a live drag already occupies.</p>
 *
 * <p><b>Tab is deliberately NOT intercepted.</b> Repeating the chord has to keep resolving through the
 * keymap, or the second press stops going through the command and the gesture silently stops being
 * rebindable — which is the whole reason the modifier mask is read from the accelerator too. Everything
 * else falls through as before rather than being swallowed wholesale: a grab is the reference behaviour,
 * and deadening every key in the application on a gesture this short is a larger claim than the feature
 * needs.</p>
 *
 * <h3>The mouse works too, and the overlay is hittable ONLY while it is drawn</h3>
 *
 * <p>Hover selects, a click activates, and each tile carries a close button — Windows' switcher, all
 * three. The overlay covers the whole desktop so its panel can be centred by flexbox rather than by
 * arithmetic, and a full-size element that eats input is the single failure this codebase has met most
 * often — so the thing that makes it safe is that it is {@code display: none} for every frame it is not
 * on screen, which includes the whole of the 150ms delay and therefore the whole of a fast tap. There is
 * no window in which an invisible switcher can swallow a click.</p>
 *
 * <p>A press on the backdrop cancels, as a press outside any menu does. It cannot commit instead: the
 * pointer is nowhere near a tile, so there is nothing to say the user meant the current selection.</p>
 */
public class WindowSwitcher extends UIElement {

    public static final String SWITCHER_CLASS = "__window-switcher__";
    public static final String PANEL_CLASS = "__switcher-panel__";
    public static final String ENTRY_CLASS = "__switcher-entry__";
    public static final String HEADER_CLASS = "__switcher-header__";
    public static final String TITLE_CLASS = "__switcher-title__";
    public static final String CLOSE_CLASS = "__switcher-close__";
    public static final String SELECTED_CLASS = "__selected__";
    public static final String ICON_CLASS = "__icon__";

    /**
     * How long the panel stays invisible after the chord is pressed — GNOME's {@code POPUP_DELAY_TIMEOUT}.
     *
     * @see WindowSwitcher the class note on why this is the feature
     */
    private static final long POPUP_DELAY_NANOS = 150L * 1_000_000L;

    /**
     * How long the switcher waits before committing when <b>no modifier is held at all</b> — GNOME's
     * {@code NO_MODS_TIMEOUT}.
     *
     * <p>Only reachable through a remap: the shipped chords both carry {@code Mod}, and with a modifier
     * the release is what commits. A user who binds the switcher to a bare key has Enter, Space and a
     * click, but nothing that ends the gesture by <em>walking away</em> from it — so without this one it
     * would sit on screen until something was pressed.</p>
     */
    private static final long NO_MODS_NANOS = 1500L * 1_000_000L;

    private final Desktop desktop;

    /** The panel, so the overlay itself can stay a bare full-size centring box. */
    private final UIElement panel = new UIElement();

    /** The MRU snapshot this pass is cycling, taken once when the switcher opens. */
    private final List<WindowFrame> order = new ArrayList<>();
    private final List<Tile> entries = new ArrayList<>();

    private boolean open;
    private int selected;

    /**
     * The modifier bits that hold the switcher open — read from the chord that invoked it, never assumed.
     *
     * @see #holdingMaskFor
     */
    private int holdingMask;

    /** The accent wash under the tiles. @see Taskbar#GLOW_CLASS */
    private final UIElement glow = new UIElement();

    private long openedAt;
    private long lastInteraction;
    private boolean shown;
    private boolean ticking;

    WindowSwitcher(Desktop desktop) {
        this.desktop = desktop;
        addClass(SWITCHER_CLASS);
        panel.addClass(PANEL_CLASS);
        // THE GLOW under the tiles: the bar's accent wash, so the switcher is the bar's material and
        // not a second one. An INTERNAL child of the panel, added before any tile, so buildEntries --
        // which adds and removes tiles by reference -- never sees it and it always paints first.
        glow.addClass(Taskbar.GLOW_CLASS);
        glow.setHitTest(false);
        panel.addInternalChild(glow);
        addInternalChild(panel);
        setPanelShown(false);

        // A PRESS ON THE BACKDROP CANCELS. Target-only, so a press on a tile -- which is a descendant --
        // never reaches this and activates instead.
        onMouseDown.attachListener((element, event) -> cancel(), false, false);
    }

    /**
     * The accent wash under the tiles, for {@link TaskbarDesigner} to retone the whole family at once.
     * Package-private: a theme retones this through {@code --switcher-glow}, and the element itself is
     * only reachable because the designer writes at IMPORTANT origin while it runs.
     */
    UIElement glow() {
        return glow;
    }

    /** Whether the switcher is cycling — true for the invisible part of the gesture as well. */
    public boolean isOpen() {
        return open;
    }

    /** Whether the panel has actually been drawn, which it has not during the first 150ms. */
    public boolean isVisible() {
        return open && shown;
    }

    /** The window that would be activated if the modifier were released now, or null. */
    @Nullable
    public WindowFrame selectedWindow() {
        return open && selected >= 0 && selected < order.size() ? order.get(selected) : null;
    }

    /** What the switcher is offering, in MRU order. Empty when it is not open. */
    public List<WindowFrame> offered() {
        return List.copyOf(order);
    }

    /**
     * The gesture: opens on the first press and advances on every one after it.
     *
     * <p>One entry point for both, because from the keymap's side they are the same keystroke — the
     * command fires again on each repeat of the chord, and whether that means "open" or "next" is state
     * this owns. GNOME reaches the same place through a keybinding action that re-fires while its own
     * grab is held.</p>
     *
     * @param commandId the command that was invoked, so the modifier holding it open can be read from
     *                  <em>that</em> command's live binding rather than assumed to be Ctrl
     */
    public void cycle(boolean forward, String commandId) {
        if (open) {
            advance(forward);
            return;
        }
        begin(forward, commandId);
    }

    private void begin(boolean forward, String commandId) {
        order.clear();
        order.addAll(desktop.registry().switcherOrder());
        if (order.size() < 2) {
            // NOTHING TO SWITCH TO. Opening on one window shows a panel whose only entry is the window
            // already in front, which is a way of saying nothing at some expense.
            order.clear();
            return;
        }
        open = true;
        shown = false;
        revealRequested = false;
        // THE SECOND ENTRY, or the last one going backwards. @see the class note.
        selected = forward ? 1 : order.size() - 1;
        holdingMask = holdingMaskFor(commandId);
        openedAt = System.nanoTime();
        lastInteraction = openedAt;
        buildEntries();
        addToTopLayer();
        wake();
    }

    private void advance(boolean forward) {
        if (order.isEmpty()) return;
        selected = Math.floorMod(selected + (forward ? 1 : -1), order.size());
        lastInteraction = System.nanoTime();
        markSelection();
    }

    /**
     * Commits: the selected window is activated, and the switcher goes.
     *
     * <p>Activated as a POINTER gesture, so focus lands without a ring. The user drove this with the
     * keyboard, but the ring is for focus that moved somewhere the user did not point at — and a switcher
     * is nothing but pointing.</p>
     */
    private void finish() {
        if (!open) return;
        WindowFrame chosen = selectedWindow();
        close();
        if (chosen != null && chosen.state() != WindowState.DESTROYED) desktop.activate(chosen, false);
    }

    /**
     * First refusal on a key press, while the gesture is live.
     *
     * <p>Ported from GNOME's {@code _keyPressHandler}, including the part that is easy to miss: a handled
     * key <b>reveals the panel</b>. Pressing an arrow is an explicit request to see the list, so waiting
     * out the rest of the delay would make the switcher feel unresponsive exactly when it is being used
     * deliberately rather than tapped.</p>
     *
     * <p><b>Left/Right are previous/next and wrap</b>, which is GNOME's; <b>Up/Down move by a ROW</b>,
     * which is not — GNOME's switcher is a single line and spends Up/Down on an app's window sub-list,
     * which we have no equivalent of. Ours wraps into a grid, so a grid is what the arrows navigate.</p>
     *
     * <p><b>Enter and Space commit immediately</b>, modifier still held or not. That is the base class's
     * behaviour and it is the only way to finish the gesture at all if somebody rebinds to a bare key.</p>
     *
     * @return whether the key was consumed, so it goes no further
     */
    public boolean handleKey(int key) {
        if (!open) return false;
        switch (key) {
            case CgKeyCodes.KEY_ESCAPE:
                cancel();
                return true;
            case CgKeyCodes.KEY_RETURN:
            case CgKeyCodes.KEY_NUMPADENTER:
            case CgKeyCodes.KEY_SPACE:
                finish();
                return true;
            case CgKeyCodes.KEY_LEFT:
                advance(false);
                revealNow();
                return true;
            case CgKeyCodes.KEY_RIGHT:
                advance(true);
                revealNow();
                return true;
            case CgKeyCodes.KEY_UP:
                moveByRow(-1);
                revealNow();
                return true;
            case CgKeyCodes.KEY_DOWN:
                moveByRow(1);
                revealNow();
                return true;
            default:
                return false;
        }
    }

    /**
     * Moves a whole row up or down the grid.
     *
     * <p><b>Does not wrap</b>, unlike Left/Right — a grid's vertical edges are edges, and wrapping from
     * the bottom row to the top would move the selection by an amount that depends on how many windows
     * happen to be open. With a single row there is nowhere to go and the key does nothing, which is what
     * every grid does and is why the arrows are not simply aliases for previous/next.</p>
     */
    private void moveByRow(int direction) {
        int columns = columnsPerRow();
        int next = selected + direction * columns;
        if (next < 0 || next >= order.size()) return;
        selected = next;
        lastInteraction = System.nanoTime();
        markSelection();
    }

    /**
     * How many tiles are on the first row, read off the LAYOUT rather than computed.
     *
     * <p>The panel wraps, so the count depends on the tiles' widths — which depend on each window's
     * shape — and on how much room the sheet's {@code max-width} leaves. Nothing in Java knows any of
     * that, and re-deriving it would be a second implementation of flex-wrap that could disagree with
     * the one on screen. Tiles that share a top edge are one row.</p>
     */
    private int columnsPerRow() {
        if (entries.isEmpty()) return 1;
        float top = entries.get(0).getRuntimeCache().getY();
        int count = 0;
        for (Tile tile : entries) {
            if (Math.abs(tile.getRuntimeCache().getY() - top) > 0.5f) break;
            count++;
        }
        return Math.max(1, count);
    }

    /**
     * Asks for the panel as soon as its pictures are measured, without waiting out the rest of the delay.
     *
     * <p>Not {@code setPanelShown(true)} on the spot, which is what GNOME does: a thumbnail takes its
     * shape from the window it shows and can only do that once its own box has been laid out, so
     * revealing before that draws every picture at the sheet's maximum for a frame and then snaps. The
     * measurement costs two or three frames rather than the 150ms, so this is responsive either way.</p>
     */
    private void revealNow() {
        revealRequested = true;
    }

    private boolean revealRequested;

    /**
     * Abandons the gesture without activating anything — Escape.
     *
     * @return whether there was a switcher to cancel
     */
    private boolean cancel() {
        if (!open) return false;
        close();
        return true;
    }

    private void close() {
        open = false;
        shown = false;
        order.clear();
        removeFromTopLayer();
        setPanelShown(false);
    }

    /**
     * The modifier that has to stay down, taken from the invoking command's live accelerator.
     *
     * <p>Read from the keymap rather than hardcoded, so remapping the switcher moves the modifier that
     * holds it open with it. A hardcoded Ctrl would leave a user who rebound to {@code Alt+Grave} with a
     * switcher that committed the instant it opened.</p>
     *
     * <p><b>Shift is stripped.</b> It is the direction qualifier in every switcher there has ever been —
     * {@code Mod+Shift+Tab} is "the same gesture, backwards" — so it cannot also be the key that holds
     * the panel open: letting go of Shift to cycle forwards again would commit mid-cycle. GNOME reaches
     * the same answer by taking the <em>primary</em> modifier of the binding's mask rather than the whole
     * of it. If Shift is all there is, it is used, because a switcher that can never be committed is
     * worse than one that commits early.</p>
     */
    private int holdingMaskFor(String commandId) {
        KeyChord chord = Keymap.acceleratorFor(this, commandId);
        if (chord == null || chord.length() == 0) return 0;
        int mask = chord.at(chord.length() - 1).modifiers();
        int withoutShift = mask & ~CgModifiers.SHIFT;
        return withoutShift != 0 ? withoutShift : mask;
    }

    /**
     * Per frame while the gesture is live.
     *
     * <p>A PULL rather than a listener, and that is the same reasoning {@code TaskbarPreviews} records:
     * the decision — is the modifier still down, has the delay elapsed — is a per-frame question anyway,
     * and polling the modifier state cannot miss a release the way a key-up listener can. It also side-
     * steps the left/right duality entirely: there is no need to know that Alt is two keys.</p>
     */
    private boolean tick() {
        if (!open) return false;
        long now = System.nanoTime();

        if (holdingMask != 0) {
            // RELEASED, so commit. Checked before the reveal below, which is what makes a fast tap
            // produce no panel at all rather than a frame of one.
            if ((currentModifiers() & holdingMask) == 0) {
                finish();
                return false;
            }
        } else if (now - lastInteraction >= NO_MODS_NANOS) {
            finish();
            return false;
        }

        // THE PICTURES ARE MEASURED WHILE THE PANEL IS STILL INVISIBLE, which is the delay paying for
        // itself: a thumbnail takes its shape from the window it is showing and can only do that once its
        // own box has been laid out, so the first frame of a switcher is always one where nothing is the
        // right size yet. 150ms is nine frames of head start, and a tap that never reveals the panel
        // never pays for any of it.
        boolean settled = true;
        for (Tile tile : entries) {
            if (tile.syncSize()) settled = false;
        }

        if (!shown && settled && (revealRequested || now - openedAt >= POPUP_DELAY_NANOS)) {
            shown = true;
            setPanelShown(true);
        }
        return true;
    }

    private static int currentModifiers() {
        var input = CgPlatform.input();
        return input == null ? 0 : input.getCurrentModifiers();
    }

    private void wake() {
        if (ticking) return;
        UIWindow window = getAttachedWindow();
        if (window == null) {
            // NO TREE, so nothing will ever tick this. Committing immediately is the honest outcome --
            // the alternative is a switcher stuck open for good, holding a window list that is going stale.
            finish();
            return;
        }
        ticking = true;
        window.registerTicker(new UIFrameTicker() {
            @Override
            public boolean tickFrame(float deltaSeconds) {
                boolean busy = tick();
                if (!busy) ticking = false;
                return busy;
            }
        });
    }

    /** One tile per window, rebuilt per gesture — the window set changes between one press and the next. */
    private void buildEntries() {
        while (entries.size() > order.size()) {
            Tile spare = entries.remove(entries.size() - 1);
            panel.removeChild(spare);
        }
        for (int index = 0; index < order.size(); index++) {
            WindowFrame frame = order.get(index);
            Tile tile;
            if (index < entries.size()) {
                tile = entries.get(index);
            } else {
                tile = new Tile();
                entries.add(tile);
                panel.addChild(tile);
            }
            tile.show(frame);
        }
        markSelection();
    }

    /**
     * The tile showing the window at an offer index — for a test that needs something to press.
     *
     * <p>Package-private rather than public: a tile is an implementation detail of the panel, and the
     * only caller outside this class is the test that presses one.</p>
     */
    UIElement tileAt(int index) {
        return entries.get(index);
    }

    /** That tile's close button. @see #tileAt */
    Button closeButtonAt(int index) {
        return entries.get(index).close;
    }

    /** Hovering a tile selects it. */
    private void hoverTile(Tile tile) {
        int index = entries.indexOf(tile);
        if (index < 0 || index >= order.size() || index == selected) return;
        selected = index;
        lastInteraction = System.nanoTime();
        markSelection();
    }

    /** Clicking a tile picks that window and finishes, modifier held or not. */
    private void activateTile(Tile tile) {
        int index = entries.indexOf(tile);
        if (index < 0 || index >= order.size()) return;
        selected = index;
        finish();
    }

    /**
     * The tile's close button: asks the window to close, and takes it out of the offer.
     *
     * <p>Through {@code requestClose} rather than {@code destroy}, so a window's policy still decides
     * what closing means — a {@code HIDE_ON_CLOSE} one is put away rather than discarded, which is the
     * distinction the whole lifecycle rests on.</p>
     *
     * <p>The entry goes <b>optimistically</b>, because a close ANIMATES: the frame is not destroyed until
     * its 150ms has played, so waiting for the registry to agree would leave a tile on screen for the
     * window the user just dismissed. Windows removes the thumbnail on the click too.</p>
     */
    private void closeTile(Tile tile) {
        int index = entries.indexOf(tile);
        if (index < 0 || index >= order.size()) return;
        WindowFrame frame = order.get(index);
        lastInteraction = System.nanoTime();
        frame.requestClose();
        order.remove(index);
        if (order.isEmpty()) {
            cancel();
            return;
        }
        if (selected >= order.size()) selected = order.size() - 1;
        buildEntries();
    }

    private void markSelection() {
        for (int index = 0; index < entries.size(); index++) {
            Tile tile = entries.get(index);
            if (index == selected) tile.addClass(SELECTED_CLASS);
            else tile.removeClass(SELECTED_CLASS);
        }
    }

    /**
     * One window: its picture, with its icon and title above.
     *
     * <p><b>Built entirely in the constructor</b>, which is the rule this codebase has now paid for twice:
     * adding a child later can insert a Taffy node into a parent whose children are still being
     * registered, and the crash names an index rather than the widget. Everything a tile can ever need
     * exists before it is attached; {@link #show} only ever fills it in.</p>
     */
    private final class Tile extends UIElement {

        private final UIElement header = new UIElement();
        private final WindowIcon iconSlot = new WindowIcon();
        private final UIText title = new UIText("");
        private final Button close = new Button("");
        private final WindowThumbnail thumbnail = new WindowThumbnail();

        Tile() {
            addClass(ENTRY_CLASS);
            header.addClass(HEADER_CLASS);
            iconSlot.addClass(ICON_CLASS);
            title.addClass(TITLE_CLASS);
            // Sized by its box, stated: the auto-detect reads this title while the switcher is still
            // `display: none` and latches it self-sizing, after which a long title cannot shrink and
            // pushes the tile's close button out of the tile. @see WindowPreview's title
            title.neverSelfSizeWidth();
            close.addClass(CLOSE_CLASS);
            header.addChild(iconSlot);
            header.addChild(title);
            header.addChild(close);
            addChild(header);
            addChild(thumbnail);

            close.attachListener(() -> closeTile(this));
            // HOVER SELECTS, which is Windows' and GNOME's alike. It also counts as interaction, or a
            // switcher invoked with no modifier would time out under a pointer that is plainly in use.
            onMouseEnter.attachListener((element, event) -> hoverTile(this), false, false);
            // AND A PRESS ACTIVATES -- on the DOWN, because a switcher is dismissed by the press itself,
            // so waiting for the release would mean waiting for a click that lands on nothing.
            onMouseDown.attachListener((element, event) -> {
                if (isWithinClose(((UIElement) event.getTarget()))) return;
                activateTile(this);
            }, false, true);
        }

        /**
         * Whether a press landed on this tile's close button.
         *
         * <p>The tile listens on the BUBBLE phase, so a press on the close button reaches it too — and
         * activating the window somebody just asked to close is the one outcome that cannot be right.
         * A press on the button's own children counts, which is why this walks rather than compares.</p>
         */
        private boolean isWithinClose(@Nullable UIElement target) {
            for (UIElement walk = target; walk != null && walk != this; walk = walk.getParent()) {
                if (walk == close) return true;
            }
            return false;
        }

        void show(WindowFrame frame) {
            title.setText(frame.getTitle());
            // THE SAME TILE THE TASKBAR AND THE PREVIEW DRAW. @see WindowIcon
            iconSlot.show(frame.iconName(), frame.getTitle());
            thumbnail.setFrame(frame);
        }

        /** @return whether the picture changed size, so a caller knows the tile is not settled yet */
        boolean syncSize() {
            return thumbnail.syncSize();
        }
    }

    /**
     * Shows or hides the overlay outright.
     *
     * <p>{@code display} rather than opacity, for the reason the whole engine keeps meeting: anything
     * under opacity 1 goes through a screen-sized layer FBO, and this is a full-screen element. A hidden
     * one must also take no space, which {@code display: none} is the only spelling of.</p>
     */
    private void setPanelShown(boolean shown) {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }
}
