/**
 * What a script is compiled and completed <b>against</b> — the classpath, and the two things that are
 * on it without being files.
 *
 * <p>{@code HostClasspath} is the running process's own classpath rather than a declared one, because a
 * script's whole point is calling into the application hosting it. {@code TypeIndex} is every type name
 * on that classpath, so an unimported one can be offered and imported on accept. {@code ReflectionOverlay}
 * is the gap in the premise: a classpath is a list of files, and a class generated at runtime has no
 * file at all.</p>
 *
 * <p><b>Nothing here names a JDT type</b>, which is what lets the index be built once and shared by the
 * Java engine, the JavaScript engine's interop tier, and the import corrections — three consumers on two
 * sides of the bridge. {@code TypeIndex} is also the reason {@code ScriptPolicy} filters through a
 * <em>view</em> rather than a copy: the index belongs to the classpath and the policy belongs to
 * whoever is asking.</p>
 */
package com.crystalgui.language.java.classpath;
