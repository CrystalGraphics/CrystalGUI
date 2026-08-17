/**
 * The Run surface — the panel, the rail, the console view, and the marks that say something is running.
 *
 * <p><b>The only package under {@code language.run} that may import {@code com.crystalgui.ui}.</b>
 * Everything below this line — the transcript, the sessions, the runtime seam — is assertable with no
 * window, and keeping it that way is what the split is for.</p>
 *
 * <p>{@code ScriptWorkbench} is the wiring: the engine, the commands, the console and the indicator,
 * attached to a workbench. It lives in this module rather than in the harness because <b>the dependency
 * runs this way</b> — {@code Workbench} constructs its own panels and cannot name a Run panel it has
 * never heard of, so {@code RunPanels} registers from outside.</p>
 *
 * <h2>The trap this package has already paid for</h2>
 *
 * <p><b>A signal emitted by a worker thread carries that thread into every listener.</b>
 * {@code RunSessions} is written by the thread whose script just changed state and emits inline, so a
 * handler here runs on a script thread by construction. Pushing a button's enablement from one ends in
 * {@code invalidateStyleMatch()}, which writes the style engine's dirty-match set while the UI thread is
 * copying it — an {@code ArrayIndexOutOfBoundsException} out of {@code HashMap.keysToArray}, thrown in
 * {@code advanceFrame}, with nothing about Run anywhere in the trace.</p>
 *
 * <p><b>The fix is pull, not a lock and not a hop:</b> {@code RunPanel.refreshActions} recomputes the
 * same value every frame from the same object, and a per-frame reader cannot race the frame it reads in.
 * Where a push is genuinely required, hop through {@code JobScheduler}, whose {@code onDone} runs on the
 * UI thread during {@code drain()} — {@code RunIndicators} is the reference. A
 * {@code ConcurrentLinkedQueue} drained in the frame is the other safe shape, which is exactly why
 * {@code RunConsole}'s transcript is one.</p>
 *
 * <p>{@code TailFollow} carries no UI import and is here anyway: it is whether new output drags the view
 * down with it, which is a view question. It was four lines inside {@code RunConsoleView} and they were
 * wrong twice, in two different ways.</p>
 */
package com.crystalgui.language.run.view;
