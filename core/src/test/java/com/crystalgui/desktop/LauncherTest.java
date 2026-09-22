package com.crystalgui.desktop;

import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.launcher.Launcher;
import com.crystalgui.desktop.launcher.LauncherButton;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher — <b>what can run</b>, beside the taskbar that shows what is running.
 *
 * <p>The assertions are about the two things a launcher is: an honest view of the registry, and a
 * search that finds what somebody actually types. Both are engine-side and neither needs a frame.</p>
 */
public class LauncherTest extends UiDocumentTestBase {

    private Desktop desktop;

    @Before
    public void openADesktop() {
        Desktop.setAnimationsEnabled(false);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        document.update(800f, 600f);
    }

    /** A manifest with a launch factory that opens one bare window. */
    private static ApplicationKind kindOf(String id, String name, String... keywords) {
        return ApplicationKind.of(id, name)
                .keywords(keywords)
                .launch(context -> {
                    WindowFrame frame = new WindowFrame(name);
                    context.desktop().addWindow(frame);
                    return new Stub(id, name, frame);
                });
    }

    private Launcher launcherOn(Desktop on) {
        Taskbar taskbar = on.taskbar();
        assertNotNull("the desktop has no taskbar to launch from", taskbar);
        LauncherButton button = taskbar.start();
        assertNotNull("the taskbar has no start button", button);
        return button.launcher();
    }

    private static List<String> namesOf(Launcher launcher) {
        return launcher.shown().stream().map(ApplicationKind::displayName).toList();
    }

    @Test
    public void theTaskbarCarriesAStartButton() {
        assertNotNull("there is no way into the launcher from the strip", launcherOn(desktop));
    }

    @Test
    public void everyInstalledApplicationIsListed() {
        desktop.applications().install(kindOf("test:alpha", "Alpha"));
        desktop.applications().install(kindOf("test:beta", "Beta"));

        Launcher launcher = launcherOn(desktop);
        launcher.refill();

        List<String> shown = namesOf(launcher);
        assertTrue("an installed application was not listed: " + shown, shown.contains("Alpha"));
        assertTrue("an installed application was not listed: " + shown, shown.contains("Beta"));
    }

    @Test
    public void searchMatchesTheMiddleOfANameAndNotJustItsStart() {
        desktop.applications().install(kindOf("test:mid", "Zephyr Quibble"));

        Launcher launcher = launcherOn(desktop);
        launcher.search("quibble");

        // SUBSTRING, NOT PREFIX. Typing the second word of a two-word name is what makes a
        // prefix-matching launcher feel broken.
        assertEquals("the second word of a name found nothing",
                List.of("Zephyr Quibble"), namesOf(launcher));
    }

    @Test
    public void searchFindsAnApplicationByAKeywordItsNameDoesNotContain() {
        desktop.applications().install(kindOf("test:kw", "Zephyr Quibble", "wobblestruck"));

        Launcher launcher = launcherOn(desktop);
        launcher.search("wobblestruck");

        assertEquals("the keyword slot is not being consulted at all",
                List.of("Zephyr Quibble"), namesOf(launcher));
    }

    /**
     * The profiler is reachable from the launcher, by name and by a word nobody would guess it
     * declares — which is the whole question this surface was built to answer.
     */
    @Test
    public void theShippedApplicationsAreListedAndFindable() {
        Launcher launcher = launcherOn(desktop);
        launcher.refill();

        List<String> installed = namesOf(launcher);
        assertTrue("Frame Profiler is not installed on a plain desktop: " + installed,
                installed.contains("Frame Profiler"));
        assertTrue("Crystal Editor is not installed on a plain desktop: " + installed,
                installed.contains("Crystal Editor"));

        // NOBODY SEARCHES FOR "frame profiler" when a frame drops; they search for "fps".
        launcher.search("fps");
        assertTrue("the profiler cannot be found by the word somebody would actually type",
                namesOf(launcher).contains("Frame Profiler"));
    }

    @Test
    public void searchIgnoresCase() {
        desktop.applications().install(kindOf("test:case", "Zephyr Quibble"));
        Launcher launcher = launcherOn(desktop);

        launcher.search("ZEPHYR");
        assertEquals(List.of("Zephyr Quibble"), namesOf(launcher));
    }

    @Test
    public void aQueryThatMatchesNothingEmptiesTheList() {
        desktop.applications().install(kindOf("test:fp", "Frame Profiler"));
        Launcher launcher = launcherOn(desktop);
        launcher.refill();

        launcher.search("zzzz");
        assertTrue("a query matching nothing still listed something", launcher.shown().isEmpty());
    }

    @Test
    public void refillClearsTheQuery() {
        desktop.applications().install(kindOf("test:a", "Alpha"));
        desktop.applications().install(kindOf("test:b", "Beta"));

        Launcher launcher = launcherOn(desktop);
        launcher.refill();
        int all = launcher.shown().size();

        launcher.search("alpha");
        assertEquals(1, launcher.shown().size());

        // OPENING IT AGAIN SHOWS EVERYTHING. A launcher that reopened onto the last query would
        // answer "no apps installed" to somebody who had typed a typo and closed it.
        launcher.refill();
        assertEquals(all, launcher.shown().size());
        assertTrue(launcher.searchField().getText().isEmpty());
    }

    @Test
    public void theListFollowsTheRegistryRatherThanASnapshotTakenOnce() {
        Launcher launcher = launcherOn(desktop);
        launcher.refill();
        int before = launcher.shown().size();

        // An application registers when its jar's service runs, which can be after the first open.
        desktop.applications().install(kindOf("test:late", "Late Arrival"));
        launcher.refill();

        assertEquals("a late registration never reached the launcher",
                before + 1, launcher.shown().size());
        assertTrue(namesOf(launcher).contains("Late Arrival"));
    }

    @Test
    public void theStartButtonTogglesRatherThanReopening() {
        Launcher launcher = launcherOn(desktop);
        LauncherButton button = desktop.taskbar().start();

        button.toggle();
        assertTrue("the start button did not open the launcher", launcher.isOpen());
        button.toggle();
        assertFalse("clicking the start button while open did not close it", launcher.isOpen());
    }

    /** One running application, for a manifest that has to return something. */
    private record Stub(String id, String name, WindowFrame window) implements Application {

        @Override
        public ApplicationKind kind() {
            return ApplicationKind.of(id, name);
        }

        @Override
        public WindowFrame mainWindow() {
            return window;
        }

        @Override
        public boolean open(Resource resource) {
            return false;
        }

        @Override
        public void activate() {
        }

        @Override
        public void dispose() {
        }
    }
}
