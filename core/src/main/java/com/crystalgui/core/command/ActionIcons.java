package com.crystalgui.core.command;

/**
 * The names of the shipped action marks — what {@link Command#icon} and {@code ActionButton.icon} take.
 *
 * <pre>{@code
 * Command.of(CUT, "Cut").icon(ActionIcons.CUT);
 * ActionButton.command(EXPAND_ALL).icon(ActionIcons.EXPAND_ALL);
 * }</pre>
 *
 * <h3>Why a class and not the string</h3>
 *
 * <p>An icon id that names no file fails <b>silently</b>: {@code CgUiSvg.ofIcon} answers null,
 * {@code MenuItem} substitutes {@code CgUiDrawable.EMPTY}, and the row draws a reserved, empty column.
 * Nothing logs and nothing throws, so a typo survives a build, a test run and a screenshot. A constant
 * turns that into a compile error, which is the whole of the argument.</p>
 *
 * <p>The ids were literals in twelve files before this existed, several of them repeated — `copy` in
 * three, `collapseAll` in six.</p>
 *
 * <h3>Only marks that follow the cascade belong here</h3>
 *
 * <p>Every icon named below is authored as {@code currentColor}, so it takes the row's colour and dims
 * with {@code :disabled}. That is not decoration: {@code CgUiSvg.ofIcon} resolves a name through
 * {@code FileIconTheme.withVariant}, whose default is {@code Variant.DARK}, so a name here reaches
 * {@code <name>_dark.svg} when one exists — and <b>both halves of a pair have to carry
 * {@code currentColor}</b> or the light file's conversion is invisible. {@code CommandIconsResolveTest}
 * asserts both properties over every command that names one.</p>
 *
 * <p>The one exception is named apart, under {@link #COLOURED}: a multi-tone mark whose palette carries
 * the meaning cannot be tinted, because flattening it to one tone leaves a silhouette. It ships as two
 * real drawings instead, and {@code withVariant} picks the one for the background.</p>
 */
public final class ActionIcons {

    private static final String ACTION = "crystalgui:general/action/";

    // Clipboard — the three marks a reader already knows, and the only ones IntelliJ puts in a context menu.
    public static final String CUT = ACTION + "cut";
    public static final String COPY = ACTION + "copy";
    public static final String PASTE = ACTION + "paste";

    // History.
    public static final String UNDO = ACTION + "undo";
    public static final String REDO = ACTION + "redo";

    // File and state.
    public static final String SAVE = ACTION + "save";
    public static final String REFRESH = ACTION + "refresh";
    public static final String RESET = ACTION + "reset";
    public static final String SETTINGS = ACTION + "settings";

    // Coloured — see COLOURED below before using one.
    public static final String INTENTION_BULB = ACTION + "intentionBulb";
    public static final String ADD_FILE = ACTION + "addFile";
    public static final String ADD_DIRECTORY = ACTION + "addDirectory";

    // Tree and panel title actions.
    public static final String EDIT = ACTION + "edit";
    public static final String ADD = ACTION + "add";
    /** Every verb that destroys the thing it names — Delete, Delete Group, Remove, Remove from Group. */
    public static final String DELETE = ACTION + "delete";
    public static final String EXPAND_ALL = ACTION + "expandAll";
    public static final String COLLAPSE_ALL = ACTION + "collapseAll";
    public static final String LOCATE = ACTION + "locate";

    /** The transform box with its corner handles — ours; the set has no free-transform mark. */
    public static final String FREE_TRANSFORM = ACTION + "freeTransform";

    // The Library's two view faces.
    public static final String VIEW_ROWS = ACTION + "viewRows";
    public static final String VIEW_CARDS = ACTION + "viewCards";

    /** The eye and the struck-through eye — a {@link Command#whenToggled} pair, never used singly. */
    public static final String SHOW = "crystalgui:general/show";
    public static final String HIDE = "crystalgui:general/hide";

    /**
     * Marks whose palette is the meaning, and which therefore may <b>not</b> be tinted.
     *
     * <p>{@code intentionBulb}'s amber glass says "there is something to do here"; the blue {@code +} on
     * {@code addFile} and {@code addDirectory} is the whole of what makes them <i>new</i> rather than
     * <i>open</i>. Recolouring any of the three from CSS flattens it to one tone and throws that away,
     * which is why they keep their hard-coded fills and their {@code _dark} twins — two real drawings,
     * one per background, exactly as JetBrains ships them.</p>
     *
     * <p>So these are the marks that do not dim with {@code :disabled}. {@code CommandIconsResolveTest}
     * reads this set rather than carrying a second list of exceptions.</p>
     */
    public static final java.util.Set<String> COLOURED =
            java.util.Set.of(INTENTION_BULB, ADD_FILE, ADD_DIRECTORY);

    private ActionIcons() {
    }
}
