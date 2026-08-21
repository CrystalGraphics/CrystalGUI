package com.crystalgui.language.run;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a script may reach — classes and, since §19.5, individual members.
 *
 * <h3>Why this is in {@code language.run} and not in {@code language.js}</h3>
 *
 * <p>JavaScript is the first consumer with real teeth, and it is not the only one. The same question is
 * asked by the <b>type index</b> (which types may be offered at all), by <b>completion and hover</b> (which
 * members may be described), and by <b>Java compilation</b>. A policy owned by one language would have to
 * be reached through that language by the other three, which is how a rule ends up enforced in some places
 * and not others.</p>
 *
 * <p>It is also the host's rather than the engine's: a policy is a deployment decision, and the engine is
 * what obeys it. That is why the child side of the bridge receives a {@code Predicate<String>} and never
 * this type — a JDK-typed shadow of a host object, exactly as the console and the input are.</p>
 *
 * <h3>The rule that decides is the MOST SPECIFIC one</h3>
 *
 * <p>This is the whole model, and it replaced a strict "a denial is a veto, asked first". That ordering was
 * chosen so an allowlist entry could not re-permit a refusal — {@code allow java.lang} must not undo
 * {@code deny java.lang.reflect}, or {@link #UNSAFE} means whatever the two lists happen to say about each
 * other. Specificity keeps that property and buys the one it could not express: {@code java.lang.reflect}
 * is more specific than {@code java.lang}, so the denial still wins, while
 * {@code deny java.util.List} + {@code allow java.util.List#size} now leaves exactly one member reachable.
 * <b>An exception has to be sayable in both directions</b>, and a veto can only say one.</p>
 *
 * <p>Specificity is a property of the <em>target</em>, not of the order rules were written in, so a policy
 * means the same thing however it is assembled — a file read in a different order cannot change it. Where
 * two rules tie, <b>a denial wins</b>: the safe answer is the one that refuses.</p>
 *
 * <table>
 *   <tr><th>target</th><th>example</th><th>beats</th></tr>
 *   <tr><td>an exact member</td><td>{@code java.util.List#add}</td><td>everything below</td></tr>
 *   <tr><td>a member pattern</td><td>{@code java.util.List#~set.*}</td><td>every class rule</td></tr>
 *   <tr><td>a longer prefix</td><td>{@code java.util.List}</td><td>{@code java.util}</td></tr>
 *   <tr><td>a shorter prefix</td><td>{@code java}</td><td>a regex</td></tr>
 *   <tr><td>a class regex</td><td>{@code ~.*\\.internal\\..*}</td><td>the base posture only</td></tr>
 * </table>
 *
 * <p><b>Any member rule outranks any class rule</b>, rather than comparing the two by prefix length. A
 * deployment naming a member is talking about that member; making it lose to a longer package prefix
 * somewhere else would mean the more precise statement was the weaker one.</p>
 *
 * <p><b>A regex is the least specific thing here</b>, which is the opposite of what its precision suggests.
 * A pattern is a sweeping statement — it is reached for to describe a shape rather than a place — so it
 * sets the background against which the named exceptions are read.</p>
 *
 * <h3>Prefixes match on a boundary</h3>
 *
 * <p>{@code java.util} admits {@code java.util.List} and {@code java.util.concurrent.Future} but not
 * {@code java.utility.Thing}, and a nested class carries its outer name as a prefix so it matches the
 * enclosing entry for free.</p>
 *
 * <h3>Allow and deny compose; neither is a boundary</h3>
 *
 * <p>The original rule was "allowlist only", on the grounds that <b>a denylist is unsound the moment a new
 * class appears</b>. That is true and it is why a denylist may never be the thing a security claim rests
 * on. It stopped being the whole argument once the honest posture was settled: for Java this is a
 * <em>guardrail</em>, and the allowlist a guardrail needs is the host API, the Minecraft surface and a
 * usable slice of {@code java.*} — thousands of entries. <b>A control nobody will write is worse than a
 * leaky one that gets used.</b></p>
 *
 * <p><b>Not a security boundary.</b> A script runs in the game's own JVM, so a determined author has
 * reflection and the classloader; this stops accidents and casual reach, which is what the trust model — a
 * script is code the player installed — actually asks for.</p>
 *
 * <h3>What enforces which half, and where the gaps are</h3>
 *
 * <p>Stated because a control that is decided and not enforced looks exactly like one that is, and this
 * class answers questions that four different surfaces ask.</p>
 *
 * <table>
 *   <tr><th>surface</th><th>classes</th><th>members</th></tr>
 *   <tr><td>JS completion, hover, the type index</td><td>enforced</td><td><b>enforced</b></td></tr>
 *   <tr><td>JS at run time (Rhino's {@code ClassShutter})</td><td>enforced</td>
 *       <td><b>not yet</b> — needs the membrane, which today is installed only for name mapping</td></tr>
 *   <tr><td>Java ahead of time ({@code RefusedTypes}, the constant pool)</td><td>enforced</td>
 *       <td><b>not yet</b> — the pool carries {@code Methodref} owner and name, so it is reachable</td></tr>
 *   <tr><td>Java at run time ({@code ScriptClassLoader})</td><td>enforced</td>
 *       <td>not reachable — a loader is handed a class, never a call</td></tr>
 * </table>
 *
 * <p>So a member rule today is a <b>guardrail in the editor</b>: it stops a member being offered, described
 * or completed, which is what stops it being reached by accident. It is not yet a run-time refusal, and
 * nothing here should be read as claiming it is. The class half is unchanged and is enforced everywhere it
 * was before.</p>
 *
 * <h3>Writing one</h3>
 *
 * <pre>
 * ScriptPolicy.builder()
 *         .deny(ScriptPolicy.UNSAFE)                  // the routes out of a class filter
 *         .allow("java.util")                         // a package
 *         .deny("java.util.List")                     // ...minus a class
 *         .allow("java.util.List#size")               // ...plus one of its members back
 *         .deny("~.*\\.internal\\..*")                // and a shape, wherever it appears
 *         .build();
 * </pre>
 *
 * <p>The string form is what a config file writes: {@code owner}, {@code owner#member}, {@code owner#*},
 * and a {@code ~} prefix on either side to make it a regex. {@link Target} is the same vocabulary typed,
 * for callers assembling a policy in code.</p>
 */
public final class ScriptPolicy {

    /** Everything is reachable — the harness's posture, and a test's. */
    private static final ScriptPolicy ALLOW_ALL = new ScriptPolicy(Collections.emptyList(), true);

    /**
     * What no policy may permit — the machinery that enforces policies.
     *
     * <h3>A filter its subject can switch off is not a filter</h3>
     *
     * <p>{@code JavaLanguage.restrictTo} and {@code JsLanguage.restrictTo} are {@code public static}, they
     * sit on the host classpath, and {@code ScriptClassLoader} is parent-first. So under a policy of
     * "deny {@code java.io}" the name {@code com.crystalgui.language.java.JavaLanguage} was not denied —
     * it is not {@code java.io} — the ahead-of-time scan passed it, the loader passed it, and <b>one line
     * of script turned the filter off for every script after it</b>:</p>
     *
     * <pre>com.crystalgui.language.java.JavaLanguage.restrictTo(null);</pre>
     *
     * <p>This is therefore a <b>floor and not a rule</b>. It is not part of the specificity contest at all
     * — it is asked before it, so naming it in an allowlist does not permit it and no member exception can
     * reach inside it. That is the whole property, and it is why it is separate from {@link #UNSAFE}, which
     * is a list a host composes and may edit down.</p>
     *
     * <p><b>{@code com.crystalgui.language} and nothing wider.</b> {@code com.crystalgui.ui} and
     * {@code com.crystalgui.text} are what scripts are FOR — driving the interface and the document — so
     * a floor over all of {@code com.crystalgui} would refuse the API in the name of protecting it.</p>
     *
     * <p>One exception, and it is not reachable through this: {@code ScriptControl}, whose
     * {@code checkpoint()} is injected into every method of every script by {@code Safepoints}. The scan
     * and the loader exempt it by name. It exposes exactly one {@code public static void} that reads the
     * calling thread's own interrupt status, so reaching it buys a script nothing but the ability to stop
     * itself.</p>
     */
    public static final List<String> ALWAYS_REFUSED = Collections.singletonList("com.crystalgui.language");

    /**
     * Whether this name is refused by every policy that restricts anything.
     *
     * <p><b>{@link #allowAll()} is the one exception, and it is not a hole.</b> The floor exists to stop a
     * script <em>relaxing</em> the policy in force; with nothing configured there is nothing to relax, and
     * a script that calls {@code restrictTo} itself can only make things narrower — after which the floor
     * refuses anything that could widen them again. A one-way ratchet, in the safe direction.</p>
     */
    public static boolean isAlwaysRefused(@Nullable String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;
        String name = elementTypeOf(binaryName);
        for (String prefix : ALWAYS_REFUSED) {
            if (prefixMatches(name, prefix)) return true;
        }
        return false;
    }

    /**
     * The ways out of a class allowlist, as prefixes — what a denying policy usually wants first.
     *
     * <p><b>Without these a class filter is decorative.</b> Every entry is a documented route from a name a
     * policy permits to one it does not: reflection and method handles resolve a class from a string, a
     * {@code ClassLoader} loads one outright, and {@code Runtime}/{@code ProcessBuilder} leave the JVM.
     * Refusing {@code java.io} while admitting {@code java.lang.reflect} refuses a spelling rather than a
     * capability.</p>
     *
     * <p>It is a <b>list a host passes</b> rather than something implied by {@link #denying}, because a
     * policy that silently refuses more than it was told to is the mirror of one that silently allows
     * more — and because a deployment that genuinely wants reflection has to be able to drop an entry.</p>
     *
     * <p>Two omissions are deliberate and both are holes. <b>{@code java.lang.Thread}</b> escapes the kill
     * switch — a stop names one thread and a spawned one runs on — but threads are ordinary in correct
     * scripts, and this set is for accidents rather than for determined authors. <b>{@code
     * java.lang.System}</b> carries {@code System.exit} and also {@code System.out}, which is the console.
     * That second one is now sayable — {@code deny("java.lang.System#exit")} keeps the console and takes
     * the exit — and it is left out of this list anyway, because dropping a whole class from under every
     * script's {@code println} is not a default anyone should inherit silently.</p>
     */
    public static final List<String> UNSAFE = List.of(
            "java.lang.reflect",
            "java.lang.invoke",
            "java.lang.ClassLoader",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.security",
            "sun.misc.Unsafe",
            "jdk.internal");

    // ── What a rule is about ────────────────────────────────────────────────────────────────────

    /**
     * A set of classes, and optionally a set of their members.
     *
     * <p>Immutable, and it carries its own {@link #specificity()} — see the class note for the table. Build
     * one with {@link #under}, {@link #matching}, {@link #member}, {@link #membersMatching} or
     * {@link #parse}.</p>
     */
    public static final class Target {

        private static final int MEMBER_PATTERN = 1000;
        private static final int MEMBER_EXACT = 2000;

        @Nullable private final String prefix;
        @Nullable private final Pattern classPattern;
        @Nullable private final String memberName;
        @Nullable private final Pattern memberPattern;
        private final boolean aboutMembers;
        private final int specificity;

        private Target(@Nullable String prefix, @Nullable Pattern classPattern,
                       @Nullable String memberName, @Nullable Pattern memberPattern,
                       boolean aboutMembers) {
            this.prefix = prefix;
            this.classPattern = classPattern;
            this.memberName = memberName;
            this.memberPattern = memberPattern;
            this.aboutMembers = aboutMembers;
            this.specificity = computeSpecificity();
        }

        /** Every class at or under a dot-boundary prefix — a package, a class, or a nest. */
        public static Target under(String prefix) {
            requireText(prefix, "prefix");
            return new Target(prefix, null, null, null, false);
        }

        /** Every class whose binary name matches — the broadest thing here; see the class note. */
        public static Target matching(String regex) {
            requireText(regex, "regex");
            return new Target(null, compile(regex), null, null, false);
        }

        /** One named member of every class {@code ownerPrefix} covers. */
        public static Target member(String ownerPrefix, String memberName) {
            requireText(ownerPrefix, "ownerPrefix");
            requireText(memberName, "memberName");
            return new Target(ownerPrefix, null, memberName, null, true);
        }

        /** Every member matching a pattern, of every class {@code ownerPrefix} covers. */
        public static Target membersMatching(String ownerPrefix, String memberRegex) {
            requireText(ownerPrefix, "ownerPrefix");
            requireText(memberRegex, "memberRegex");
            return new Target(ownerPrefix, null, null, compile(memberRegex), true);
        }

        /**
         * The written form: {@code owner}, {@code owner#member}, {@code owner#*}, with a leading
         * {@code ~} on either side to make that side a regex.
         *
         * <p>What a config file holds, and what {@link Builder#allow(String...)} takes. The typed
         * factories above are the same vocabulary for a caller assembling a policy in code.</p>
         */
        public static Target parse(String spec) {
            requireText(spec, "spec");
            int hash = spec.indexOf('#');
            String owner = hash < 0 ? spec : spec.substring(0, hash);
            String member = hash < 0 ? null : spec.substring(hash + 1);
            requireText(owner, "owner in \"" + spec + "\"");

            boolean ownerIsPattern = owner.startsWith("~");
            String ownerBody = ownerIsPattern ? owner.substring(1) : owner;
            requireText(ownerBody, "owner in \"" + spec + "\"");
            String ownerPrefix = ownerIsPattern ? null : ownerBody;
            Pattern ownerPattern = ownerIsPattern ? compile(ownerBody) : null;

            if (member == null) {
                return new Target(ownerPrefix, ownerPattern, null, null, false);
            }
            requireText(member, "member in \"" + spec + "\"");
            // `#*` is "every member", which is what a bare class rule already means -- but written this way
            // it carries MEMBER specificity, so it can beat a class rule elsewhere. That is the point of
            // being able to say it.
            if ("*".equals(member)) {
                return new Target(ownerPrefix, ownerPattern, null, compile(".*"), true);
            }
            if (member.startsWith("~")) {
                String body = member.substring(1);
                requireText(body, "member in \"" + spec + "\"");
                return new Target(ownerPrefix, ownerPattern, null, compile(body), true);
            }
            return new Target(ownerPrefix, ownerPattern, member, null, true);
        }

        /** Whether this rule speaks about individual members rather than about a class as a whole. */
        public boolean aboutMembers() {
            return aboutMembers;
        }

        /** How strongly this target claims a name — see the table on the class. */
        public int specificity() {
            return specificity;
        }

        private int computeSpecificity() {
            int score;
            if (prefix != null) {
                // A LONGER PATH IS A NARROWER CLAIM. Counted in segments rather than characters so
                // `com.acme` does not outrank `java.util.List` for being spelled with more letters; `$`
                // counts too, so a nested class is narrower than the class that encloses it.
                int segments = 1;
                for (int i = 0; i < prefix.length(); i++) {
                    char c = prefix.charAt(i);
                    if (c == '.' || c == '$') segments++;
                }
                score = 1 + 10 * segments;
            } else {
                score = 0;
            }
            if (memberName != null) return score + MEMBER_EXACT;
            if (memberPattern != null) return score + MEMBER_PATTERN;
            return score;
        }

        private boolean coversClass(String binaryName) {
            if (classPattern != null) return classPattern.matcher(binaryName).matches();
            return prefix != null && prefixMatches(binaryName, prefix);
        }

        private boolean coversMember(String name) {
            if (memberName != null) return memberName.equals(name);
            if (memberPattern != null) return memberPattern.matcher(name).matches();
            return true;
        }

        /** The prefix this target is anchored at, or null when it is a pattern — for path walkability. */
        @Nullable
        private String anchor() {
            return prefix;
        }

        private static Pattern compile(String regex) {
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException malformed) {
                // Refused rather than dropped. A pattern that does not compile is a rule the deployment
                // believes it wrote, and silently ignoring it is how a policy comes to be weaker than the
                // file that describes it.
                throw new IllegalArgumentException("not a valid pattern: " + regex, malformed);
            }
        }

        private static void requireText(@Nullable String value, String what) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(what + " must not be empty");
            }
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            text.append(prefix != null ? prefix : "~" + classPattern);
            if (memberName != null) text.append('#').append(memberName);
            else if (memberPattern != null) text.append("#~").append(memberPattern);
            return text.toString();
        }
    }

    /** One decision about one {@link Target}. */
    private static final class Rule {
        private final boolean allow;
        private final Target target;

        Rule(boolean allow, Target target) {
            this.allow = allow;
            this.target = target;
        }

        @Override
        public String toString() {
            return (allow ? "allow " : "deny ") + target;
        }
    }

    // ── Building one ────────────────────────────────────────────────────────────────────────────

    /**
     * Assembles a policy.
     *
     * <p>Order does not matter — {@link Target#specificity()} decides, not position — so a builder fed
     * from a file cannot mean something different because the file was sorted.</p>
     *
     * <p>The <b>base posture</b> is implied unless stated: a policy with any allow rule refuses what none
     * of them names, and a policy with only denials permits what none of them names. That is the
     * difference between "only these" and "everything except these", and it is what the two older
     * factories {@link ScriptPolicy#of(List)} and {@link ScriptPolicy#denying(List)} always meant. Say it
     * outright with {@link #allowingEverythingElse()} or {@link #denyingEverythingElse()}.</p>
     */
    public static final class Builder {

        private final List<Rule> rules = new ArrayList<>();
        @Nullable private Boolean base;

        private Builder() {
        }

        public Builder allow(Target... targets) {
            return add(true, targets);
        }

        public Builder deny(Target... targets) {
            return add(false, targets);
        }

        /** The written form — see {@link Target#parse}. */
        public Builder allow(String... specs) {
            return addParsed(true, specs);
        }

        /** The written form — see {@link Target#parse}. */
        public Builder deny(String... specs) {
            return addParsed(false, specs);
        }

        /** For a list read from configuration, and for {@link ScriptPolicy#UNSAFE}. */
        public Builder allow(@Nullable List<String> specs) {
            return addParsed(true, specs);
        }

        /** For a list read from configuration, and for {@link ScriptPolicy#UNSAFE}. */
        public Builder deny(@Nullable List<String> specs) {
            return addParsed(false, specs);
        }

        /** Anything no rule names is reachable. */
        public Builder allowingEverythingElse() {
            this.base = true;
            return this;
        }

        /** Anything no rule names is refused — including when no rule was written at all. */
        public Builder denyingEverythingElse() {
            this.base = false;
            return this;
        }

        public ScriptPolicy build() {
            boolean effectiveBase = base != null ? base : !hasAllow();
            if (rules.isEmpty() && effectiveBase) return ALLOW_ALL;
            return new ScriptPolicy(Collections.unmodifiableList(new ArrayList<>(rules)), effectiveBase);
        }

        private boolean hasAllow() {
            for (Rule rule : rules) {
                if (rule.allow) return true;
            }
            return false;
        }

        private Builder add(boolean allow, @Nullable Target... targets) {
            if (targets == null) return this;
            for (Target target : targets) {
                if (target != null) rules.add(new Rule(allow, target));
            }
            return this;
        }

        private Builder addParsed(boolean allow, @Nullable String... specs) {
            if (specs == null) return this;
            for (String spec : specs) {
                if (spec != null && !spec.isEmpty()) rules.add(new Rule(allow, Target.parse(spec)));
            }
            return this;
        }

        private Builder addParsed(boolean allow, @Nullable List<String> specs) {
            if (specs == null) return this;
            for (String spec : specs) {
                if (spec != null && !spec.isEmpty()) rules.add(new Rule(allow, Target.parse(spec)));
            }
            return this;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final List<Rule> rules;

    /** What a name no rule mentions gets. */
    private final boolean baseAllows;

    private ScriptPolicy(List<Rule> rules, boolean baseAllows) {
        this.rules = rules;
        this.baseAllows = baseAllows;
    }

    public static ScriptPolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Everything <b>except</b> these — the posture a guardrail can actually be written in.
     *
     * <p>The intended pairing is {@code denying(UNSAFE)}. Entries are in the written form, so a member may
     * be named here too.</p>
     */
    public static ScriptPolicy denying(@Nullable List<String> denied) {
        return builder().deny(denied).allowingEverythingElse().build();
    }

    /**
     * Only these.
     *
     * <p>An empty list means <b>nothing</b> is reachable, which is a legitimate posture and not a mistake
     * to be helpfully corrected into {@code allowAll}: a host that means "no Java at all" has to be able to
     * say it, and silently widening a policy is the worst thing this class could do.</p>
     */
    public static ScriptPolicy of(@Nullable List<String> allowed) {
        if (allowed == null) return ALLOW_ALL;
        return builder().allow(allowed).denyingEverythingElse().build();
    }

    /** Only these, minus those. The more specific rule decides — see the class note. */
    public static ScriptPolicy of(@Nullable List<String> allowed, @Nullable List<String> denied) {
        if (allowed == null) return denying(denied);
        return builder().allow(allowed).deny(denied).denyingEverythingElse().build();
    }

    /** Whether anything is refused at all — what lets a consumer skip filtering entirely. */
    public boolean allowsEverything() {
        return rules.isEmpty() && baseAllows;
    }

    /**
     * May a script reach this binary name?
     *
     * <p>An array's element type is what is asked about: {@code java.util.List[]} is reachable exactly when
     * {@code java.util.List} is, and a policy that refused the array form while admitting the element would
     * be refusing a spelling rather than a class. Same for a nested class.</p>
     *
     * <p><b>A class with a permitted member is reachable even when the class itself is refused</b>, and
     * that is not a leak — it is what makes {@code deny java.util.List} + {@code allow java.util.List#size}
     * mean anything. The name has to load and the type has to be nameable for a member of it to be called;
     * refusing the class here would leave the permitted member unreachable and the rule inert, which is the
     * failure mode where a control appears to work and does nothing. What the script may then <em>do</em>
     * with the class is {@link #allowsMember}'s answer, and that still refuses everything but {@code size}.
     * </p>
     */
    public boolean allowsClass(@Nullable String binaryName) {
        // ALLOW-ALL IS ANSWERED FIRST, and the floor applies to every policy after it. The floor exists to
        // stop a script RELAXING the policy in force; with nothing configured there is nothing to relax.
        //
        // The order is also what keeps the default posture free: `allowsEverything` is what lets the type
        // index, the completion list and the loader skip their work entirely, and a floor ahead of it would
        // make every one of them filter on every lookup for a deployment that restricted nothing.
        if (allowsEverything()) return true;
        // AND NOW THE FLOOR, ahead of the rules: a policy cannot permit the thing that enforces policies.
        if (isAlwaysRefused(binaryName)) return false;
        if (binaryName == null || binaryName.isEmpty()) return false;
        String name = elementTypeOf(binaryName);
        // A PRIMITIVE IS NOT A CLASS ANYBODY CAN REACH THROUGH. `int` has no package and no members, and
        // refusing it would make every method taking one undescribable.
        if (name.indexOf('.') < 0 && isPrimitive(name)) return true;

        Rule best = null;
        boolean anyMemberAllowed = false;
        for (Rule rule : rules) {
            if (!rule.target.coversClass(name)) continue;
            if (rule.target.aboutMembers()) {
                if (rule.allow) anyMemberAllowed = true;
                continue;
            }
            if (wins(rule, best)) best = rule;
        }
        if (best != null) return best.allow || anyMemberAllowed;
        return baseAllows || anyMemberAllowed;
    }

    /**
     * May a script use this member of this class?
     *
     * <p>The same contest as {@link #allowsClass}, over the rules that cover the member as well as those
     * that cover only its class — so a member rule decides where there is one and the class rule decides
     * where there is not.</p>
     *
     * <p>Asked with the <b>declaring</b> class rather than the receiver's: an inherited {@code toString()}
     * is a call into the type that declared it, and a policy naming that type means it.</p>
     */
    public boolean allowsMember(@Nullable String ownerBinaryName, @Nullable String memberName) {
        if (allowsEverything()) return true;
        if (isAlwaysRefused(ownerBinaryName)) return false;
        if (ownerBinaryName == null || ownerBinaryName.isEmpty()) return false;
        if (memberName == null || memberName.isEmpty()) return allowsClass(ownerBinaryName);
        String owner = elementTypeOf(ownerBinaryName);

        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.target.coversClass(owner)) continue;
            if (rule.target.aboutMembers() && !rule.target.coversMember(memberName)) continue;
            if (wins(rule, best)) best = rule;
        }
        return best != null ? best.allow : baseAllows;
    }

    /**
     * Which of two matching rules decides.
     *
     * <p>More specific wins; on a tie, <b>a denial wins</b>. The tie-break is not arbitrary — a policy that
     * says both things about one name is a policy whose author has contradicted themselves, and the safe
     * reading of a contradiction is the one that refuses.</p>
     */
    private static boolean wins(Rule candidate, @Nullable Rule incumbent) {
        if (incumbent == null) return true;
        int difference = candidate.target.specificity() - incumbent.target.specificity();
        if (difference != 0) return difference > 0;
        return !candidate.allow && incumbent.allow;
    }

    /**
     * May a script see this package — for a completion root, and for {@code Packages.*}.
     *
     * <p>True when the package is <em>at or under</em> an allowed prefix, and also when an allowed prefix is
     * under <em>it</em>: {@code java} must be offerable for {@code java.util.List} to be reachable through
     * it, or the policy would admit a class by a path it refuses to show.</p>
     *
     * <p><b>A pattern rule makes every path walkable.</b> A regex describes a shape and says nothing about
     * where things live, so there is no prefix to test against — and refusing a root on that basis would
     * hide the classes the pattern permits. Walkability is not permission: {@link #allowsClass} still
     * decides what is at the end of the path.</p>
     */
    public boolean allowsPackage(@Nullable String packageName) {
        if (allowsEverything()) return true;
        // AT OR UNDER THE FLOOR, so `com.crystalgui.language` is not offered as a completion root either.
        // Not the reverse test the denials get below: `com.crystalgui` must stay walkable, or the floor
        // would hide `com.crystalgui.ui` on the way past.
        if (isAlwaysRefused(packageName)) return false;
        if (packageName == null || packageName.isEmpty()) return false;

        // AT OR UNDER A DENIAL ONLY, and never the other way round. `java.lang.reflect` is refused, and
        // `java.lang` is not refused for containing it -- a package is not its worst member. That is the
        // opposite of the allow test below, where an allowed prefix UNDER the package does admit it,
        // because a path has to be walkable to reach what is at the end of it.
        //
        // A member rule never refuses a package: it refuses one thing inside a class, and the class is
        // still there.
        Rule bestDenial = null;
        for (Rule rule : rules) {
            if (rule.allow || rule.target.aboutMembers()) continue;
            String anchor = rule.target.anchor();
            if (anchor != null && prefixMatches(packageName, anchor) && wins(rule, bestDenial)) {
                bestDenial = rule;
            }
        }
        if (bestDenial != null) {
            // ...unless something more specific under it is allowed, or the denial would hide the path to
            // its own exception.
            for (Rule rule : rules) {
                if (!rule.allow) continue;
                String anchor = rule.target.anchor();
                if (anchor == null) return true;
                if (prefixMatches(anchor, packageName) || prefixMatches(packageName, anchor)) {
                    if (rule.target.specificity() > bestDenial.target.specificity()) return true;
                }
            }
            return false;
        }

        if (baseAllows) return true;
        for (Rule rule : rules) {
            if (!rule.allow) continue;
            String anchor = rule.target.anchor();
            if (anchor == null) return true;
            if (prefixMatches(packageName, anchor) || prefixMatches(anchor, packageName)) return true;
        }
        return false;
    }

    /**
     * The element type of an array, in <b>either</b> spelling — or the name unchanged.
     *
     * <p>{@code java.util.List[]} is what a source-level name looks like and what the editor asks about;
     * {@code [Ljava.util.List;} is what the JVM calls the same type, and it is what a {@code ClassShutter}
     * is handed when a script touches one. Handling only the first meant the javadoc's promise about arrays
     * was kept for the surface that never sees one and broken for the surface that does.</p>
     */
    private static String elementTypeOf(String binaryName) {
        String name = binaryName;
        while (name.endsWith("[]")) name = name.substring(0, name.length() - 2);
        int depth = 0;
        while (depth < name.length() && name.charAt(depth) == '[') depth++;
        if (depth == 0) return name;
        String element = name.substring(depth);
        // `[Lfoo.Bar;` is a reference array; `[I`, `[D` and the rest are primitive ones, whose one-letter
        // element name is not a class name at all and is left to the primitive test.
        if (element.startsWith("L") && element.endsWith(";")) {
            return element.substring(1, element.length() - 1);
        }
        return element;
    }

    /** A dot-boundary prefix test — so {@code java.util} does not admit {@code java.utility}. */
    private static boolean prefixMatches(String name, String prefix) {
        if (!name.startsWith(prefix)) return false;
        return name.length() == prefix.length() || name.charAt(prefix.length()) == '.'
                // A NESTED CLASS is separated by `$`, and is part of the class its prefix named.
                || name.charAt(prefix.length()) == '$';
    }

    /**
     * Whether this names a primitive — the source spelling, and the JVM's one-letter array element codes.
     *
     * <p>The second half is why an array of primitives is reachable: the shutter sees {@code [I} for an
     * {@code int[]}, whose element name is {@code I}.</p>
     */
    private static boolean isPrimitive(String name) {
        switch (name) {
            case "boolean": case "byte": case "char": case "short":
            case "int": case "long": case "float": case "double": case "void":
            case "Z": case "B": case "C": case "S":
            case "I": case "J": case "F": case "D":
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        if (allowsEverything()) return "ScriptPolicy[allow all]";
        StringBuilder text = new StringBuilder("ScriptPolicy[");
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) text.append(", ");
            text.append(rules.get(i));
        }
        if (!rules.isEmpty()) text.append(", ");
        return text.append("else ").append(baseAllows ? "allow" : "deny").append(']').toString();
    }
}
