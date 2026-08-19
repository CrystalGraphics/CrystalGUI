/**
 * Rhino, running a script — the engine's side of the execution bridge.
 *
 * <p>{@code RhinoExecutor} is the counterpart of {@code js.host.JsHost}, and the split is the loader's:
 * this half names Rhino, that half names the Run panel, and they meet at
 * {@code language.engine.bridge.JsExecutor}.</p>
 *
 * <p>Three things here are subtler than they look. <b>A stop names its thread</b> — one executor serves
 * every host in the process, so a stop with no argument would end somebody else's script; it sets a
 * per-run flag <em>and</em> interrupts, because {@code Thread.sleep} clears the interrupt status when it
 * throws. <b>{@code RhinoRemapping} is a membrane, not a subclass</b> — {@code JavaMembers} is internal
 * and differs per band, subclassing {@code NativeJavaObject} throws at the first binding because its
 * constructor moved, and overriding {@code wrapAsJavaObject} does nothing at all. And <b>the engine
 * loader belongs on the thread for calls INTO the engine and must come off for the script's own
 * execution</b>: after {@code initStandardObjects} the script is calling out, and leaving a child-first
 * loader in place makes every {@code ServiceLoader} in the application resolve against the engine's
 * classpath.</p>
 *
 * <p>{@code RhinoGlobals} asks the engine which names exist rather than listing them, because the answer
 * differs per band. {@code RhinoConsoleFormat} is Node's {@code util.inspect} to one level, not
 * JavaScript's own {@code String(value)} — which is right for a string and a number and wrong for
 * everything an author actually logs.</p>
 */
package com.crystalgui.language.js.rhino.exec;
