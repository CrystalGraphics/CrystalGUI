/**
 * The half of the JavaScript engine the <b>host</b> loads — registration, attachment, and Run.
 *
 * <p><b>Nothing here may import {@code org.mozilla.javascript}.</b> That is the whole membership rule,
 * and it is checkable: everything in this package reaches the engine through
 * {@code language.engine.bridge}, so it stays loadable on a process that never opens a band at all.</p>
 *
 * <p>Six classes. {@code JsLanguage} is the one call an application makes — two registrations, the same
 * shape {@code JavaLanguage} has. {@code JsLanguageServices} is the per-document attachment.
 * {@code JsHost} is the {@code ScriptRuntime} the Run panel holds. {@code JsCompletionProvider} answers
 * at a caret from the last analysis, over the same seam its Java twin uses.</p>
 *
 * <p>{@code RhinoOrigin} and {@code RhinoStackFrameFilter} keep their {@code Rhino} prefix and are host
 * classes: they are named for whose <em>format</em> they carry, not for what they import. A stack frame
 * printed by Rhino has a shape only Rhino produces, and reading it is the host's job because the console
 * that shows it is the host's.</p>
 */
package com.crystalgui.language.js.host;
