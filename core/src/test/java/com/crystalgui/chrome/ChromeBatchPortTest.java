package com.crystalgui.chrome;

import com.crystalgui.chrome.menu.MenuBarView;
import com.crystalgui.chrome.notification.NotificationsView;
import com.crystalgui.chrome.palette.QuickPick;
import com.crystalgui.chrome.preferences.NavigatorView;
import com.crystalgui.chrome.problems.ProblemsPanel;
import com.crystalgui.chrome.status.Breadcrumbs;
import com.crystalgui.chrome.status.StatusBarView;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.data.CommandTarget;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Menu;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * M6.3's chrome on the new engine — the menu bar, the palette, and the shell's panels.
 *
 * <p>The batch's own tests, not a port of the old engine's: what these assert is the handful of
 * things that changed shape in the move, led by the one the invariant rows say cannot be tested any
 * other way.</p>
 */
public class ChromeBatchPortTest extends UiDocumentTestBase {

    private static MenuId menu(String name) {
        // A FRESH ID PER TEST. `MenuId.submenu` declarations live on the interned id and outlive
        // `resetForTesting`, so a shared constant leaks a section from one test into the next.
        return MenuId.of(name + "-" + System.nanoTime());
    }

    /**
     * <b>A menu bar resolves its commands against the focus owner the opening press just destroyed.</b>
     *
     * <p>The press that opens a menu blurs whatever was focused <em>before</em> it dispatches, and a
     * bar title takes no focus, so by the time the menu is built {@code focused()} is null. Falling
     * back to the bar looks harmless and is not: the bar sits <em>above</em> the workbench content, so
     * a context resolved from it can see the shell and none of the panels — File ▸ Save stayed enabled
     * while Split Right, Next Tab, Close Panel and every Edit entry greyed out, which reads as those
     * commands being broken rather than as the context being wrong.</p>
     *
     * <p><b>Driven at a POINT.</b> Dispatching straight at the title skips the focus-losing walk
     * entirely, which is how sixteen passing tests shipped this bug on the old engine. Asserted on the
     * source a command's {@code enabledWhen} actually SAW, because that is the only observable — the
     * menu opens either way, and every row in it is present either way.</p>
     */
    @Test
    public void aMenuBarRemembersTheFocusOwnerThePressDestroys() {
        withDefaultStyles();
        CommandRegistry registry = new CommandRegistry();
        MenuId edit = menu("edit");
        AtomicReference<CommandTarget> seen = new AtomicReference<>();
        registry.register(Command.of("test.cut", "Cut")
                .menu(edit, "clipboard", 0)
                .enabledWhen(context -> {
                    seen.set(context.source());
                    return true;
                })
                .run(() -> {
                }));

        MenuBarView bar = new MenuBarView(registry).addMenu(edit, "Edit");
        layout(bar, l -> l.widthPercent(100f).height(22f));
        document.append(bar);

        // THE THING THE USER WAS WORKING IN, below the bar and focusable.
        Button content = new Button("content");
        content.setFocusPolicy(FocusPolicy.CLICK);
        layout(content, l -> l.width(120f).height(30f));
        document.append(content);
        frame();
        frame();

        Box contentBox = boxOf(content);
        assertNotNull("the content has no box", contentBox);
        click(contentBox.worldX() + 20f, contentBox.worldY() + 15f);
        frame();
        assertSame("the fixture never focused the content, so this can prove nothing",
                content, document.focus().focused());

        UINode title = bar.children().stream()
                .filter(c -> c.hasClass(MenuBarView.TITLE_CLASS))
                .findFirst().orElse(null);
        assertNotNull("the bar built no title", title);
        Box titleBox = boxOf(title);
        assertNotNull("the title has no box", titleBox);
        press(titleBox.worldX() + titleBox.width() / 2f, titleBox.worldY() + titleBox.height() / 2f);
        release(titleBox.worldX() + titleBox.width() / 2f, titleBox.worldY() + titleBox.height() / 2f);
        frame();

        assertEquals("the menu opened against " + seen.get() + " -- the bar itself, or nothing, "
                        + "rather than the control the user was working in",
                content, seen.get());
    }

    /**
     * <b>The registry carries {@code enabled}; it never filters, and the menu DIMS.</b>
     *
     * <p>The palette copied VS Code's hide-disabled behaviour once and listed one command of nine,
     * because every {@code enabledWhen} resolves outward from focus and "nothing focused" answers no
     * to everything. A menu whose rows appear and vanish is also a menu whose rows are never in the
     * same place twice.</p>
     *
     * <p>Asserted on the row COUNT with a disabled command in the section, which is the only shape
     * that separates "dimmed" from "dropped": a renderer that hid it would build one row, and the row
     * it built would be the enabled one — plausible in every screenshot.</p>
     */
    @Test
    public void aDisabledCommandStillGetsARow() {
        CommandRegistry registry = new CommandRegistry();
        MenuId id = menu("file");
        registry.register(Command.of("test.open", "Open").menu(id, "io", 0).run(() -> {
        }));
        registry.register(Command.of("test.save", "Save").menu(id, "io", 1)
                .enabledWhen(context -> false)
                .run(() -> {
                }));

        List<MenuEntry.Item> rows = new ArrayList<>();
        registry.sections(id, CommandContext.of(null)).forEach(section -> section.entries()
                .forEach(entry -> {
                    if (entry instanceof MenuEntry.Item item) rows.add(item);
                }));

        assertEquals("a disabled command lost its row: " + rows, 2, rows.size());
        MenuEntry.Item save = rows.stream()
                .filter(item -> item.command().getId().equals("test.save"))
                .findFirst().orElse(null);
        assertNotNull("the disabled command was filtered out rather than dimmed: " + rows, save);
        // THE COUNTER-ASSERTION: a registry that reported every row enabled would satisfy the row
        // count above and leave the renderer nothing to dim with, which is the same bug wearing a
        // different hat.
        assertFalse("the row is there and claims to be enabled", save.enabled());
        assertTrue("the enabled one lost its row too",
                rows.stream().anyMatch(item -> item.command().getId().equals("test.open")
                        && item.enabled()));
    }

    /**
     * A {@link MenuBarView} opens a real {@link Menu}, and closes it again.
     *
     * <p>The bar is the batch's only widget that builds a whole other widget on demand, and the two
     * things that can go wrong are both silent: a menu built into a detached tree, and a menu that
     * never opens because its section came back empty. Both look like nothing happening.</p>
     */
    @Test
    public void aMenuBarOpensAndClosesItsMenu() {
        withDefaultStyles();
        CommandRegistry registry = new CommandRegistry();
        MenuId view = menu("view");
        registry.register(Command.of("test.zoom", "Zoom In").menu(view, "zoom", 0).run(() -> {
        }));

        MenuBarView bar = new MenuBarView(registry).addMenu(view, "View");
        layout(bar, l -> l.widthPercent(100f).height(22f));
        document.append(bar);
        frame();
        frame();

        bar.open(view);
        frame();
        frame();
        assertEquals("open(id) did not open that menu", view, bar.openMenu());

        bar.close();
        frame();
        assertEquals("the bar still reports a menu open", null, bar.openMenu());
    }

    /**
     * Every chrome panel in the batch lays out with content in it.
     *
     * <p>The same smoke test the two batches before it needed. Every one of these is a container a
     * shipped rule reaches <em>into</em> — that is why not one of them hosts a shadow root — so the
     * failure available to each is the same: a box of {@code Nx0} that is correct in every other
     * observable.</p>
     */
    @Test
    public void every63ChromePanelLaysOutWithItsContent() {
        withDefaultStyles();
        QuickPick pick = new QuickPick();
        ProblemsPanel problems = new ProblemsPanel();
        NotificationsView notifications = new NotificationsView();
        NavigatorView navigator = new NavigatorView();
        StatusBarView status = new StatusBarView();
        Breadcrumbs crumbs = new Breadcrumbs();

        List<UINode> panels = List.of(problems, notifications, navigator, status, crumbs);
        for (UINode panel : panels) {
            layout(panel, l -> l.width(400f).height(200f));
            document.append(panel);
        }
        // A QuickPick opens itself into the document -- it is a promoted overlay, not a child.
        pick.open(document);
        frame();
        frame();

        List<String> offenders = new ArrayList<>();
        for (UINode panel : new ArrayList<>(List.of(pick))) {
            checkBox(offenders, panel);
        }
        for (UINode panel : panels) {
            checkBox(offenders, panel);
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    private void checkBox(List<String> offenders, UINode node) {
        Box box = boxOf(node);
        if (box == null) offenders.add(node.getClass().getSimpleName() + ": no box");
        else if (!(box.width() > 0f) || !(box.height() > 0f)) {
            offenders.add(node.getClass().getSimpleName()
                    + ": measured " + box.width() + "x" + box.height());
        }
    }
}
