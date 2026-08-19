package com.crystalgui.language.run;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsRegistry;

import com.crystalgui.language.run.console.ConsoleSettings;
import com.crystalgui.language.run.console.RunConsole;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConsoleSettings} — a module contributing its own Preferences rows.
 *
 * <p>What is worth pinning is not the values but the <b>reach</b>: {@code WorkbenchSettings} lives in
 * {@code core} and cannot name a console it has never heard of, so if a declaration made from
 * {@code language/} did not arrive in the registry the Run page would simply be absent — and absent is
 * indistinguishable from "this application has no Run panel", which is a real configuration.</p>
 */
public class ConsoleSettingsTest {

    /** The row reaches the registry from outside {@code core}, which is the whole claim. */
    @Test
    public void theBufferSizeIsDeclaredAndFindable() {
        ConsoleSettings.declare();

        Setting<?> found = SettingsRegistry.get().get(ConsoleSettings.BUFFER_KB.getId());
        assertNotNull("a setting declared from language/ never reached the registry", found);
        assertEquals(ConsoleSettings.BUFFER_KB.getId(), found.getId());
    }

    /**
     * <b>Declaring twice is a no-op, not a second row.</b>
     *
     * <p>{@code RunPanels.install} calls this, and a host may install a panel more than once — a second
     * workspace, a console rebuilt after a reload. A registry that accumulated would show the same
     * setting several times in the window.</p>
     */
    @Test
    public void declaringTwiceLeavesOneRow() {
        ConsoleSettings.declare();
        int after = SettingsRegistry.get().all().size();
        ConsoleSettings.declare();
        assertEquals("re-declaring grew the registry", after, SettingsRegistry.get().all().size());
    }

    /** The page exists, or the row lands in a window with nowhere to draw it. */
    @Test
    public void theRunPageIsDeclaredAlongsideIt() {
        ConsoleSettings.declare();
        assertTrue("the Run page was never declared", SettingsCategory.isPage("run"));
    }

    /**
     * <b>The default is the console's own, not a number written twice.</b>
     *
     * <p>Two spellings of one default is the hazard: bump {@code DEFAULT_BUDGET_KB} and the console
     * changes while the Preferences row goes on offering the old value as its reset.
     */
    @Test
    public void theDefaultIsTheConsolesOwn() {
        assertEquals(Integer.valueOf(RunConsole.DEFAULT_BUDGET_KB),
                ConsoleSettings.BUFFER_KB.getDefaultValue());
    }

    /** A floor, because a buffer of nothing is a console that discards output as fast as it arrives. */
    @Test
    public void thereIsAFloorUnderTheBuffer() {
        assertTrue("the minimum is not a usable console", ConsoleSettings.MINIMUM_KB > 0);
        assertTrue("the minimum is above the default, so the setting can never take effect",
                ConsoleSettings.MINIMUM_KB < RunConsole.DEFAULT_BUDGET_KB);
    }
}
