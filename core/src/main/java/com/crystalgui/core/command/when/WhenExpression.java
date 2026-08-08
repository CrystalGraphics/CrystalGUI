package com.crystalgui.core.command.when;

import com.crystalgui.core.data.DataContext;

import java.util.function.Predicate;

/**
 * A condition written as text — VS Code's {@code when} clause.
 *
 * <pre>{@code
 * WhenExpression.parse("undoStack && !readOnly")
 * WhenExpression.parse("graphView || editor")
 * WhenExpression.parse("language == 'glsl' && selection")
 * }</pre>
 *
 * <h3>Why this exists when a lambda already works</h3>
 *
 * <p>It does not replace {@code enabledWhen(Predicate)} and is not meant to: for a command declared in
 * Java, a lambda is clearer, typed, and cannot be misspelled. What a lambda cannot be is <b>data</b>. A
 * server-driven UI ships a description over a wire; a resource pack declares a menu in JSON; a keymap
 * preset carries conditions. None of those can carry a {@code Predicate}, and every one of them is
 * something this engine already does with commands, keymaps and stylesheets. This is the same argument
 * {@code Command} makes about bindings naming a {@code String} id rather than holding a lambda.</p>
 *
 * <p>It was deliberately parked until the menu bar existed, because until something rendered a
 * contributed condition there was nothing for it to be data <em>for</em>.</p>
 *
 * <h3>The grammar</h3>
 *
 * <pre>
 * or      := and ( '||' and )*
 * and     := equality ( '&amp;&amp;' equality )*
 * equality:= unary ( ('==' | '!=') literal )?
 * unary   := '!' unary | primary
 * primary := '(' or ')' | key
 * key     := [A-Za-z_] [A-Za-z0-9_.]*
 * literal := '...' | "..." | bare word
 * </pre>
 *
 * <p><b>Deliberately smaller than VS Code's.</b> No {@code =~} regex match, no {@code in} operator, no
 * {@code &lt;}/{@code &gt;} comparisons. Each of those exists there to serve a specific built-in key, and
 * adding an operator with no caller is how a grammar becomes something nobody can predict the behaviour
 * of. They can be added; the parser is a recursive descent and each is one method.</p>
 *
 * <h3>A malformed expression is refused, loudly</h3>
 *
 * <p>{@link #parse} throws. This is the opposite of {@code StyleValue}, which logs and degrades to null —
 * and the difference is what the two guard: a bad declaration must not break the cascade, because a
 * stylesheet is decoration and the page must still render. A bad <em>condition</em> silently becoming
 * "false" hides a command forever, and silently becoming "true" offers one that cannot work. Neither is a
 * degradation; both are wrong answers.</p>
 */
public final class WhenExpression {

    private final Node root;
    private final String source;

    private WhenExpression(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    /**
     * Parses {@code expression}.
     *
     * @throws IllegalArgumentException if it is malformed, naming the position
     */
    public static WhenExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("A when expression cannot be empty");
        }
        Parser parser = new Parser(expression);
        Node node = parser.parseOr();
        parser.expectEnd();
        return new WhenExpression(node, expression);
    }

    /** Evaluates against {@code context}. */
    public boolean test(DataContext context) {
        return root.test(context);
    }

    /** As a predicate, for {@code Command.enabledWhereData} and friends. */
    public Predicate<DataContext> asPredicate() {
        return this::test;
    }

    @Override
    public String toString() {
        return "when(" + source + ")";
    }

    // ── The tree ────────────────────────────────────────────────────────────────────────────────

    private interface Node {
        boolean test(DataContext context);
    }

    private record Key(String name) implements Node {
        @Override
        public boolean test(DataContext context) {
            return ContextKeys.isTruthy(ContextKeys.resolve(name, context));
        }
    }

    private record Not(Node inner) implements Node {
        @Override
        public boolean test(DataContext context) {
            return !inner.test(context);
        }
    }

    private record And(Node left, Node right) implements Node {
        @Override
        public boolean test(DataContext context) {
            // Short-circuits, which is not merely an optimisation: a definition may legitimately throw or
            // be expensive when its guard is false, so `workbench && workbench.dirty` has to be safe.
            return left.test(context) && right.test(context);
        }
    }

    private record Or(Node left, Node right) implements Node {
        @Override
        public boolean test(DataContext context) {
            return left.test(context) || right.test(context);
        }
    }

    private record Equality(String name, String literal, boolean negated) implements Node {
        @Override
        public boolean test(DataContext context) {
            boolean equal = ContextKeys.matches(ContextKeys.resolve(name, context), literal);
            return negated != equal;
        }
    }

    // ── The parser ──────────────────────────────────────────────────────────────────────────────

    private static final class Parser {

        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Node parseOr() {
            Node left = parseAnd();
            while (eat("||")) left = new Or(left, parseAnd());
            return left;
        }

        Node parseAnd() {
            Node left = parseEquality();
            while (eat("&&")) left = new And(left, parseEquality());
            return left;
        }

        Node parseEquality() {
            Node left = parseUnary();
            boolean negated = false;
            if (peek("!=")) negated = true;
            else if (!peek("==")) return left;

            // Only a bare key can be compared. `!(a) == 'b'` has no meaning worth guessing at, and
            // refusing it here is cheaper than defining it.
            if (!(left instanceof Key key)) {
                throw error("only a plain key may be compared with == or !=");
            }
            at += 2;
            return new Equality(key.name(), readLiteral(), negated);
        }

        Node parseUnary() {
            skipSpace();
            if (eat("!")) return new Not(parseUnary());
            return parsePrimary();
        }

        Node parsePrimary() {
            skipSpace();
            if (eat("(")) {
                Node inner = parseOr();
                skipSpace();
                if (!eat(")")) throw error("expected ')'");
                return inner;
            }
            return new Key(readIdentifier());
        }

        String readIdentifier() {
            skipSpace();
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                boolean valid = at == start
                        ? Character.isLetter(c) || c == '_'
                        : Character.isLetterOrDigit(c) || c == '_' || c == '.';
                if (!valid) break;
                at++;
            }
            if (at == start) throw error("expected a context key");
            return text.substring(start, at);
        }

        String readLiteral() {
            skipSpace();
            if (at >= text.length()) throw error("expected a value");
            char quote = text.charAt(at);
            if (quote == '\'' || quote == '"') {
                int end = text.indexOf(quote, ++at);
                if (end < 0) throw error("unterminated string");
                String value = text.substring(at, end);
                at = end + 1;
                return value;
            }
            int start = at;
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))
                    && "()&|!".indexOf(text.charAt(at)) < 0) {
                at++;
            }
            if (at == start) throw error("expected a value");
            return text.substring(start, at);
        }

        boolean peek(String token) {
            skipSpace();
            return text.startsWith(token, at);
        }

        boolean eat(String token) {
            if (!peek(token)) return false;
            at += token.length();
            return true;
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        void expectEnd() {
            skipSpace();
            if (at < text.length()) throw error("unexpected '" + text.charAt(at) + "'");
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "Bad when expression at " + at + ": " + message + " — in \"" + text + "\"");
        }
    }
}
