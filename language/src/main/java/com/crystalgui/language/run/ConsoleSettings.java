package com.crystalgui.language.run;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsRegistry;

import java.util.List;

/**
 * What the console lets you change — the cycle buffer, and nothing else.
 *
 * <h3>Declared from {@code language/}, which is the whole reason this class exists</h3>
 *
 * <p>{@code WorkbenchSettings} is {@code core}'s and cannot name a console it has never heard of. The
 * registry takes declarations from anywhere, so a module contributes its own rows the same way
 * {@code JavaLanguage} contributes services and {@code RunPanels} contributes a panel — and an
 * application without this module gets a Preferences window with no Run page rather than a page with
 * nothing behind it.</p>
 *
 * <h3>The buffer is a setting; soft wrap deliberately is not</h3>
 *
 * <p>IntelliJ exposes its console cycle buffer in Settings and warns about chatty processes, which here
 * is the normal case — a per-tick handler. {@link RunConsole#DEFAULT_BUDGET_KB} is a guess at what
 * suits a machine, and the person who knows is the one whose machine it is.</p>
 *
 * <p>Soft wrap was the obvious second row and would have been a mistake. It is already remembered per
 * workspace through the panel's own session state, and a global default on top of that is <b>two writers
 * for one value</b>: the setting would be applied at some moment of its own choosing and the restored
 * state at another, and which won would depend on attach order. That is the shape of a control that
 * changes by itself. IntelliJ makes the same split — its console wrap is a toolbar toggle it remembers,
 * not a Settings row.</p>
 */
public final class ConsoleSettings {

    private ConsoleSettings() {
    }

    /**
     * How much output the transcript keeps, in KB.
     *
     * <p>Bounded below at something usable rather than left open: a buffer of 0 is a console that
     * discards output as fast as it arrives, which reads as the feature being broken rather than as a
     * setting being set to nothing.</p>
     */
    public static final Setting<Integer> BUFFER_KB =
            Setting.integer("run.consoleBufferKb", "Console buffer size (KB)",
                            RunConsole.DEFAULT_BUDGET_KB)
                    .description("How much output the console keeps. The oldest lines are dropped past "
                            + "this, and the panel says how many. A script printing every tick will "
                            + "reach it in minutes.");

    /** The smallest buffer that is still a console rather than a hole. @see #BUFFER_KB */
    public static final int MINIMUM_KB = 16;

    /**
     * What each line is stamped with.
     *
     * <p><b>A setting rather than a decision, because decorating output is a real trade.</b> IntelliJ's
     * console shows the process's bytes and nothing else, and somebody reading a script's own aligned
     * output wants exactly that — a prefix pushes their table sideways and makes a copied line text the
     * script never printed. But an event-driven script prints the same sentence at 10:14 and at 10:47,
     * and without a clock the transcript cannot say whether anything is still happening.</p>
     *
     * <p>Spelled with the enum's own constant names so the two cannot disagree — the same rule
     * {@code WorkbenchSettings.SORT_ORDER} follows.</p>
     */
    public static final Setting<String> LINE_PREFIX =
            Setting.select("run.consoleLinePrefix", "Stamp console lines with",
                            List.of(ConsolePrefix.Style.NONE.name(),
                                    ConsolePrefix.Style.TIME.name(),
                                    ConsolePrefix.Style.FULL.name()),
                            ConsolePrefix.Style.FULL.name())
                    .description("NONE shows exactly what the script printed. TIME adds [22:59:20]. "
                            + "FULL adds the script line that printed it as well.");

    /** The style in force at {@code panel}. Unknown values fall back rather than throwing. */
    public static ConsolePrefix.Style prefixStyle(RunPanel panel) {
        try {
            return ConsolePrefix.Style.valueOf(panel.resolve(LINE_PREFIX));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // A HAND-EDITED settings file is the case this covers, and a console that refused to draw
            // would be a worse answer than one that draws the default.
            return ConsolePrefix.Style.FULL;
        }
    }

    /**
     * Declares the set. Idempotent — the registry replaces a same-id declaration, and this is the same
     * instance every time.
     */
    public static void declare() {
        // The PAGE, declared rather than derived from the id, so adding a setting cannot grow a node in
        // somebody's navigation by accident. Same rule WorkbenchSettings states for its four.
        SettingsCategory.page("run", "Run");
        SettingsRegistry.get().register(BUFFER_KB);
        SettingsRegistry.get().register(LINE_PREFIX);
    }

    /** The value in force at {@code panel}, clamped. Settings resolve outward through the tree. */
    public static int bufferKb(RunPanel panel) {
        return Math.max(MINIMUM_KB, panel.resolve(BUFFER_KB));
    }
}
