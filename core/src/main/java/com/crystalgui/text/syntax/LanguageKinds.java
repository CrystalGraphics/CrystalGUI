package com.crystalgui.text.syntax;

/**
 * <b>A jar that knows languages declares them here</b> — the {@code ServiceLoader} seam behind
 * {@link LanguageRegistry}.
 *
 * <p>Implement it, ship a {@code META-INF/services/com.crystalgui.text.syntax.LanguageKinds} entry
 * naming the class, and every grammar, tokenizer and engine that jar carries is in front of
 * {@code core/}'s keyword lexers from the first time anything asks what a file is. Nothing calls
 * anything: being on the classpath is the whole mechanism.</p>
 *
 * <h3>What it replaces, and why the replacement is not merely tidier</h3>
 *
 * <p>{@code LanguageStack.registerAll()} was a call two hosts had to remember — the 1.7.10 loader and
 * the harness — each with a paragraph of its own explaining why. The defence was that a host is the only
 * thing that knows <em>when</em>: {@code LanguageRegistry} is consulted as an editor is built, so a
 * document already open keeps whichever tokenizer it was handed, and registering late is registering for
 * nobody.</p>
 *
 * <p>That is exactly the argument discovery answers better. {@link LanguageRegistry#bootstrap()} runs on
 * the <em>first read</em> of the registry, which is by construction before the first document is
 * classified — earlier than any host can arrange, and impossible to forget. A third host got no grammars,
 * no ECJ and no Rhino, and the failure is silent, because an editor that colours from the built-in lexers
 * and does not analyse is a <b>supported</b> configuration rather than a broken one.</p>
 *
 * <h3>Being on the classpath must not load a native, and does not</h3>
 *
 * <p>{@code TreeSitterLanguages} states this as the reason it cannot register from a static initialiser,
 * and the rule survives here: discovery is driven by a <em>question about a file</em>, not by class
 * loading. A process that never asks what language anything is — a dedicated server, which holds no
 * documents — never runs a service and never loads a grammar.</p>
 *
 * <h3>One implementation per jar, and it is the module's own front door</h3>
 *
 * <p>{@code language/}'s is {@code LanguageStack} itself rather than a {@code LanguageContribution}
 * beside it: one lifetime, one purpose, and a second class whose only real content is the name of the
 * first is a wrapper wearing a boundary's clothes.</p>
 *
 * @see LanguageRegistry#bootstrap()
 */
public interface LanguageKinds {

    /**
     * Registers this jar's languages into {@link LanguageRegistry}.
     *
     * <p>Called <b>once per process</b>, from the registry's first read. It must be safe to call again
     * anyway — a host may still call its module's own entry point directly, and
     * {@link LanguageRegistry#registerExtensions} replaces a rule rather than adding to it.</p>
     *
     * <p><b>Report rather than throw</b> for a tier that cannot be switched on. A grammar whose native
     * will not load on this platform, or an engine band that is not staged, is an ordinary deployment and
     * not a fault; the registry isolates a service that throws anyway, so a mistake here costs its own
     * languages and not the editor.</p>
     */
    void register();
}
