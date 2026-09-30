package com.crystalgui.ui.event;

import lombok.Getter;

/**
 * An input method's text in progress — the DOM's {@code compositionupdate}. Sent to the focus owner
 * while a Japanese, Chinese or Korean IME composes; the finished text then arrives as ordinary
 * {@link KeyboardEvent.Down} characters.
 *
 * <pre>{@code
 * events.getGroup(CompositionEvent.class).attachListener((el, event) -> {
 *     showComposing(event.getText(), event.getCaret());   // replaces the previous run
 *     event.stopPropagation();                           // consumed: the host shows nothing of its own
 * }, false, false);
 * }</pre>
 *
 * <ul>
 *   <li>Each event carries the <b>whole</b> run, not a delta: replace what the last one showed.</li>
 *   <li>Empty text ends the composition. The commit may come either before or after it, so a widget
 *       that shows the run inline removes it on the first committed character as well.</li>
 *   <li>Only hosts whose toolkit reports a composition send it: Minecraft 26.3 (SDL3). Everywhere else
 *       the operating system draws the run in a window of its own.</li>
 * </ul>
 */
@Getter
public final class CompositionEvent extends UIEvent {

    /** The run being composed; empty when the composition ended. */
    private final String text;

    /** The IME's caret within {@link #getText()}, in UTF-16 units. */
    private final int caret;

    public CompositionEvent(EventTarget target, String text, int caret) {
        super(target, true);
        this.text = text == null ? "" : text;
        this.caret = Math.max(0, Math.min(caret, this.text.length()));
    }
}
