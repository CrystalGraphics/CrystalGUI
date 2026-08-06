package com.crystalgui.ui.elements.workbench.decoration;

import javax.annotation.Nullable;

/**
 * One contributor's statement about a file — VS Code's {@code IDecorationData}, ported.
 *
 * <h3>What a decoration is for</h3>
 *
 * <p>A row in a file tree has to say several independent things at once: modified, read-only, has errors,
 * untracked, ignored. Written directly into the row renderer these become a chain of {@code if}s that grows
 * a branch per feature and where every new one has to know about the previous four. VS Code's answer, and
 * this port's, is that each is an <b>independent contributor</b> and the row asks a service which one wins.
 * <b>The point of the pattern is that the fifth contributor is free.</b></p>
 *
 * <h3>Colour is a class, not an ARGB</h3>
 *
 * <p>VS Code names a theme colour id here. We name a CSS class, for the same reason
 * {@code FileIconTheme} does and {@code NodePort} reads its wire colour back out of the cascade: the
 * palette belongs in a stylesheet, and a decoration carrying {@code 0xFFE2C08D} would put a theme's colours
 * in Java where no theme can reach them.</p>
 *
 * @param weight        higher wins when two providers decorate the same file. VS Code's own convention;
 *                      errors outrank modified outranks untracked
 * @param styleClass    the CSS class the row carries, e.g. {@code decoration-modified}. Null for none
 * @param letter        a short badge — {@code "M"}, {@code "A"}, {@code "!"}. One or two characters; it is
 *                      drawn in a fixed slot at the row's trailing edge, not measured
 * @param tooltip       human-readable reason
 * @param strikethrough for a file that is ignored or deleted
 * @param bubble        whether an ancestor folder inherits this. <b>This is the whole reason a folder can
 *                      show that something inside it changed</b>, and it is per-decoration rather than
 *                      global because not every one should climb: "modified" should reach the folder,
 *                      "this is a symlink" should not
 */
public record FileDecoration(int weight, @Nullable String styleClass, @Nullable String letter,
                             @Nullable String tooltip, boolean strikethrough, boolean bubble) {

    /** Conventional weights, so two providers written months apart still order sensibly. */
    public static final int WEIGHT_ERROR = 30;
    public static final int WEIGHT_WARNING = 20;
    public static final int WEIGHT_MODIFIED = 10;
    public static final int WEIGHT_INFO = 0;

    public static FileDecoration of(int weight, String styleClass, String letter, String tooltip) {
        return new FileDecoration(weight, styleClass, letter, tooltip, false, true);
    }

    public FileDecoration withBubble(boolean bubble) {
        return new FileDecoration(weight, styleClass, letter, tooltip, strikethrough, bubble);
    }

    public FileDecoration withStrikethrough(boolean strikethrough) {
        return new FileDecoration(weight, styleClass, letter, tooltip, strikethrough, bubble);
    }

    /**
     * The same decoration as it appears on an <b>ancestor folder</b>.
     *
     * <p>Keeps the colour and drops the letter. A folder showing {@code M} would be claiming the folder
     * itself is modified, which is not what happened — VS Code shows the colour on the folder name and the
     * badge only on the file, and the distinction is the entire information content of the bubble.</p>
     */
    public FileDecoration bubbled() {
        return new FileDecoration(weight, styleClass, null, tooltip, false, true);
    }

    public boolean isEmpty() {
        return styleClass == null && letter == null && !strikethrough;
    }
}
