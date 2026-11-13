package com.crystalgui.core.async;

/**
 * Thrown at an engine checkpoint of a document being closed, to unwind its frame to the sequence. An {@code Error},
 * so an author's {@code catch (Exception)} cannot swallow it.
 *
 * <pre>{@code
 * try {
 *     work();
 * } catch (Exception e) {      // does not catch it
 *     recover(e);
 * } finally {
 *     release();               // runs: a finally is how an application cleans up under an abort
 * }
 * }</pre>
 *
 * <p>Easy to get wrong: never catch {@code Throwable} or {@code Error} to carry on; rethrow what is an
 * {@code ApplicationAborted}. A checkpoint is anywhere the engine runs author code: queued tasks, signal listeners,
 * event dispatch, the cascade, layout and paint ({@link UiSequence#checkpoint}).</p>
 */
public final class ApplicationAborted extends Error {

    private static final long serialVersionUID = 1L;

    ApplicationAborted(String sequence) {
        super("the document '" + sequence + "' is being closed", null, false, false);
    }
}
