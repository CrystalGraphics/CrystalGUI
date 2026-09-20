/**
 * <b>What a running game is asked to prove about itself</b> — the four probes, and the loader-shaped
 * hole in each.
 *
 * <p>Every one of these is a claim nothing else in the build can reach. A headless test asserts by
 * <em>absence</em> and touches no loader; the GL harness is a client with a real context by design; an
 * import guard sees imports, and each defect these were built for is a <em>runtime</em> property — "a
 * client-only class is constructed on a server", "a screen that pauses the game stops the server that
 * answers it". So the only thing that can see one is a game, and the only thing that can drive a game
 * is a loader.</p>
 *
 * <table border="1">
 *   <caption>The four</caption>
 *   <tr><th>Probe</th><th>Runs in</th><th>Asks</th></tr>
 *   <tr><td>{@link com.crystalgui.probe.ServerSmoke}</td><td>a dedicated server</td>
 *       <td>the server-side stack came up, and no client-only class was loaded</td></tr>
 *   <tr><td>{@link com.crystalgui.probe.ConnectionProbe}</td><td>a client, on a real connection</td>
 *       <td>sessions, state, events, calls, fan-out and the workspace, over the game's own channel</td></tr>
 *   <tr><td>{@link com.crystalgui.probe.DesktopProbe}</td><td>a client with a desktop up</td>
 *       <td>a scripted run through the compositor: minimise, restore, pin, click through an overlay</td></tr>
 *   <tr><td>{@link com.crystalgui.probe.AutoTest}</td><td>a client, unattended</td>
 *       <td>open the desktop, photograph it, quit — the only one that also runs on a SHIPPED jar</td></tr>
 * </table>
 *
 * <h2>Why they are here and not in a test source set</h2>
 *
 * <p>Because a loader module can only name what is on its compile classpath, and the thing that drives
 * these is a loader's own entry point — {@code ClientProxy}, {@code LifecycleCrystalGUI}. A dev-only
 * source set would mean a shipping class naming a class that is absent in production, which is a
 * {@code NoClassDefFoundError} at exactly the moment nobody is watching.</p>
 *
 * <p><b>So they ship, deliberately.</b> The cost is four classes in {@code crystalgui-&lt;version&gt;.jar};
 * each is off behind a system property and costs one {@code Boolean.getBoolean} when it is. That is
 * cheaper than the arrangement it replaced, where the same logic lived in each loader — six probe
 * classes on 1.7.10 and none on 1.20.x — and shipped anyway.</p>
 *
 * <h2>What a loader owes each of them</h2>
 *
 * <p>A {@code Host} interface, and nothing else. It answers only what a game knows: is this a dedicated
 * server, how do I load a world, how do I take a screenshot, how do I stop. What to check, in what
 * order, what a skip means and what the report says are this package's, because they are the same
 * answer on every Minecraft version — and were four different answers before they moved here.</p>
 *
 * <p>Each probe writes its verdict through {@link com.crystalgui.probe.ProbeReport}, which is the rule
 * a build task reads: line one is {@code PASS} or {@code FAIL}, and <b>an absent file is a failure</b>.</p>
 *
 * @see com.crystalgui.probe.ProbeReport
 */
package com.crystalgui.probe;
