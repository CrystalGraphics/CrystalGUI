/**
 * The transcript — a document a script writes into, bounded, with a level per line.
 *
 * <p><b>A console is a text area, and this was a list first.</b> The disproof is selection: an IDE
 * console lets you drag from the middle of one line to the middle of another, and a row-based list
 * cannot express that. {@code RunConsole} is therefore a document, and {@code run.view.RunConsoleView}
 * is a {@code TextEditor} configured to be read-only output.</p>
 *
 * <p><b>Nothing here imports {@code com.crystalgui.ui}</b> — that is what makes the transcript
 * assertable without a window, and it is the line between this package and {@code .view}.</p>
 *
 * <h2>What shapes a line</h2>
 *
 * <p>{@code RunMessage} is one line plus enough about where it came from to be filtered, collapsed and
 * navigated. {@code RunLevel} is what kind of line it is and is deliberately <b>not</b>
 * {@code DiagnosticSeverity}: a diagnostic is a statement about source, carries a range, and is answered
 * by re-analysing the file; a console line is none of those things. {@code ConsolePrefix} is the stamp
 * in front — and a console that decorates output is not showing what the program printed, which is the
 * argument the class opens with rather than a caveat it hides.</p>
 *
 * <p>{@code ConsoleFilter} is IntelliJ's {@code Filter}: the console knows nothing about stack frames
 * and runs a chain instead, which is why {@code JavaStackFrameFilter} lives here and
 * {@code js.host.RhinoStackFrameFilter} lives beside the engine whose format it reads. This one matches
 * what {@code StackTraceElement#toString()} writes, so it is fixed by the JDK rather than guessed at —
 * and it does not violate this module's engine-neutrality rule, because the JDK's frame format is not
 * {@code language.java}.</p>
 *
 * <p>{@code AnsiEscapes} exists because nothing in the engine emits them and plenty of ordinary Java
 * does.</p>
 */
package com.crystalgui.language.run.console;
