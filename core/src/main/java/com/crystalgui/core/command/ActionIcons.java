package com.crystalgui.core.command;

import com.crystalgui.core.command.ActionIcons;
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

    /** A text field with its caret — ours; the set has no rename mark. */
    public static final String RENAME = ACTION + "rename";
    /** Copy's two sheets with a plus on the front one — ours. */
    public static final String DUPLICATE = ACTION + "duplicate";
    /** The set's {@code copy} with a selector's {@code #} in place of its text lines. */
    public static final String COPY_SELECTOR = ACTION + "copySelector";

    // The Library's two view faces.
    public static final String VIEW_ROWS = ACTION + "viewRows";
    public static final String VIEW_CARDS = ACTION + "viewCards";

    /** The eye and the struck-through eye — a {@link Command#whenToggled} pair, never used singly. */
    public static final String SHOW = "crystalgui:general/show";
    public static final String HIDE = "crystalgui:general/hide";

    // Kind glyphs — what a row CREATES rather than what it does to it. Artwork; see carriesItsOwnPalette.
    /** The FILE — what package-info.java is. A row that makes a type wants {@link #JAVA_CLASS}. */
    public static final String JAVA_FILE = "crystalgui:filetypes/java";
    /** The TYPE, as a completion list and a structure view draw it. */
    public static final String JAVA_CLASS = "crystalgui:nodes/java/class";
    public static final String JAVASCRIPT_FILE = "crystalgui:filetypes/javaScript";
    public static final String PACKAGE = "crystalgui:nodes/java/package";

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

    /**
     * Whether {@code iconId} supplies its own colours, so the cascade must leave it alone.
     *
     * <p>Two groups, and the line between them is the one {@code ui/icons/ATTRIBUTION.md} draws:
     * <b>whether the colour carries meaning</b>. An action mark is chrome and follows the row; a kind
     * glyph — anything under {@code filetypes/} or {@code nodes/} — is a drawing of a <i>thing</i>, and
     * Java's red-brown circle and the shader's magenta are how it is recognised. {@link #COLOURED} is
     * the short list of action marks on the artwork side of that line.</p>
     *
     * <p>Derived rather than enumerated for the glyph directories, because they grow every time an icon
     * is pulled from the index and a list here would be the copy that goes stale.</p>
     */
    public static boolean carriesItsOwnPalette(String iconId) {
        return iconId != null
                && (COLOURED.contains(iconId)
                || iconId.startsWith("crystalgui:filetypes/")
                || iconId.startsWith("crystalgui:nodes/"));
    }

    private ActionIcons() {
    }
}
