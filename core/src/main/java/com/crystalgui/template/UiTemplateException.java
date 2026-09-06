package com.crystalgui.template;

import javax.annotation.Nullable;

/**
 * A {@code .cgui} document that cannot be loaded, naming the document, the node and what is wrong.
 *
 * <p>Every refusal is one of these and none of them is a silent default: a document that names a kind
 * nobody registered, a state key a widget does not declare, a slot that does not exist, an override for
 * an id the template has not got, a cycle, or a format version newer than this reader. A template that
 * loads is a template that will inflate.</p>
 *
 * <pre>{@code
 * try {
 *     UiTemplate template = UiTemplates.load("mymod:ui/status");
 * } catch (UiTemplateException broken) {
 *     LOGGER.error(broken.getMessage());   // "mymod:ui/status at root.children[1]: ..."
 * }
 * }</pre>
 *
 * <p>Unchecked, because a document shipped in a jar is a build-time mistake and a loader that catches it
 * per call site would be catching a typo.</p>
 */
public final class UiTemplateException extends RuntimeException {

    private final String document;

    @Nullable
    private final String nodePath;

    public UiTemplateException(String document, @Nullable String nodePath, String problem) {
        super(document + (nodePath == null ? "" : " at " + nodePath) + ": " + problem);
        this.document = document;
        this.nodePath = nodePath;
    }

    public UiTemplateException(String document, @Nullable String nodePath, String problem, Throwable cause) {
        super(document + (nodePath == null ? "" : " at " + nodePath) + ": " + problem, cause);
        this.document = document;
        this.nodePath = nodePath;
    }

    /** Which document — an asset id, or a workspace path. */
    public String document() {
        return document;
    }

    /** Where in it, as {@code root.children[1].children[0]}, or null when the header is at fault. */
    @Nullable
    public String nodePath() {
        return nodePath;
    }
}
