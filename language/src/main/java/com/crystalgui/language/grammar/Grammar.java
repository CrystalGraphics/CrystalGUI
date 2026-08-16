package com.crystalgui.language.grammar;

import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.syntax.Language;

import org.treesitter.TSLanguage;

import java.util.List;
import java.util.function.Supplier;


/**
 * Every grammar this module ships, as a table.
 *
 * <h3>Why a table, and why now</h3>
 *
 * <p>This was six near-identical static factories on {@link TreeSitterTokenizer} and six near-identical
 * {@code registerExtensions} calls in {@link TreeSitterLanguages}, with the per-language facts split
 * across both: which parser, which query directory, which {@link Language}, which extensions, whether it
 * injects. Adding XML meant editing two files in four places and getting six lines right, and the only
 * thing stopping a mismatch was reading carefully.</p>
 *
 * <p>They are the same six facts either way, so they are stated once. A seventh grammar is now <b>one
 * row</b>, and the two consumers cannot disagree about it because there is nothing left to disagree
 * with.</p>
 *
 * <h3>The parser is a {@link Supplier}, which is load-bearing rather than tidy</h3>
 *
 * <p>An enum constant's fields are built when the class initialises, so holding {@code TSLanguage}
 * instances directly would construct <em>every</em> grammar — and therefore load every native — the first
 * time anything touched this class, including a lookup for a language the process will never open. The
 * supplier makes the table a description of six grammars rather than an instance of them.</p>
 */
public enum Grammar {

    JAVA(Language.JAVA, "java", org.treesitter.TreeSitterJava::new, List.of(),
            "java"),

    CSS(Language.PLAIN, "css", org.treesitter.TreeSitterCss::new, List.of(),
            "css"),

    /**
     * JavaScript — the second language with an engine behind it (M10).
     *
     * <p>It sat on {@link Language#PLAIN} until then, and the cost of that was not colour: it was
     * <b>editing</b>. {@code Language} is what supplies the comment syntax the toggle-comment command
     * uses, the bracket pairs auto-close and auto-indent read, and the {@code .} that opens a completion
     * list — so a {@code .js} file coloured beautifully and could not be commented out, closed a brace,
     * or offered a member list, all silently. The tree-sitter grammar had nothing to do with any of
     * them.</p>
     */
    JAVASCRIPT(Language.JAVASCRIPT, "javascript", org.treesitter.TreeSitterJavascript::new, List.of(),
            "js", "mjs", "cjs"),

    /**
     * HTML, which is three languages in one document.
     *
     * <p>The injected pair is named here rather than derived from {@code injections.scm}, because that
     * file names languages as strings and a string is not a grammar — the mapping has to exist somewhere,
     * and a row that lists what it can host is the honest place. A body whose language is not in this list
     * simply stays uncoloured, which is what an unhandled injection should look like.</p>
     */
    HTML(Language.PLAIN, "html", org.treesitter.TreeSitterHtml::new, List.of("css", "javascript"),
            "html", "htm"),

    /**
     * GLSL, claiming <b>the same eight extensions core's own lexer does</b>.
     *
     * <p>That is deliberate: this must REPLACE the built-in lexer rather than cover a subset of it, or a
     * shader opened as {@code .vert} and the same shader opened as {@code .glsl} would highlight
     * differently depending on which registration happened to win.</p>
     */
    GLSL(Language.GLSL, "glsl", org.treesitter.TreeSitterGlsl::new, List.of(),
            "glsl", "vert", "frag", "geom", "tesc", "tese", "comp", "shader"),

    /**
     * The XML family, by extension rather than by content.
     *
     * <p>An {@code .svg} and an {@code .xsd} are XML documents and parse as such; nothing in the editor
     * needs to know which schema a document claims to follow.</p>
     */
    XML(Language.PLAIN, "xml", org.treesitter.TreeSitterXml::new, List.of(),
            "xml", "xsd", "xsl", "xslt", "svg", "plist", "wsdl");

    private static final String QUERY_ROOT = "assets/crystalgui/syntax/";

    private final Language language;
    private final String directory;
    private final Supplier<TSLanguage> parser;
    private final List<String> injects;
    private final List<String> extensions;

    Grammar(Language language, String directory, Supplier<TSLanguage> parser,
            List<String> injects, String... extensions) {
        this.language = language;
        this.directory = directory;
        this.parser = parser;
        this.injects = injects;
        this.extensions = List.of(extensions);
    }

    /** What this file is, for comment syntax and bracket pairs — separate from how it is coloured. */
    public Language language() {
        return language;
    }

    /** The vendored query directory, which is also the name {@code injections.scm} refers to it by. */
    public String directory() {
        return directory;
    }

    /** File extensions this claims, without leading dots. */
    public List<String> extensions() {
        return extensions;
    }

    /** Whether this grammar embeds others. */
    public boolean hasInjections() {
        return !injects.isEmpty();
    }

    String queryPath(String fileName) {
        return QUERY_ROOT + directory + "/" + fileName;
    }

    /** The grammar a {@code injections.scm} language name refers to, or null if we do not ship it. */

    static Grammar byDirectory(String name) {
        for (Grammar grammar : values()) {
            if (grammar.directory.equals(name)) return grammar;
        }
        return null;
    }

    /**
     * A tokenizer for this grammar, with its injected children built alongside.
     *
     * @param scheduler where reparses run, or null to parse on the calling thread
     */
    public TreeSitterTokenizer newTokenizer(JobScheduler scheduler) {
        TreeSitterTokenizer tokenizer = new TreeSitterTokenizer(newParser(),
                Queries.loadForHighlighting(queryPath("highlights.scm")), scheduler);
        if (hasInjections()) tokenizer.withInjections(this, scheduler);
        return tokenizer;
    }

    /** A fresh parser for this grammar — this is where the native is actually loaded. */
    TSLanguage newParser() {
        return parser.get();
    }

    /** The grammars this one may host, in the order the query names them. */
    List<Grammar> injectedGrammars() {
        List<Grammar> grammars = new java.util.ArrayList<>(injects.size());
        for (String name : injects) {
            Grammar grammar = byDirectory(name);
            if (grammar != null) grammars.add(grammar);
        }
        return grammars;
    }
}
