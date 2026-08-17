package com.crystalgui.language.java.assist;

import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolKind;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Renders one symbol's declaration for the Quick Documentation popup — the text, and the capture names
 * that colour it.
 *
 * <h3>Why this is not in EcjSourceAnalyzer</h3>
 *
 * <p>It arrived there and grew to eight hundred lines inside {@code EcjAnalysis}, whose other members
 * answer three unrelated questions: what is wrong with this file, how should it be coloured, and what
 * does this name refer to. Rendering a declaration shares nothing with them but the AST — no state, no
 * helpers, no vocabulary — so it was a second class living in the first one's braces.</p>
 *
 * <h3>Quoted first, always — and from wherever the source actually is</h3>
 *
 * <p>A declaration is <b>quoted</b>: the text its author wrote, sliced whole, so layout, indentation,
 * parameter names and terminator are theirs and only the captures are derived. That used to mean "a
 * symbol in the file being edited", because nothing else had source to quote. {@link AttachedSources}
 * removed the limit rather than the rule — a {@code -sources.jar} beside a jar and the JDK's
 * {@code src.zip} are read exactly as the buffer is — so {@code java.util.List.add} now comes out of
 * {@code src.zip} the same way a method three lines up comes out of the file.</p>
 *
 * <p><b>Assembly is the fallback, and only the fallback.</b> It runs when there is genuinely no source:
 * a mod jar shipped without one, an obfuscated Minecraft jar, a directory of class files. There it does
 * the only thing left — rebuilds the declaration from the binding, in a layout of this class's choosing,
 * without the parameter names a class file does not carry. That is a strictly worse answer, and the
 * whole point of the ordering is that it is now reached only when there is no better one.</p>
 *
 * <p>Which is why the layout machinery stays rather than being deleted with the path it was written for:
 * {@link #MAX_SIGNATURE_LINE}, the break before a long {@code =}, one parameter per line, the hanging
 * indent under {@code implements}. It invents a wrapping the quoted path gets for free, and it is still
 * the only thing keeping a source-less {@code ArrayList} from being 110 characters on one line. What it
 * no longer does is decide how the JDK looks.</p>
 *
 * @see Signature for why the seam carries structure rather than a marked-up string
 * @see AttachedSources for where a classpath symbol's source is found
 */
public final class JavaSignatures {

    private final CompilationUnit unit;
    private final String source;
    /**
     * What colour a name is — <b>the editor's own function, not a second copy of it</b>.
     *
     * <p>Handed in rather than reimplemented, because the two had already drifted twice. A name gets one
     * answer, and a view that renders it differently is now visibly the one at fault. @see #capturesIn</p>
     */
    private final Function<SimpleName, String> nameCaptures;
    /**
     * Where to look when this unit does not declare the symbol — null in the attached units themselves.
     *
     * <p>Null there is what makes the recursion one level deep by construction rather than by a guard: a
     * unit parsed out of {@code src.zip} has nowhere further to look, so it quotes or it does not.</p>
     */
    private final AttachedSources attached;

    public JavaSignatures(CompilationUnit unit, String source, Function<SimpleName, String> nameCaptures) {
        this(unit, source, nameCaptures, null);
    }

    public JavaSignatures(CompilationUnit unit, String source, Function<SimpleName, String> nameCaptures,
                   AttachedSources attached) {
        this.unit = unit;
        this.source = source == null ? "" : source;
        this.nameCaptures = nameCaptures;
        this.attached = attached;
    }

    // ── The rendered declaration ────────────────────────────────────────────────────────────
    //
    // WHY THIS IS HERE AND NOT IN THE WIDGET. Only a binding knows that `public` is a modifier, that
    // `@Nullable` is an annotation and that `x` is a parameter -- and only the language knows what
    // order a declaration reads in. A widget assembling this from name/kind/type can produce Java's
    // shape and nothing else, and every refinement (annotations, visibility, generics, throws,
    // varargs, defaults) would be another field on the seam that no other language populates.
    //
    // The capture names below are §10.1's, the same vocabulary the grammar and the semantic provider
    // speak, so the popup colours this with the rules that colour the editor. @see Signature

    /**
     * How long a declaration may be before it is broken across lines.
     *
     * <p>A count of characters rather than of pixels, because the engine cannot see the box — and
     * does not need to: what it knows is where a break is <em>legal and meaningful</em>, which is the
     * half that cannot be recovered downstream. A widget re-wrapping this at the edge of its box
     * breaks between whatever two words happen to land there, which is how a parameter list ends up
     * split in the middle of a generic type.</p>
     */
    private static final int MAX_SIGNATURE_LINE = 72;

    public Signature of(IBinding binding, SymbolKind kind, String name) {
        // QUOTED FIRST, AND NEVER RE-WRAPPED. The author chose that layout; MAX_SIGNATURE_LINE is a rule
        // for text this class invents, not for text it copies, and applying it to a quote would only
        // re-render the identical slice. Hoisted out of the three branches below when quoting learned to
        // reach the classpath: with a quote available for nearly everything, "try flat, then broken" was
        // running the whole assembly twice to arrive back at the same substring.
        Signature quoted = quoted(binding);
        if (quoted != null) return quoted;

        Signature flat = render(binding, kind, name, false);
        // TRIED FLAT FIRST, and kept if it fits. Breaking unconditionally would put a two-word field
        // declaration on three lines, which is worse than the problem being solved.
        //
        // THE LONGEST LINE, not the total length: a flat render already contains a newline whenever the
        // symbol carries an annotation, so measuring the whole string counts the metadata against the
        // declaration and breaks parameter lists that would have fit comfortably.
        return longestLine(flat.text()) <= MAX_SIGNATURE_LINE ? flat
                : render(binding, kind, name, true);
    }

    private static int longestLine(String text) {
        int longest = 0;
        int from = 0;
        while (from <= text.length()) {
            int end = text.indexOf('\n', from);
            if (end < 0) end = text.length();
            longest = Math.max(longest, end - from);
            from = end + 1;
        }
        return longest;
    }

    private Signature render(IBinding binding, SymbolKind kind, String name, boolean broken) {
        Signature.Builder out = new Signature.Builder();
        // ALWAYS its own line -- see appendAnnotations. Not `broken`: whether the declaration needs
        // wrapping is a question about its length, and where its metadata goes is not.
        appendAnnotations(out, binding.getAnnotations(), true);
        appendModifiers(out, binding.getModifiers(), kind);

        if (binding instanceof IVariableBinding) {
            IVariableBinding variable = (IVariableBinding) binding;
            appendTypeName(out, variable.getType());
            out.raw(" ").append(name, captureForVariable(variable));
            appendInitializer(out, variable, broken);
            return out.build();
        }

        if (binding instanceof IMethodBinding) {
            // THE DECLARATION, NOT THE INSTANTIATION, for the reason the type branch below records:
            // `new ArrayList<>(List.of("one"))` binds the constructor with its parameters already
            // substituted, so the popup said `ArrayList(Collection<? extends String>)` -- true of
            // this call and not of the declaration anybody is asking about.
            IMethodBinding method = ((IMethodBinding) binding).getMethodDeclaration();
            // A GENERIC METHOD DECLARES ITS OWN PARAMETERS, before the return type. Omitted entirely
            // until now, so `static <T> List<T> of(T... elements)` rendered as `static List<T> of(...)`
            // -- with a `T` in the return type and nothing anywhere saying where it came from, which is
            // exactly the complaint the class branch already answers with appendTypeParameters.
            appendMethodTypeParameters(out, method);
            if (!method.isConstructor()) {
                appendTypeName(out, method.getReturnType());
                out.raw(" ");
            }
            // `function.method`, THE DECLARATION COLOUR, because that is what this is. It was briefly
            // `function.call` on the argument that a popup has one subject and needs no emphasis to
            // separate it from anything -- which is true about EMPHASIS and beside the point about
            // IDENTITY. The parity rule is that a name is drawn as what it IS, and what this is is the
            // declaration; marking it as a call states something false in the one box devoted to it.
            //
            // AND A CONSTRUCTOR IS A METHOD DECLARATION, not a class. It took the `constructor` capture
            // on the reasoning that a constructor names its class and should be coloured as one -- which
            // reads well and is not what either reference does: `public ArrayList(Collection<? extends
            // E>)` is a declaration of a member, and drawing its name in the class colour said the box
            // was describing the type while the parameter list beside it said otherwise. The editor
            // reaches the same answer through methodCapture, which splits declaration from use and needs
            // no constructor case either.
            out.append(name, "function.method");
            appendParameters(out, method, broken);
            appendThrows(out, method.getExceptionTypes());
            return out.build();
        }

        if (binding instanceof ITypeBinding) {
            // THE DECLARATION, NOT THE INSTANTIATION. Hovering `new ArrayList<>(List.of("one"))`
            // binds the type as `ArrayList<String>`, whose superclass JDT reports as
            // `AbstractList<String>` -- so the popup claimed ArrayList is declared over String.
            // Documentation is about how a type is DECLARED, which is `ArrayList<E> extends
            // AbstractList<E>`, and getTypeDeclaration is the binding that says so. Both references
            // show the declaration here.
            ITypeBinding type = ((ITypeBinding) binding).getTypeDeclaration();
            out.word(declarationKeyword(type), "keyword");
            out.append(name, typeCapture(type));
            appendTypeParameters(out, type);
            appendSupertypes(out, type, broken);
            return out.build();
        }
        return out.build();
    }

    /**
     * {@code @Nullable}, {@code @Contract(mutates = "this,io")} — simple names, with their arguments.
     *
     * <p>Qualified names would be correct and unreadable: {@code @org.jetbrains.annotations.Nullable}
     * is most of a line for no information a reader wanted. IntelliJ shows the simple name and makes
     * it a link to the full one, which is the same trade with a navigation affordance we do not have
     * yet — {@link Signature} is shaped so that arrives without changing the colouring.</p>
     */
    private static void appendAnnotations(Signature.Builder out,
                                          IAnnotationBinding[] annotations, boolean ownLine) {
        if (annotations == null) return;
        for (IAnnotationBinding annotation : annotations) {
            ITypeBinding type = annotation.getAnnotationType();
            if (type == null) continue;
            out.append("@" + type.getName(), "attribute");
            IMemberValuePairBinding[] pairs = annotation.getDeclaredMemberValuePairs();
            if (pairs != null && pairs.length > 0) {
                out.append("(", "punctuation.bracket");
                for (int i = 0; i < pairs.length; i++) {
                    if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                    // A SINGLE `value` MEMBER IS WRITTEN BARE in source -- @Contract("...") rather
                    // than @Contract(value = "..."). Printing the name back would be correct Java and
                    // not what anybody wrote.
                    if (pairs.length > 1 || !"value".equals(pairs[i].getName())) {
                        out.append(pairs[i].getName(), "property").raw(" ")
                                .append("=", "operator").raw(" ");
                    }
                    appendAnnotationValue(out, pairs[i].getValue());
                }
                out.append(")", "punctuation.bracket");
            }
            // EACH ON ITS OWN LINE, ALWAYS -- which is what both references do, and what makes a
            // method with a @Contract readable at all: the annotation is about the declaration rather
            // than part of it, so running them together buries the signature after its own metadata.
            //
            // This used to be `if (broken)`, i.e. only once the declaration had already grown too long
            // for one line -- so whether an annotation got its own line depended on how many characters
            // happened to follow it. `@SuppressWarnings("unused")` on a long method was correct and
            // `@FunctionalInterface` on a short interface was not, which reads as the rule working
            // intermittently rather than as it being the wrong rule.
            //
            // The flag survives for PARAMETER annotations, which are the opposite case: `@Nullable
            // String x` is one item in a list and a break there splits the list, not the metadata.
            if (ownLine) out.newline(); else out.raw(" ");
        }
    }

    /**
     * Visibility first, then the rest — Java's own conventional order.
     *
     * <p>Read from the modifier flags rather than from {@code SymbolModifier}, which carries no
     * visibility at all. That absence is right on the seam: three more enum constants would be three
     * more things every engine must populate for a fact only Java-shaped languages have. Here there
     * is a binding, so the words come out of the flags and go straight into the rendered text.</p>
     */
    private static void appendModifiers(Signature.Builder out, int flags, SymbolKind kind) {
        if (Modifier.isPublic(flags)) out.word("public", "keyword");
        if (Modifier.isProtected(flags)) out.word("protected", "keyword");
        if (Modifier.isPrivate(flags)) out.word("private", "keyword");
        if (Modifier.isStatic(flags)) out.word("static", "keyword");
        // An interface's methods are implicitly abstract and nobody writes it; an interface itself is
        // implicitly abstract too. Printing it back is noise that is not in the source.
        if (Modifier.isAbstract(flags) && kind != SymbolKind.INTERFACE) out.word("abstract", "keyword");
        if (Modifier.isFinal(flags)) out.word("final", "keyword");
        if (Modifier.isSynchronized(flags)) out.word("synchronized", "keyword");
        if (Modifier.isVolatile(flags)) out.word("volatile", "keyword");
        if (Modifier.isTransient(flags)) out.word("transient", "keyword");
        if (Modifier.isNative(flags)) out.word("native", "keyword");
        if (Modifier.isDefault(flags)) out.word("default", "keyword");
    }

    /**
     * {@code (@Nullable String x, int count)} — with real names when this file declares the method.
     *
     * <p><b>Names only when the declaration is in this unit.</b> {@code IMethodBinding} exposes
     * parameter types and not names, because a class read off the classpath genuinely has none unless
     * it was built with {@code -parameters}; the names live on the {@code MethodDeclaration}, which
     * exists only for source. IntelliJ shows {@code x} for {@code println} because it has the JDK
     * sources attached, and falls back to types exactly as this does when it does not.</p>
     *
     * <p>So a classpath method reads {@code println(String)} and one in the open file reads
     * {@code println(String x)}. That difference is real information — it says where the source is —
     * rather than an inconsistency to paper over with {@code arg0}.</p>
     */
    private void appendParameters(Signature.Builder out, IMethodBinding method, boolean broken) {
        out.append("(", "punctuation.bracket");
        ITypeBinding[] types = method.getParameterTypes();
        List<String> names = parameterNames(method);
        boolean perLine = broken && types.length > 0;
        if (perLine) out.newline();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                out.append(",", "punctuation.delimiter");
                if (perLine) out.newline(); else out.raw(" ");
            }
            if (perLine) out.indent();
            IAnnotationBinding[] onParameter = method.getParameterAnnotations(i);
            appendAnnotations(out, onParameter, false);
            boolean varargs = method.isVarargs() && i == types.length - 1;
            if (varargs) {
                appendTypeName(out, types[i].isArray() ? types[i].getElementType() : types[i]);
                out.append("...", "punctuation.bracket");
            } else {
                appendTypeName(out, types[i]);
            }
            if (names != null && i < names.size()) {
                out.raw(" ").append(names.get(i), "variable.parameter");
            }
        }
        if (perLine) out.newline();
        out.append(")", "punctuation.bracket");
    }

    /**
     * Null when this unit does not declare the method — see the note on {@link #appendParameters}.
     *
     * <p><b>A record's canonical constructor is declared by the RECORD</b>, not by a
     * {@code MethodDeclaration}: nobody wrote it, so {@code findDeclaringNode} answers the record itself
     * and the names are its components. Without this a record in the file being edited rendered
     * {@code Message(String, Severity, long)} — indistinguishable from a classpath type with no sources
     * attached, and wrong in a way that reads as the engine not knowing about a file it is compiling.</p>
     */
    private List<String> parameterNames(IMethodBinding method) {
        ASTNode declaration = unit.findDeclaringNode(method);
        if (declaration instanceof MethodDeclaration) {
            return namesOf(((MethodDeclaration) declaration).parameters());
        }
        // NULL, NOT A RECORD NODE, for a canonical constructor — findDeclaringNode answers for a binding
        // that has a declaration, and this one has none: it is implied by the header. So the question has
        // to be asked of the TYPE, which does.
        if (declaration == null && method.isConstructor()) {
            ITypeBinding owner = method.getDeclaringClass();
            declaration = owner == null ? null : unit.findDeclaringNode(owner);
        }
        if (declaration == null) return null;
        return namesOf(structuralList(declaration, "recordComponents"));
    }

    private static List<String> namesOf(List<?> declarations) {
        if (declarations == null) return null;
        List<String> names = new ArrayList<>();
        for (Object each : declarations) {
            if (each instanceof SingleVariableDeclaration) {
                names.add(((SingleVariableDeclaration) each).getName().getIdentifier());
            }
        }
        return names.isEmpty() ? null : names;
    }

    /**
     * A named child list of any node, <b>without naming the node's class</b>.
     *
     * <p>{@code RecordDeclaration} arrived in JDT with Java 14 and this adapter is compiled against the
     * OLDEST band, where the class does not exist — naming it in a cast or a signature makes the whole
     * class unloadable there, which is the trap {@code TextBlock} already documents. A structural
     * property lookup asks the AST by name instead, so an older band simply finds nothing.</p>
     */
    private static List<?> structuralList(ASTNode node, String property) {
        for (Object each : node.structuralPropertiesForType()) {
            if (!(each instanceof ChildListPropertyDescriptor)) continue;
            ChildListPropertyDescriptor descriptor = (ChildListPropertyDescriptor) each;
            if (property.equals(descriptor.getId())) return (List<?>) node.getStructuralProperty(descriptor);
        }
        return null;
    }

    private static void appendThrows(Signature.Builder out, ITypeBinding[] thrown) {
        if (thrown == null || thrown.length == 0) return;
        out.raw(" ").word("throws", "keyword");
        for (int i = 0; i < thrown.length; i++) {
            if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
            appendTypeName(out, thrown[i]);
        }
    }

    /**
     * {@code <E>}, {@code <K, V>}, {@code <T extends Comparable<T>>} — a generic type's own parameters,
     * <b>as declared, bounds included</b>.
     *
     * <p>Without these {@code ArrayList} renders as a raw type, which is the one thing it is not:
     * {@code class ArrayList} beside {@code extends AbstractList<E>} says the parameter came from
     * nowhere.</p>
     *
     * <h3>A DECLARATION of a type variable is not a USE of one</h3>
     *
     * <p>{@link #appendTypeName} renders the use — a bare {@code T}, which is right everywhere a
     * parameter is referred to and wrong in the one place it is introduced. Routing this through it
     * silently reduced {@code class Box<T extends Comparable<T>>} to {@code class Box<T>}: not a
     * mis-colour but a missing constraint, in a box whose entire job is to say what the constraint is.
     * The bound is often the most load-bearing half of the declaration.</p>
     *
     * <p>JDT returns an empty bound array when the only bound is {@code Object}, which is the same
     * omission {@code appendSupertypes} makes and for the same reason — nobody wrote it.</p>
     */
    private static void appendTypeParameters(Signature.Builder out, ITypeBinding type) {
        appendParameterList(out, type.getTypeParameters());
    }

    /** The shared {@code <A, B extends C>} rendering — a type declares these and so does a method. */
    private static void appendParameterList(Signature.Builder out, ITypeBinding[] parameters) {
        if (parameters == null || parameters.length == 0) return;
        out.append("<", "punctuation.bracket");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
            out.append(parameters[i].getName(), "type.parameter");
            appendTypeBounds(out, parameters[i]);
        }
        out.append(">", "punctuation.bracket");
    }

    /** {@code <T>} on a generic method, which sits before the return type rather than after the name. */
    private static void appendMethodTypeParameters(Signature.Builder out, IMethodBinding method) {
        appendParameterList(out, method.getTypeParameters());
        if (method.getTypeParameters() != null && method.getTypeParameters().length > 0) out.raw(" ");
    }

    /**
     * {@code extends Comparable<T> & Serializable} on a type parameter's declaration.
     *
     * <p><b>{@code extends Object} is skipped</b>, the same omission {@link #appendSupertypes} makes and
     * for the same reason — every unbounded parameter has it and no source file contains it. JDT does
     * <em>not</em> report an empty array for the unbounded case, which was the assumption this shipped
     * with: {@code ArrayList<E>} came out as {@code ArrayList<E extends Object>}.</p>
     */
    private static void appendTypeBounds(Signature.Builder out, ITypeBinding parameter) {
        ITypeBinding[] bounds = parameter.getTypeBounds();
        if (bounds == null || bounds.length == 0) return;
        if (bounds.length == 1 && "java.lang.Object".equals(bounds[0].getQualifiedName())) return;
        out.raw(" ").word("extends", "keyword");
        for (int i = 0; i < bounds.length; i++) {
            // `&`, not `,` -- a type parameter takes intersection bounds, and a comma here would read
            // as a second parameter.
            if (i > 0) out.raw(" ").append("&", "operator").raw(" ");
            appendTypeName(out, bounds[i]);
        }
    }

    /**
     * {@code extends Foo implements Bar} — the supertypes, minus the ones nobody writes, and broken
     * one clause per line when the declaration is long.
     *
     * <p>{@code extends Object} is on every class and is in no source file; {@code extends Enum<E>}
     * is compiler bookkeeping for an enum. Printing either back is a declaration the user never wrote
     * appearing in a box that claims to show what they did.</p>
     *
     * <p>The breaks matter more here than anywhere else: {@code ArrayList} implements four interfaces,
     * so its declaration is 110 characters on one line and unreadable. Both references put
     * {@code extends} and {@code implements} on their own lines and give each interface a line.</p>
     */
    private static void appendSupertypes(Signature.Builder out, ITypeBinding type, boolean broken) {
        ITypeBinding superclass = type.getSuperclass();
        if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())
                && !type.isEnum() && !type.isInterface()) {
            if (broken) out.newline(); else out.raw(" ");
            out.word("extends", "keyword");
            appendTypeName(out, superclass);
        }
        ITypeBinding[] interfaces = type.getInterfaces();
        if (interfaces == null || interfaces.length == 0) return;
        if (broken) out.newline(); else out.raw(" ");
        String keyword = type.isInterface() ? "extends" : "implements";
        out.word(keyword, "keyword");

        // A HANGING INDENT, not a block one, and the difference is visible: the FIRST interface stays
        // on the keyword's line and the rest align under it, so the list reads as one clause with its
        // items stacked. Putting every interface on its own indented line instead leaves `implements`
        // alone on a line of its own, which reads as a heading over a list rather than as a sentence.
        //
        // The pad is the keyword's own length plus its space -- 11 for `implements`, 8 for an
        // interface's `extends` -- so it is derived rather than a magic number.
        //
        // IT IS EXACT ONLY IN A MONOSPACE FONT, and this comment used to say we ship none -- naming
        // Minecraft.otf and MinecraftRegular.otf as the only two assets. **That is no longer true.**
        // JetBrainsMono-Regular.ttf ships and `.__syntax__` already declares it, which is exactly the
        // class the popup's signature carries, so a space count aligns here for the same reason it
        // aligns for IntelliJ. The option this note called out as the honest fix was taken.
        //
        // It still under-indents anywhere the family fails to resolve and the proportional fallback
        // draws instead, which is the case worth remembering rather than the one that was fixed.
        boolean perLine = broken && interfaces.length > 1;
        String pad = spaces(keyword.length() + 1);
        for (int i = 0; i < interfaces.length; i++) {
            if (i > 0) {
                out.append(",", "punctuation.delimiter");
                if (perLine) out.newline().raw(pad); else out.raw(" ");
            }
            appendTypeName(out, interfaces[i]);
        }
    }

    /** {@code String.repeat} is Java 11, and this class is loaded by the band-8 child. */
    private static String spaces(int count) {
        StringBuilder pad = new StringBuilder(count);
        for (int i = 0; i < count; i++) pad.append(' ');
        return pad.toString();
    }

    /**
     * The declaration <b>as its author wrote it</b> — from this unit, or from an attached source
     * archive — or null when there is no source for it anywhere.
     *
     * <h3>Two lookups, one rule</h3>
     *
     * <p>The file being edited is asked first, by binding, because that is both the commonest case and
     * the only one where the binding object itself is the key. Everything else is asked of
     * {@link AttachedSources} by the binding's <em>key string</em>, which is what lets two independent
     * parses agree: a JDT key is derived from the signature, so the key this unit reports for
     * {@code List.add} is character-for-character the key a unit parsed out of {@code src.zip} reports
     * for its declaration — provided both resolved against the same classpath, which is why
     * {@code AttachedSources} is handed one.</p>
     *
     * <p><b>The declaration binding, never the use.</b> {@code List<String>.add} and {@code List<E>.add}
     * have different keys and only the second exists in a file, so a parameterized binding has to be
     * reduced first — the same reduction the assembled path already made for a different reason (a
     * popup describes how a thing is <em>declared</em>, not how this one call site parameterized it).</p>
     *
     * <p>Recursion stops by construction: an attached unit is built with no {@code AttachedSources} of
     * its own, so it either quotes from its own text or answers null.</p>
     */
    private Signature quoted(IBinding binding) {
        if (binding == null) return null;
        Signature here = quotedNode(unit == null ? null : unit.findDeclaringNode(binding));
        if (here != null) return here;
        if (attached == null) return null;

        String topLevel = topLevelSourceName(binding);
        if (topLevel == null) return null;
        AttachedSources.Attached source = attached.unitFor(topLevel);
        if (source == null || source.unit == null) return null;

        String key = declarationKeyOf(binding);
        if (key == null) return null;
        ASTNode declaration = source.unit.findDeclaringNode(key);
        if (declaration == null) return null;
        return new JavaSignatures(source.unit, source.text, nameCaptures).quotedNode(declaration);
    }

    /** Which of the two quoting shapes a declaring node is, or null if it is neither. */
    private Signature quotedNode(ASTNode declaration) {
        if (declaration instanceof VariableDeclarationFragment) return quotedFragment(declaration);
        if (declaration instanceof BodyDeclaration) return quotedHeaderOf(declaration);
        // A PARAMETER, A CATCH VARIABLE AND AN ENHANCED-FOR VARIABLE are all this one node, and all three
        // were being assembled from the binding purely because nothing dispatched them. They have a
        // declaration written down like anything else -- and it carries things the binding cannot report
        // in the right shape: `final`, a varargs `...` rather than an array type, and an annotation on
        // the same line, which is where the author put it and where assembly would not have.
        if (declaration instanceof SingleVariableDeclaration) {
            return quoteWhole(declaration);
        }
        return null;
    }

    /**
     * The name of the file a symbol would be declared in — its outermost enclosing type.
     *
     * <p>A source archive is keyed by compilation unit, so a nested class, a method and a field all have
     * to resolve to the same top-level name before anything can be looked up.</p>
     *
     * <p>Null for the things that are not declared in a file at all: a local, a parameter, a type
     * variable, a primitive, an array. Each of those has either no declaring class or no name to look
     * up, and asking anyway would put a lookup for {@code T.java} in front of every hover.</p>
     */
    private static String topLevelSourceName(IBinding binding) {
        ITypeBinding type = declaringTypeOf(binding);
        if (type == null) return null;
        while (type.getDeclaringClass() != null) type = type.getDeclaringClass();
        type = type.getTypeDeclaration();
        if (type.isPrimitive() || type.isArray() || type.isTypeVariable() || type.isWildcardType()) {
            return null;
        }
        String qualified = type.getBinaryName();
        if (qualified == null || qualified.isEmpty()) qualified = type.getQualifiedName();
        // A LOCAL OR ANONYMOUS CLASS has a binary name like `Outer$1`, which is not a file. The `$` is
        // also how a nested type spells itself, but the walk above has already reached the outermost
        // one -- so anything still carrying a `$` here is a class with no source name of its own.
        if (qualified == null || qualified.isEmpty() || qualified.indexOf('$') >= 0) return null;
        return qualified;
    }

    private static ITypeBinding declaringTypeOf(IBinding binding) {
        if (binding instanceof ITypeBinding) return (ITypeBinding) binding;
        if (binding instanceof IMethodBinding) return ((IMethodBinding) binding).getDeclaringClass();
        if (binding instanceof IVariableBinding) return ((IVariableBinding) binding).getDeclaringClass();
        return null;
    }

    /** The key of the DECLARATION a binding came from, which is the only key a source file contains. */
    private static String declarationKeyOf(IBinding binding) {
        if (binding instanceof ITypeBinding) {
            return ((ITypeBinding) binding).getTypeDeclaration().getKey();
        }
        if (binding instanceof IMethodBinding) {
            return ((IMethodBinding) binding).getMethodDeclaration().getKey();
        }
        if (binding instanceof IVariableBinding) {
            IVariableBinding declaration = ((IVariableBinding) binding).getVariableDeclaration();
            return declaration == null ? binding.getKey() : declaration.getKey();
        }
        return binding.getKey();
    }

    /**
     * The declaration <b>exactly as it appears in the file</b>, semicolon and all.
     *
     * <h3>Quoted, not assembled</h3>
     *
     * <p>Everything else here builds a declaration out of parts and chooses its own layout: modifiers
     * in a fixed order, a space before the {@code =}, a break when the line runs long. That is the only
     * option for a symbol whose source is nowhere to be found — which since {@link AttachedSources} means
     * a jar shipped without sources rather than "the classpath". Wherever there IS a file it is strictly
     * worse, and it went wrong in two ways at once.</p>
     *
     * <p>The <b>layout</b> stopped matching: the file has {@code List<Shape> shapes = List.of(} on one
     * line, and the assembled form imposed a break before the {@code =} on top of the author's own
     * wrapping — so the popup showed a shape the file does not contain, with the arguments carrying the
     * file's indentation on top of ours. And the <b>semicolon</b> was missing, because an initializer
     * <em>expression</em> ends before it; the statement is the thing that has one.</p>
     *
     * <p>Both are the same mistake — reconstructing what is already written down. The fragment's
     * parent spans modifiers, type, name, initializer and terminator, so quoting it is one substring,
     * and the captures come off the AST at positions into that very string.</p>
     */
    private Signature quotedFragment(ASTNode fragment) {
        ASTNode declaration = fragment.getParent();
        // THE THREE PARENTS THAT ARE A DECLARATION IN THEIR OWN RIGHT. A field, a local statement, and
        // the init clause of a `for` -- which reads as an exception and is not one: the clause node
        // spans `int i = 0` and stops, so quoting it drags in no more of the loop than a local
        // declaration drags in of its method. It was excluded on the assumption that its parent WAS the
        // loop, so `i` in every counting loop in the file was assembled instead of quoted.
        if (!(declaration instanceof FieldDeclaration)
                && !(declaration instanceof VariableDeclarationStatement)
                && !(declaration instanceof VariableDeclarationExpression)) {
            return null;
        }
        int from = declaration.getStartPosition();
        int length = declaration.getLength();
        if (from < 0 || length <= 0 || from + length > source.length()) return null;
        int end = from + length;

        // THE DOC COMMENT IS NOT PART OF THE DECLARATION, whatever the node spans. A FieldDeclaration
        // covers its own Javadoc, so quoting it put a paragraph of prose into the SIGNATURE band --
        // the one band meant to hold a single declaration, sitting directly above the band whose
        // whole purpose is documentation.
        //
        // Skipped by READING THE TEXT rather than by asking getJavadoc(), which answers null unless
        // the parser was configured with doc-comment support -- and it still is not, so the node
        // covered the comment while the accessor denied it existed. Scanning also catches the ordinary
        // `//` and `/* */` comments a Javadoc node would never have represented.
        from = skipLeadingComments(from, end);
        if (from >= end) return null;

        // THE WHOLE DECLARATION, INITIALIZER AND ALL. It was briefly cut before any initializer that was
        // not a literal, on the stated grounds that IntelliJ shows a local as its type and name -- which
        // was asserted rather than measured, and is not what IntelliJ does: it quotes the declaration in
        // full, all four lines of a wrapped `List.of(...)` included. Recorded because the reasoning was
        // otherwise sound and will be re-proposed: a long declaration is a LAYOUT problem for the popup
        // to solve, not a licence to show something other than what was written.
        return quote(declaration, from, end, false);
    }

    /**
     * A node that <b>is</b> its own declaration — sliced whole, with any leading comment trimmed.
     *
     * <p>The simplest of the three quoting shapes, and the one with nothing to decide: a parameter has no
     * body to cut before and no initializer to weigh, so its own extent is the answer.</p>
     */
    private Signature quoteWhole(ASTNode declaration) {
        int from = declaration.getStartPosition();
        int end = from + declaration.getLength();
        if (from < 0 || end > source.length() || end <= from) return null;
        from = skipLeadingComments(from, end);
        return from >= end ? null : quote(declaration, from, end, false);
    }

    /**
     * The <b>header</b> of a type or method declared in this unit — everything up to its body brace.
     *
     * <h3>Why this exists at all, and what it retires</h3>
     *
     * <p>The assembled renderer knows a fixed list of things a declaration can contain: modifiers, a
     * keyword, a name, type parameters, {@code extends}, {@code implements}, parameters, {@code throws}.
     * Java keeps adding to that list. {@code sealed} is a modifier the flag constants only gained in a
     * later JDT, {@code permits} has no accessor the oldest band can name at all, and {@code non-sealed}
     * is a keyword no {@code Modifier} query returns — so
     * {@code public sealed interface Shape permits Circle, Rectangle, Triangle} came out as
     * {@code public static interface Shape}: two clauses silently gone, and a {@code static} the author
     * never wrote, because a nested interface carries that flag implicitly.</p>
     *
     * <p><b>Every one of those is the same bug, and chasing them one clause at a time is the wrong
     * shape of work.</b> A symbol declared in the file being edited has its declaration written down
     * already: quoting it is one substring, and it is right about every keyword the language has now and
     * every one it gains later, including the implicit modifiers a binding reports and nobody typed.</p>
     *
     * <h3>Where the header ends</h3>
     *
     * <p>At the first {@code &#123;} after the last modifier — the body brace. Scanning starts after the
     * modifier list rather than at the declaration, because an annotation argument may contain a brace
     * ({@code @Target(&#123;METHOD, FIELD&#125;)}) and it is the only thing in a header that can. An
     * abstract method has no brace, so the node's own end is used and the trailing {@code ;} trimmed.</p>
     */
    private Signature quotedHeaderOf(ASTNode declaration) {
        int from = declaration.getStartPosition();
        int end = from + declaration.getLength();
        if (from < 0 || end > source.length() || end <= from) return null;
        from = skipLeadingComments(from, end);
        if (from >= end) return null;

        int scanFrom = from;
        for (Object modifier : ((BodyDeclaration) declaration).modifiers()) {
            ASTNode node = (ASTNode) modifier;
            scanFrom = Math.max(scanFrom, node.getStartPosition() + node.getLength());
        }
        int brace = source.indexOf('{', scanFrom);
        if (brace >= 0 && brace < end) end = brace;
        while (end > from && (Character.isWhitespace(source.charAt(end - 1))
                || source.charAt(end - 1) == ';')) {
            end--;
        }
        return end <= from ? null : quote(declaration, from, end, true);
    }

    /** The shared tail of both quoting paths: slice, dedent, and take the captures off the AST. */
    private Signature quote(ASTNode declaration, int from, int end, boolean header) {
        String slice = source.substring(from, end);
        boolean tooLong = slice.length() > MAX_DECLARATION_CHARS;
        if (tooLong) slice = slice.substring(0, MAX_DECLARATION_CHARS);

        Dedented body = dedent(slice, indentColumnOf(from));

        Signature.Builder out = new Signature.Builder();
        out.raw(body.text);
        if (tooLong) out.raw("…");
        // Walks the WHOLE declaration, body included, and `mark` drops anything past the slice --
        // the same bounds check that makes the truncation above safe.
        List<Capture> captures = capturesIn(declaration, from, slice);
        if (header) markHeaderKeywords(slice, captures);
        for (Capture capture : captures) {
            out.tokenAt(body.map(capture.start), body.map(capture.end), capture.name);
        }
        return out.build();
    }

    /**
     * The declaration keywords that <b>have no AST node to hang a capture on</b>.
     *
     * <p>{@code public} and {@code final} are {@code Modifier} nodes and are already marked; {@code class},
     * {@code extends}, {@code implements}, {@code permits} and {@code throws} are bare tokens the AST
     * records only as structure. The assembled renderer wrote each one itself and coloured it in passing,
     * so quoting silently dropped every one of them — a header in two colours where it used to be in
     * three, which is the kind of loss that looks like a scheme change rather than a missing capture.</p>
     *
     * <p>A whole-word scan is safe HERE and would not be in general: the region is one declaration
     * header, which cannot contain a comment (it ends at the body brace) and whose only string is inside
     * an annotation argument — already captured, and skipped for that reason. {@code permits},
     * {@code record} and {@code sealed} are CONTEXTUAL keywords, legal as identifiers elsewhere, which is
     * the other half of why this is not applied to the variable path.</p>
     */
    private static void markHeaderKeywords(String slice, List<Capture> captures) {
        for (String word : HEADER_KEYWORDS) {
            int at = slice.indexOf(word);
            while (at >= 0) {
                int end = at + word.length();
                boolean whole = (at == 0 || !isWordChar(slice.charAt(at - 1)))
                        && (end == slice.length() || !isWordChar(slice.charAt(end)));
                if (whole && !overlapsCapture(captures, at, end)) {
                    captures.add(new Capture(at, end, "keyword"));
                }
                at = slice.indexOf(word, at + 1);
            }
        }
    }

    private static final String[] HEADER_KEYWORDS = {
            // `super` and `extends` reach here only as WILDCARD BOUNDS -- the one place in a header
            // either can appear, and the place `super` cannot possibly be the call the vendored grammar
            // captures it as.
            "non-sealed", "implements", "interface", "extends", "permits", "throws",
            "sealed", "record", "class", "enum", "super",
    };

    private static boolean isWordChar(char c) {
        return Character.isJavaIdentifierPart(c) || c == '-';
    }

    private static boolean overlapsCapture(List<Capture> captures, int start, int end) {
        for (Capture capture : captures) {
            if (capture.start < end && start < capture.end) return true;
        }
        return false;
    }

    /** The first position at or after {@code from} that is neither whitespace nor a comment. */
    private int skipLeadingComments(int from, int end) {
        int at = from;
        while (at < end) {
            if (Character.isWhitespace(source.charAt(at))) {
                at++;
            } else if (source.startsWith("/*", at)) {
                int close = source.indexOf("*/", at + 2);
                if (close < 0 || close + 2 > end) return end;
                at = close + 2;
            } else if (source.startsWith("//", at)) {
                int newline = source.indexOf('\n', at);
                if (newline < 0 || newline > end) return end;
                at = newline + 1;
            } else {
                return at;
            }
        }
        return end;
    }

    /** How far into its own line the declaration starts — the indent its first line lost. */
    private int indentColumnOf(int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int column = 0;
        while (lineStart + column < offset && isBlank(source.charAt(lineStart + column))) column++;
        return lineStart + column == offset ? column : 0;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * The quoted text with its continuation lines re-anchored, plus the offset map that goes with it.
     *
     * <h3>The first line loses an indent the others keep</h3>
     *
     * <p>A slice starts <em>at</em> the declaration, so whatever whitespace preceded it on its line is
     * not in the slice — but every line after the first still carries its full column. Quote a
     * statement indented eight columns whose arguments are indented sixteen and the popup shows a
     * first line at zero with arguments at sixteen: the relative indent doubles, and the deeper the
     * declaration sits in the file the worse it gets.</p>
     *
     * <p>So each continuation line gives back up to {@code column} leading blanks — exactly what the
     * first line lost. Relative indentation is preserved, which is the part that carries meaning; the
     * absolute column is a fact about the file, not about the declaration.</p>
     *
     * <p>Removing characters moves every offset after them, so the captures cannot be applied to the
     * result directly — hence the map. Building it here rather than recomputing positions later is
     * what keeps the text and the colours derived from one pass.</p>
     */
    private static Dedented dedent(String slice, int column) {
        if (column <= 0 || slice.indexOf('\n') < 0) return Dedented.identity(slice);

        StringBuilder out = new StringBuilder(slice.length());
        int[] map = new int[slice.length() + 1];
        boolean lineStart = false;
        int i = 0;
        while (i < slice.length()) {
            if (lineStart) {
                int given = 0;
                while (given < column && i < slice.length() && isBlank(slice.charAt(i))) {
                    map[i++] = out.length();
                    given++;
                }
                lineStart = false;
                continue;
            }
            char c = slice.charAt(i);
            map[i++] = out.length();
            out.append(c);
            if (c == '\n') lineStart = true;
        }
        map[slice.length()] = out.length();
        return new Dedented(out.toString(), map);
    }

    private static final class Dedented {
        final String text;
        private final int[] map;

        Dedented(String text, int[] map) {
            this.text = text;
            this.map = map;
        }

        static Dedented identity(String text) {
            return new Dedented(text, null);
        }

        int map(int offset) {
            if (map == null) return offset;
            int at = Math.max(0, Math.min(offset, map.length - 1));
            return map[at];
        }
    }

    /** A declaration longer than this stops being a signature and starts being the file. */
    private static final int MAX_DECLARATION_CHARS = 400;

    /**
     * {@code = null}, {@code = '\t'}, {@code = 1.618_033_988_749d} — the initializer <b>as written</b>.
     *
     * <h3>Read from the AST, not from the folded constant</h3>
     *
     * <p>{@code getConstantValue()} only answers for a compile-time constant, which means primitives
     * and {@code String} and nothing else — so {@code private static final Object NOTHING = null}
     * showed no initializer at all, because {@code null} is not one. There is no way to tell "not a
     * constant" from "the constant is null" through that API, and the field plainly has an
     * initializer either way.</p>
     *
     * <p>The declaring node has it verbatim, and verbatim is also <em>better</em> for the cases the
     * folded value did cover: {@code 1.618_033_988_749d} keeps its underscores and its suffix,
     * {@code 0xDEAD_BEEF} stays hex instead of becoming {@code -559038737}, and a string keeps the
     * escapes the author wrote rather than being folded and re-escaped back into a different spelling
     * of the same bytes. IntelliJ shows the source form for exactly this reason.</p>
     *
     * <p>Falls back to the folded constant for a field on the classpath, where there is no AST.</p>
     */
    private void appendInitializer(Signature.Builder out, IVariableBinding variable,
                                   boolean broken) {
        ASTNode declaring = unit.findDeclaringNode(variable);
        if (declaring instanceof VariableDeclarationFragment) {
            Expression initializer = ((VariableDeclarationFragment) declaring).getInitializer();
            if (initializer != null) {
                // BEFORE THE `=`, indented -- IntelliJ's own break for a long field, and it keeps the
                // declaration (which is what you asked about) on a line of its own.
                if (broken) out.newline().indent(); else out.raw(" ");
                out.append("=", "operator").raw(" ");
                appendInitializerExpression(out, initializer);
                return;
            }
        }
        Object constant = variable.getConstantValue();
        if (constant == null) return;
        if (broken) out.newline().indent(); else out.raw(" ");
        out.append("=", "operator").raw(" ");
        appendLiteral(out, literalOf(constant),
                constant instanceof String || constant instanceof Character ? "string"
                        : constant instanceof Boolean ? "boolean" : "number");
    }

    /**
     * The initializer, <b>quoted from the source</b> and coloured from the AST.
     *
     * <h3>The text is the author's; only the colours are ours</h3>
     *
     * <p>This used to re-render the expression node by node, choosing where the spaces went — which
     * meant inventing rules ({@code ", "} after an argument, a space around an operator) that are
     * only ever an approximation of what was actually typed, and getting them wrong in ways nobody
     * can correct from the popup. The author already wrote the spacing and an AST node knows exactly
     * which characters it came from, so slicing them back out is both less code and more faithful:
     * a multi-line {@code List.of(...)} keeps its layout, an aligned array keeps its alignment, and
     * anything this walk does not recognise still comes out verbatim rather than reformatted.</p>
     *
     * <p>Before that it was {@code ASTNode.toString()}, which is JDT's {@code NaiveASTFlattener} —
     * that is where {@code Circle(1.5d),new Rectangle} came from, and it had no captures at all.</p>
     *
     * <h3>Captures come from a separate pass, in source coordinates</h3>
     *
     * <p>Every node reports {@code getStartPosition()} into the same string the slice was cut from,
     * so a token's offset within the slice is one subtraction. That is the whole reason this split
     * works: the text and the captures are derived from the same coordinates rather than being
     * rebuilt in parallel and hoped to agree.</p>
     */
    private void appendInitializerExpression(Signature.Builder out, Expression node) {
        int from = node.getStartPosition();
        int length = node.getLength();
        if (from < 0 || length <= 0 || from + length > source.length()) {
            // No usable position -- a recovered node, or a source we were not handed. The flattened
            // form is a poorer answer and still an answer.
            out.raw(truncated(node.toString()));
            return;
        }

        String slice = source.substring(from, from + length);
        boolean tooLong = slice.length() > MAX_INITIALIZER_CHARS;
        if (tooLong) slice = slice.substring(0, MAX_INITIALIZER_CHARS);

        int base = out.length();
        out.raw(slice);
        if (tooLong) out.raw("\u2026");

        for (Capture capture : capturesIn(node, from, slice)) {
            out.tokenAt(base + capture.start, base + capture.end, capture.name);
        }
    }

    /** How much of an initializer is worth showing before it stops being a declaration. */
    private static final int MAX_INITIALIZER_CHARS = 160;

    private static final class Capture {
        final int start;
        final int end;
        final String name;

        Capture(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }
    }

    /**
     * Every part of {@code node} worth colouring, as offsets into the slice that starts at
     * {@code from}.
     *
     * <p>A visitor rather than the recursive render it replaced, because it no longer has to produce
     * text in order — it only has to notice the nodes the scheme has a colour for. Anything it does
     * not visit simply stays the surrounding text's colour, which is the right default: an
     * unrecognised construct reads as plain code rather than as a guess.</p>
     */
    private List<Capture> capturesIn(ASTNode node, int from, String slice) {
        List<Capture> captures = new ArrayList<>();
        node.accept(new ASTVisitor() {
            /**
             * <b>Last statement about a range wins, and an earlier one about the same range is
             * dropped rather than kept beside it.</b>
             *
             * <p>Two visitors legitimately reach one node — {@code MethodInvocation} claims its own
             * name so a call is coloured even with no analyzer attached, and {@code SimpleName} then
             * asks the analyzer, which knows whether that call is static. Keeping both is not merely
             * redundant, it is <em>fatal</em>: {@code HighlightRegistry.set} rejects two ranges of one
             * name that overlap, so a signature containing any method call threw
             * {@code IllegalArgumentException} out of the popup rather than rendering.</p>
             *
             * <p>Last-wins is the same rule the editor's own merge uses, and for the same reason: the
             * later statement is the one made with more knowledge. The general answer is a floor, not a
             * competitor.</p>
             */
            private void mark(ASTNode at, String name) {
                int start = at.getStartPosition() - from;
                int end = start + at.getLength();
                if (start < 0 || end > slice.length() || end <= start) return;
                for (int i = captures.size() - 1; i >= 0; i--) {
                    Capture existing = captures.get(i);
                    if (existing.start == start && existing.end == end) captures.remove(i);
                }
                captures.add(new Capture(start, end, name));
            }

            /**
             * A literal, split so its ESCAPES get their own capture.
             *
             * <p>One `string` span over the whole thing is what the AST would give, and it is a
             * poorer rendering than the editor three lines above -- `string.escape` is in the
             * vocabulary and every scheme defines it. There is no node for an escape, so the only
             * place this can come from is the characters themselves.</p>
             */
            private void markLiteral(ASTNode at) {
                int start = at.getStartPosition() - from;
                int end = start + at.getLength();
                if (start < 0 || end > slice.length() || end <= start) return;
                int cursor = start;
                while (cursor < end) {
                    int escape = slice.indexOf('\\', cursor);
                    if (escape < 0 || escape >= end - 1) break;
                    int stop = slice.charAt(escape + 1) == 'u'
                            ? Math.min(end, escape + 6) : Math.min(end, escape + 2);
                    if (escape > cursor) captures.add(new Capture(cursor, escape, "string"));
                    captures.add(new Capture(escape, stop, "string.escape"));
                    cursor = stop;
                }
                if (cursor < end) captures.add(new Capture(cursor, end, "string"));
            }

            /**
             * A <b>text block</b>, reached by class name because its type cannot be named here.
             *
             * <p>{@code TextBlock} is a Java 13 AST node and this class is loaded by the band-8
             * child, so a {@code visit(TextBlock)} override would put the type in a method signature
             * — resolved at class load, and {@code NoClassDefFoundError} on the oldest band for a
             * construct that band cannot parse anyway. The alternative was leaving it uncoloured,
             * which is what it was: a whole SQL statement rendering as plain text beside a properly
             * coloured declaration.</p>
             *
             * <p>{@code getNodeType()} would work too and would be a bare {@code 105} here; the
             * class name says what it is.</p>
             */
            @Override
            public boolean preVisit2(ASTNode it) {
                if ("TextBlock".equals(it.getClass().getSimpleName())) {
                    markLiteral(it);
                    return false;
                }
                return true;
            }

            @Override public boolean visit(Modifier it) { mark(it, "keyword"); return false; }
            @Override public boolean visit(PrimitiveType it) { mark(it, "type.builtin"); return false; }
            @Override public boolean visit(MarkerAnnotation it) { mark(it, "attribute"); return false; }

            @Override public boolean visit(NumberLiteral it) { mark(it, "number"); return false; }
            @Override public boolean visit(BooleanLiteral it) { mark(it, "boolean"); return false; }
            @Override public boolean visit(NullLiteral it) { mark(it, "constant.builtin"); return false; }
            @Override public boolean visit(CharacterLiteral it) { markLiteral(it); return false; }
            @Override public boolean visit(StringLiteral it) { markLiteral(it); return false; }

            /**
             * {@code new} has NO NODE of its own -- a ClassInstanceCreation simply begins with it --
             * so the only way to colour it is to claim the three characters the creation starts with.
             * Checked against the text rather than assumed, since a recovered node may start
             * somewhere else entirely.
             */
            @Override
            public boolean visit(ClassInstanceCreation it) {
                int start = it.getStartPosition() - from;
                if (start >= 0 && start + 3 <= slice.length()
                        && slice.startsWith("new", start)) {
                    captures.add(new Capture(start, start + 3, "keyword"));
                }
                return true;
            }

            @Override
            public boolean visit(SimpleType it) {
                // ASKED, NEVER DECIDED HERE. This used to carry its own `isTypeVariable` test -- one
                // third of typeCapture, reimplemented -- so it kept every answer that function grew
                // afterwards out of the quoted path: an interface named in an `implements` clause
                // rendered flat while the same name in the editor behind the popup was cyan.
                //
                // AND THE NAME IS ASKED FIRST, because a type node and its name can legitimately mean
                // different things: `new Circle(1.5d)` resolves its NAME to the constructor and its TYPE
                // to the class. Claiming the span here and returning false is what stopped
                // visit(SimpleName) -- whose whole javadoc is that the two views cannot disagree -- from
                // ever being reached for it, so the popup drew a constructor call in the class colour
                // directly under an editor drawing those same characters as a call.
                //
                // Only for a SIMPLE name. A qualified one (`java.util.List`) has package segments that
                // are their own captures over sub-spans, and marking those under a whole-span `type`
                // would leave two overlapping bands where there is one name -- a separate question from
                // this one, and not this method's to answer.
                String capture = null;
                if (nameCaptures != null && it.getName() instanceof SimpleName) {
                    capture = nameCaptures.apply((SimpleName) it.getName());
                }
                mark(it, capture != null ? capture : typeCapture(it.resolveBinding()));
                return false;
            }

            @Override
            public boolean visit(MethodInvocation it) {
                mark(it.getName(), "function.call");
                // The receiver and the arguments are still worth visiting; only the NAME is claimed
                // here, or a call's whole span would take one colour.
                return true;
            }

            /**
             * <b>The editor's own answer, asked once and used by both.</b>
             *
             * <p>This branch used to decide for itself — a type was {@code type}, a variable went through
             * a local copy of the kind table — and it drifted from the editor twice in a single session.
             * Type parameters were teal in the editor and flat here; annotations were yellow here and
             * plain in the editor. Each time the fix was the same edit in the other file, which is what a
             * rule with two homes looks like.</p>
             *
             * <p>The stylesheet half was already shared: {@code .__syntax__::highlight(...)} styles the
             * editor's lines and this popup's signature from one set of rules. This is the other half —
             * the popup now asks the semantic layer what a name is rather than working it out again, so a
             * name has one answer and the two views cannot disagree about it.</p>
             */
            @Override
            public boolean visit(SimpleName it) {
                String capture = nameCaptures == null ? null : nameCaptures.apply(it);
                if (capture != null) mark(it, capture);
                return false;
            }
        });
        return captures;
    }

    /**
     * A string or char literal, with its <b>escape sequences captured separately</b>.
     *
     * <p>{@code "tab:\t newline:\\n"} drawn in one flat colour is exactly what the editor does not
     * do: {@code string.escape} is in the vocabulary and every scheme defines it, so a literal in the
     * popup was a visibly poorer rendering of the same text three lines above it. Both references
     * colour escapes distinctly, and it is the one part of a string that is not the string.</p>
     */
    private static void appendLiteral(Signature.Builder out, String literal, String capture) {
        int at = 0;
        while (at < literal.length()) {
            int escape = literal.indexOf('\\', at);
            if (escape < 0 || escape + 1 >= literal.length()) break;
            // \\uXXXX is six characters; every other escape is two.
            int end = literal.charAt(escape + 1) == 'u'
                    ? Math.min(literal.length(), escape + 6) : escape + 2;
            out.append(literal.substring(at, escape), capture);
            out.append(literal.substring(escape, end), "string.escape");
            at = end;
        }
        out.append(literal.substring(at), capture);
    }

    private static String truncated(String rendered) {
        String flattened = rendered.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
        return flattened.length() <= MAX_CONSTANT_CHARS ? flattened
                : flattened.substring(0, MAX_CONSTANT_CHARS) + "…";
    }

    /**
     * One annotation member value — and JDT hands back <b>five different things</b> here.
     *
     * <p>{@code getValue()} answers a boxed primitive or a {@code String} for the simple cases, an
     * {@link ITypeBinding} for a {@code Class} literal, an {@link IVariableBinding} for an enum
     * constant, an {@link IAnnotationBinding} for a nested annotation, and an {@code Object[]} for
     * any array — including the single-element array a lone {@code "unused"} becomes.</p>
     *
     * <p>Every one of those except the first prints as a JVM identity string through
     * {@code String.valueOf}, which is how {@code @SuppressWarnings("unused")} rendered as
     * {@code @SuppressWarnings([Ljava.lang.Object;@c3d4bd7)}. Not a formatting slip: it is four
     * distinct shapes silently falling through one branch that only ever handled the fifth.</p>
     */
    private static void appendAnnotationValue(Signature.Builder out, Object value) {
        if (value instanceof Object[]) {
            Object[] elements = (Object[]) value;
            // A SINGLE ELEMENT IS WRITTEN BARE in source -- @SuppressWarnings("unused"), never
            // @SuppressWarnings({"unused"}) -- so printing the braces back shows something nobody wrote.
            if (elements.length == 1) {
                appendAnnotationValue(out, elements[0]);
                return;
            }
            out.append("{", "punctuation.bracket");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                appendAnnotationValue(out, elements[i]);
            }
            out.append("}", "punctuation.bracket");
            return;
        }
        if (value instanceof ITypeBinding) {
            appendTypeName(out, (ITypeBinding) value);
                out.append(".class", "keyword");
            return;
        }
        if (value instanceof IVariableBinding) {
            out.append(((IVariableBinding) value).getName(), "constant");
            return;
        }
        if (value instanceof IAnnotationBinding) {
            appendAnnotations(out, new IAnnotationBinding[] { (IAnnotationBinding) value }, false);
            return;
        }
        out.append(literalOf(value), value instanceof String || value instanceof Character ? "string"
                : value instanceof Boolean ? "boolean" : "number");
    }

    /**
     * A constant rendered as the <b>literal that would produce it</b> — {@code '\t'}, not a tab.
     *
     * <p>{@code TAB = '\t'} folded to the tab character itself and went into the signature raw, where
     * it drew as a missing glyph: the popup said {@code private static final char TAB = □}. A control
     * character is not a rendering problem to work around, it is the wrong text — what a declaration
     * shows is the literal, and the literal has two quotes and a backslash in it.</p>
     *
     * <p>Truncated too, because {@code String} constants in a real file include regexes, Windows
     * paths and <b>text blocks</b>: this fixture's {@code QUERY} is a six-line SQL statement, and its
     * newlines would go straight into a line the popup draws with {@code white-space: nowrap}.</p>
     */
    private static String literalOf(Object constant) {
        if (constant instanceof Character) {
            return "'" + escaped(String.valueOf((char) (Character) constant), '\'') + "'";
        }
        if (constant instanceof String) {
            String value = (String) constant;
            boolean tooLong = value.length() > MAX_CONSTANT_CHARS;
            if (tooLong) value = value.substring(0, MAX_CONSTANT_CHARS);
            return '"' + escaped(value, '"') + (tooLong ? "…\"" : "\"");
        }
        return String.valueOf(constant);
    }

    /** How much of a string constant is worth showing before it stops being a signature. */
    private static final int MAX_CONSTANT_CHARS = 120;

    private static String escaped(String raw, char quote) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\\': out.append("\\\\"); break;
                default:
                    if (c == quote) {
                        out.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7F) {
                        // Anything else unprintable, spelled the way source would spell it rather
                        // than emitted raw to draw as tofu.
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * <b>What colour a type binding is — the one answer, for the editor and the popup both.</b>
     *
     * <p>Not cosmetic: the editor's grammar draws {@code int}, {@code char} and {@code void} as
     * builtins, so a flat {@code type} made the popup disagree with the code two lines behind it --
     * exactly the drift that sharing one capture vocabulary between them was meant to make
     * impossible. {@code EcjSourceAnalyzer} calls this rather than deciding again, for the same
     * reason it hands its {@code captureFor} in rather than having this file reimplement it.</p>
     */
    public static String typeCapture(ITypeBinding type) {
        if (type == null) return "type";
        if (type.isPrimitive()) return "type.builtin";
        return kindOf(type).captureName();
    }

    /**
     * Which <em>kind</em> of type a binding is — the question a grammar cannot answer at all, since
     * {@code class Foo}, {@code interface Foo} and {@code enum Foo} are spelled identically at every use.
     *
     * <p><b>Order is not alphabetical and cannot be.</b> JDT answers {@code isInterface()} true for an
     * annotation type (an {@code @interface} <em>is</em> one) and {@code isClass()} false for an enum, so
     * the specific tests come first or every annotation in the file turns interface-coloured. Annotation
     * types stay {@link SymbolKind#CLASS}: {@code @Nullable} used as metadata is answered positionally by
     * the analyzer, and what reaches here is the declaration, which is a type.</p>
     *
     * <p>A TYPE VARIABLE WHEREVER IT APPEARS, not only at its declaration. {@code <E>} in the header and
     * the {@code E} of a return type are the same thing, and colouring one and not the other is worse
     * than colouring neither -- it reads as the highlighter losing track halfway along the line.</p>
     */
    public static SymbolKind kindOf(ITypeBinding type) {
        if (type.isTypeVariable()) return SymbolKind.TYPE_PARAMETER;
        if (type.isEnum()) return SymbolKind.ENUM;
        if (type.isAnnotation()) return SymbolKind.CLASS;
        if (type.isInterface()) return SymbolKind.INTERFACE;
        if (type.isRecord()) return SymbolKind.RECORD;
        return SymbolKind.CLASS;
    }

    private static String declarationKeyword(ITypeBinding type) {
        if (type.isAnnotation()) return "@interface";
        if (type.isInterface()) return "interface";
        if (type.isEnum()) return "enum";
        if (type.isRecord()) return "record";
        return "class";
    }

    private static String captureForVariable(IVariableBinding variable) {
        if (variable.isEnumConstant()) return "constant";
        if (variable.isParameter()) return "variable.parameter";
        if (variable.isField()) {
            return Modifier.isStatic(variable.getModifiers())
                    && Modifier.isFinal(variable.getModifiers())
                    ? "constant" : "variable.member";
        }
        return "variable";
    }

    /**
     * A type name, <b>built from its parts</b> so the pieces inside it can be coloured separately.
     *
     * <h3>A generic is not one word</h3>
     *
     * <p>Rendering {@code SequencedCollection<E>} as a single string with a single {@code type} capture
     * makes the {@code E} inside it flat, while the {@code E} in the very same declaration's header is
     * teal — one name, two colours, six characters apart. The same goes for {@code Collection<? extends
     * E>} in a constructor's parameter list.</p>
     *
     * <p>So this walks the binding rather than asking it for a name: a type variable is a type variable
     * wherever it appears, a wildcard's bound is rendered on its own terms, and an array's element type
     * keeps whatever it was. The brackets and commas get punctuation captures, which is what the editor
     * gives them.</p>
     */
    private static void appendTypeName(Signature.Builder out, ITypeBinding type) {
        if (type == null) return;

        if (type.isTypeVariable()) {
            out.append(type.getName(), "type.parameter");
            return;
        }
        if (type.isArray()) {
            appendTypeName(out, type.getElementType());
            for (int i = 0; i < type.getDimensions(); i++) out.append("[]", "punctuation.bracket");
            return;
        }
        if (type.isWildcardType()) {
            // A WILDCARD IS PUNCTUATION. `?` names nothing, so the class capture it used to carry put two
            // blue marks in `Function<? super T, ? extends R>` that referred to no class at all -- and it
            // is not a keyword either, which was the second guess: the `super` and `extends` beside it
            // are, and the `?` is the mark that introduces them. Punctuation is what both references
            // draw it as, and it is the one capture whose whole meaning is "structure, not a name".
            out.append("?", "punctuation");
            ITypeBinding bound = type.getBound();
            if (bound != null) {
                out.raw(" ").word(type.isUpperbound() ? "extends" : "super", "keyword");
                appendTypeName(out, bound);
            }
            return;
        }
        if (type.isParameterizedType()) {
            ITypeBinding raw = type.getTypeDeclaration();
            out.append(raw.getName(), typeCapture(raw));
            out.append("<", "punctuation.bracket");
            ITypeBinding[] arguments = type.getTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                if (i > 0) out.append(",", "punctuation.delimiter").raw(" ");
                appendTypeName(out, arguments[i]);
            }
            out.append(">", "punctuation.bracket");
            return;
        }
        out.append(simpleTypeName(type), typeCapture(type));
    }

    /**
     * {@code Map<String, List<Integer>>} rather than {@code java.util.Map<java.lang.String, …>}.
     *
     * <p>The qualified form is what {@code getQualifiedName} answers and it is unreadable in a
     * signature — a two-argument generic becomes eighty characters of package names. Both references
     * show simple names here for the same reason.</p>
     */
    private static String simpleTypeName(ITypeBinding type) {
        if (type == null) return "";
        String name = type.getName();
        return name == null || name.isEmpty() ? type.getQualifiedName() : name;
    }
}
