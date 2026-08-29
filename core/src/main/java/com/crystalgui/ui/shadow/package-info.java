/**
 * <b>Spike S2 — a throwaway prototype. Nothing here survives M6.</b> {@code plan_ui_rewrite.md} M0.
 *
 * <h2>There is no {@code Shadow*} hierarchy, and there will not be one</h2>
 *
 * <p>{@link com.crystalgui.ui.shadow.ShadowButton} is not a new kind of button and is not the first of
 * fifty-four. It exists because S2 had to <em>measure</em> the cost of replacing this engine's
 * encapsulation model, and a measurement needs something converted — so one composite was converted,
 * beside the original, under one stylesheet, and the difference was read off. {@code Button} was picked
 * for being the smallest composite with a label and two optional slots.</p>
 *
 * <p><b>At M6 {@code Button} itself gets a shadow root and this class is deleted.</b> A parallel
 * hierarchy is the one outcome to avoid: every widget would exist twice, every bug would have to be
 * fixed twice, and a theme would have to name both. The whole point of converting in place is that
 * there is only ever one {@code Button}.</p>
 *
 * <h2>What S2 was asking, and what it answered</h2>
 *
 * <p>The engine audit's §4 finding is that a composite hides its parts behind a <em>boolean</em>
 * ({@code markAsInternal}) that the cascade cannot see, so all 54 composites are styled through global
 * {@code __double-underscore__} class names and any rule anywhere can reach any widget's insides. That
 * has cost real bugs: {@code .__content__} claimed by three unrelated widgets, one selector zeroing
 * every {@code ConfiguratorGroup} in the application, an adopted header coming home still wearing its
 * host's padding.</p>
 *
 * <table>
 *   <tr><th>Question</th><th>Answer</th></tr>
 *   <tr><td>Can {@code ::part(name)} be expressed and matched in this selector engine?</td>
 *       <td><b>Yes</b> — it is a second pseudo-element beside {@code ::highlight}, and unlike it,
 *           {@code ::part} contributes to a real element's own cascade</td></tr>
 *   <tr><td>Does a scope actually hold outer rules out?</td>
 *       <td><b>Yes</b> — {@code * &#123;&#125;}, {@code text &#123;&#125;} and
 *           {@code shadowbutton text &#123;&#125;} all fail to reach a shadow descendant while walking
 *           straight into a stock {@code Button}</td></tr>
 *   <tr><td>Does focus retarget?</td>
 *       <td><b>Yes</b>, and it composes through nesting — which is what stops a {@code DataContext}
 *           walk starting inside a widget's internals</td></tr>
 *   <tr><td>What does it cost?</td>
 *       <td>One extra rule-index lookup per shadow element: a {@code ::part} rule is indexed under the
 *           <b>host's</b> type and classes, so it cannot be found from the element it applies to</td></tr>
 * </table>
 *
 * <p>The finding that changes how M6 ports the UA sheet: <b>inherited properties still cross the
 * boundary</b>, exactly as on the web. So anything a widget wants to inherit needs no {@code part} at
 * all, and only what must be addressed <em>independently of the host</em> does — a much smaller set
 * than the {@code __double-underscore__} census suggests.</p>
 *
 * <h2>What it deliberately is not</h2>
 *
 * <p>Not the web's shadow DOM. No slots, no flat tree, no composed events, no {@code mode: open/closed},
 * no {@code :host} or {@code ::slotted}, and the shadow root here is a real box in the layout rather
 * than a {@code DocumentFragment} whose children belong to the host. Those are {@code ui.dom}'s job at
 * M5. This is the smallest thing that can carry a real widget through a real cascade and a real focus
 * walk, which is all a measurement needs.</p>
 *
 * <p>The two permanent things S2 produced are elsewhere on purpose: {@code ::part} in
 * {@code style/selector} and {@code style/StyleEngine}, because it is a real CSS feature; and the
 * harness scene {@code cgui-shadow-parts}, which is worth keeping through M6 as a live reference for
 * what a converted widget looks like.</p>
 */
package com.crystalgui.ui.shadow;
