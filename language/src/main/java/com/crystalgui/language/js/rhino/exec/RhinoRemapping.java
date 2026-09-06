package com.crystalgui.language.js.rhino.exec;

import com.crystalgui.language.engine.bridge.MemberNameMapper;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolScriptable;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.Wrapper;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a script's readable member names reach the runtime names a class declares.
 *
 * <h3>Not a patched {@code JavaMembers}, and not a {@code NativeJavaObject} subclass either</h3>
 *
 * <p>{@code plan/lang-javascript.md} §11 proposed shading a patched {@code org.mozilla.javascript.JavaMembers} into each
 * band's jar. That would work and it is the wrong trade: {@code JavaMembers} is <b>internal</b>, it differs
 * between the two Rhinos we ship, and a patched copy of an internal class is a fork to re-derive every time a
 * band moves — the plan names KubeJS's Rhino fork as the fallback, which is the same admission further on.</p>
 *
 * <p>The obvious alternative — subclass {@code NativeJavaObject} and override {@code get} — was tried and is
 * <b>not available</b>: its {@code (Scriptable, Object, Class)} constructor exists on band 8 and not on the
 * band we run against, so the subclass compiles and throws {@code NoSuchMethodError} at the first binding.
 * That is the {@code ObjectProperty.getLeft()} divergence for the third time, and it is why this is a
 * <b>membrane</b> instead: a {@link Scriptable} that <em>holds</em> whatever wrapper Rhino made and forwards
 * to it, translating the name on the way through. {@code Scriptable} and {@link Wrapper} are the interfaces
 * the engine is built around; they cannot have changed shape without Rhino ceasing to be Rhino.</p>
 *
 * <h3>Overload resolution is still Rhino's</h3>
 *
 * <p>This translates a <em>name</em> and hands the lookup back, so which overload of
 * {@code func_147439_a} a call selects is decided afterwards from the argument types, exactly as for an
 * unmapped class. That is why the hook takes no descriptor: picking the overload would mean re-implementing
 * the part of the engine that is hardest to get right and least necessary to touch.</p>
 *
 * <h3>The unmapped case costs nothing</h3>
 *
 * <p>An identity mapper leaves Rhino's own factory in place, so a deployment with no mappings runs the code
 * path it ran before this class existed. With a mapping installed, the declared name is still tried first —
 * so every JDK call a script makes takes one map lookup and no translation.</p>
 */
final class RhinoRemapping {

    private RhinoRemapping() {
    }

    /** A wrap factory that maps member names, or Rhino's own when nothing is mapped. */
    static WrapFactory factoryFor(@Nullable MemberNameMapper mapper, WrapFactory fallback) {
        if (mapper == null || mapper == MemberNameMapper.IDENTITY) return fallback;
        return new MappingWrapFactory(mapper, fallback);
    }

    /** @see #factoryFor */
    private static final class MappingWrapFactory extends WrapFactory {

        private final MemberNameMapper mapper;

        MappingWrapFactory(MemberNameMapper mapper, WrapFactory fallback) {
            this.mapper = mapper;
            // JAVA STRINGS STAY JAVASCRIPT STRINGS -- the setting RhinoExecutor relies on, copied from the
            // factory being replaced rather than assumed, so a change there reaches here.
            setJavaPrimitiveWrap(fallback.isJavaPrimitiveWrap());
        }

        /**
         * Every value goes through here, and both hooks are overridden so the question does not arise.
         *
         * <p>Published Rhino ends {@code wrap} with {@code return wrapAsJavaObject(...)}, which would make
         * the second override sufficient on its own — and the feature was nonetheless silently inert with
         * the factory installed and the mapping non-identity, until this one was added. The likeliest
         * explanation is the {@code NativeJavaObject} subclass that was failing at the same time (see the
         * class note), and the honest position is that we do not know which band did what.</p>
         *
         * <p>So this wraps the <em>result</em>: if the band did honour the other hook the value is already
         * ours and comes back untouched, and if it did not, the plain wrapper is replaced. Correct either
         * way, which is the only property worth having across two Rhinos.</p>
         */
        @Override
        public Object wrap(Context cx, Scriptable scope, Object obj, Class<?> staticType) {
            return mapped(super.wrap(cx, scope, obj, staticType), mapper);
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject,
                                           Class<?> staticType) {
            Scriptable wrapped = super.wrapAsJavaObject(cx, scope, javaObject, staticType);
            Object membrane = mapped(wrapped, mapper);
            return membrane instanceof Scriptable ? (Scriptable) membrane : wrapped;
        }

        @Override
        public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> javaClass) {
            Scriptable wrapped = super.wrapJavaClass(cx, scope, javaClass);
            Object membrane = mapped(wrapped, mapper);
            return membrane instanceof Scriptable ? (Scriptable) membrane : wrapped;
        }
    }

    /**
     * A membrane around {@code wrapped}, or {@code wrapped} when there is nothing to map.
     *
     * <p><b>An array is left alone.</b> {@code NativeJavaArray} is a wrapper too, and putting a membrane in
     * front of one would intercept its indexing in order to rename members an array does not have.</p>
     */
    private static Object mapped(Object wrapped, MemberNameMapper mapper) {
        if (wrapped instanceof MappedMembers) return wrapped;
        if (wrapped instanceof NativeJavaArray) return wrapped;
        if (!(wrapped instanceof Scriptable) || !(wrapped instanceof Wrapper)) return wrapped;
        // A CLASS OBJECT IS ALSO A FUNCTION, because `new java.util.ArrayList()` calls it. A membrane that
        // was only a Scriptable made every constructor in every script fail with "is not a function" -- so
        // the callable half is forwarded too, rather than statics being left unmapped to avoid the problem.
        if (wrapped instanceof Function) {
            return new MappedFunction((Function) wrapped, (Wrapper) wrapped, mapper);
        }
        return new MappedMembers((Scriptable) wrapped, (Wrapper) wrapped, mapper);
    }

    /**
     * A Java value whose members answer to their readable names.
     *
     * <p>Composition rather than inheritance, for the constructor reason in the class note — and it costs
     * nothing beyond this boilerplate, because every question is forwarded. {@link Wrapper} is implemented as
     * well as {@link Scriptable}, and that is load-bearing rather than tidy: {@code NativeJavaMethod.call}
     * unwraps its receiver through {@code Wrapper} to find the object to invoke on, so a membrane that was
     * only a {@code Scriptable} would be found by the lookup and then rejected by the call.</p>
     */
    private static class MappedMembers implements Scriptable, Wrapper, SymbolScriptable {

        private final Scriptable delegate;
        private final Wrapper wrapper;
        private final MemberNameMapper mapper;

        MappedMembers(Scriptable delegate, Wrapper wrapper, MemberNameMapper mapper) {
            this.delegate = delegate;
            this.wrapper = wrapper;
            this.mapper = mapper;
        }

        // ── Symbol-keyed properties, forwarded ──────────────────────────────────────────────────
        //
        // NOT OPTIONAL, and a membrane that only forwarded string keys looked complete. `NativeJavaObject`
        // implements SymbolScriptable, and it is how `Symbol.iterator` reaches a Java Iterable -- so
        // `for (var x of javaList)` worked on an unmapped deployment and stopped working on a mapped one,
        // which is a difference the mapping has no business making. There is nothing to translate here: a
        // Symbol is not a name a mapping file can rename.

        @Override
        public Object get(Symbol key, Scriptable start) {
            return delegate instanceof SymbolScriptable
                    ? ((SymbolScriptable) delegate).get(key, delegate) : Scriptable.NOT_FOUND;
        }

        @Override
        public boolean has(Symbol key, Scriptable start) {
            return delegate instanceof SymbolScriptable && ((SymbolScriptable) delegate).has(key, delegate);
        }

        @Override
        public void put(Symbol key, Scriptable start, Object value) {
            if (delegate instanceof SymbolScriptable) {
                ((SymbolScriptable) delegate).put(key, delegate, value);
            }
        }

        @Override
        public void delete(Symbol key) {
            if (delegate instanceof SymbolScriptable) ((SymbolScriptable) delegate).delete(key);
        }

        @Override
        public Object unwrap() {
            return wrapper.unwrap();
        }

        /** The class the mapping is keyed by — a class object maps its own statics, not {@code Class}'s. */
        @Nullable
        private Class<?> owner() {
            Object value = wrapper.unwrap();
            if (value == null) return null;
            return value instanceof Class ? (Class<?>) value : value.getClass();
        }

        @Override
        public Object get(String name, Scriptable start) {
            // THE DECLARED NAME FIRST. A class that genuinely has a member by the readable name -- an
            // unobfuscated build, or a mapping out of date in the harmless direction -- must not be shadowed
            // by a translation, and it is also the fast path for every unmapped lookup.
            Object direct = delegate.get(name, delegate);
            if (direct != Scriptable.NOT_FOUND) return direct;
            String runtime = translate(mapper, owner(), name);
            return runtime == null ? Scriptable.NOT_FOUND : delegate.get(runtime, delegate);
        }

        @Override
        public boolean has(String name, Scriptable start) {
            if (delegate.has(name, delegate)) return true;
            String runtime = translate(mapper, owner(), name);
            return runtime != null && delegate.has(runtime, delegate);
        }

        @Override
        public void put(String name, Scriptable start, Object value) {
            if (delegate.has(name, delegate)) {
                delegate.put(name, delegate, value);
                return;
            }
            String runtime = translate(mapper, owner(), name);
            delegate.put(runtime == null ? name : runtime, delegate, value);
        }

        @Override
        public void delete(String name) {
            delegate.delete(name);
            String runtime = translate(mapper, owner(), name);
            if (runtime != null) delegate.delete(runtime);
        }

        // ── Everything else is forwarded unchanged ──────────────────────────────────────────────

        @Override
        public String getClassName() {
            return delegate.getClassName();
        }

        @Override
        public Object get(int index, Scriptable start) {
            return delegate.get(index, delegate);
        }

        @Override
        public boolean has(int index, Scriptable start) {
            return delegate.has(index, delegate);
        }

        @Override
        public void put(int index, Scriptable start, Object value) {
            delegate.put(index, delegate, value);
        }

        @Override
        public void delete(int index) {
            delegate.delete(index);
        }

        @Override
        public Scriptable getPrototype() {
            return delegate.getPrototype();
        }

        @Override
        public void setPrototype(Scriptable prototype) {
            delegate.setPrototype(prototype);
        }

        @Override
        public Scriptable getParentScope() {
            return delegate.getParentScope();
        }

        @Override
        public void setParentScope(Scriptable parent) {
            delegate.setParentScope(parent);
        }

        /**
         * The ids the delegate has, under the names a reader should see.
         *
         * <p>So {@code for (var k in world)} lists {@code getBlock} rather than {@code func_147439_a} — the
         * same direction the completion list renames in, and for the same reason: a name shown is a name
         * somebody will write.</p>
         */
        @Override
        public Object[] getIds() {
            Object[] ids = delegate.getIds();
            Class<?> owner = owner();
            if (owner == null) return ids;
            Object[] renamed = new Object[ids.length];
            for (int i = 0; i < ids.length; i++) {
                renamed[i] = ids[i] instanceof String ? readable(owner, (String) ids[i]) : ids[i];
            }
            return renamed;
        }

        private String readable(Class<?> owner, String runtimeName) {
            // THE SAME WALK THE OUTWARD DIRECTION USES. It climbed superclasses only while `translate`
            // also climbed interfaces, so a mapping declared on an interface renamed a call and not the
            // list it was offered from -- the two directions disagreeing about one member.
            for (Class<?> at : hierarchyOf(owner)) {
                String internal = internalNameOf(at);
                if (!mapper.mapsAnythingIn(internal)) continue;
                String name = mapper.readableName(internal, runtimeName);
                if (name != null && !name.equals(runtimeName)) return name;
            }
            return runtimeName;
        }

        @Override
        public Object getDefaultValue(Class<?> hint) {
            return delegate.getDefaultValue(hint);
        }

        @Override
        public boolean hasInstance(Scriptable instance) {
            return delegate.hasInstance(instance);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /**
     * A membrane that is callable — a Java class object, which a script constructs and calls statics on.
     *
     * <p>{@code call} and {@code construct} are forwarded to the wrapper Rhino made, so overload resolution
     * and constructor selection remain entirely its own. What is constructed comes back through the factory
     * and is therefore membraned in turn, which is what makes {@code new World().getBlock(…)} work.</p>
     */
    private static final class MappedFunction extends MappedMembers implements Function {

        private final Function delegate;

        MappedFunction(Function delegate, Wrapper wrapper, MemberNameMapper mapper) {
            super(delegate, wrapper, mapper);
            this.delegate = delegate;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            return delegate.call(cx, scope, thisObj, args);
        }

        @Override
        public Scriptable construct(Context cx, Scriptable scope, Object[] args) {
            return delegate.construct(cx, scope, args);
        }
    }

    /**
     * The runtime name for a readable one, or null when there is nothing to try.
     *
     * <p>Null rather than the input, so a caller can tell "no mapping" from "maps to itself" and skip a
     * second lookup that would ask the delegate the identical question it has already refused.</p>
     */
    @Nullable
    private static String translate(MemberNameMapper mapper, @Nullable Class<?> owner, String readable) {
        if (owner == null || readable == null || readable.isEmpty()) return null;
        for (Class<?> at : hierarchyOf(owner)) {
            String internal = internalNameOf(at);
            if (!mapper.mapsAnythingIn(internal)) continue;
            String runtime = mapper.runtimeName(internal, readable);
            if (runtime != null && !runtime.equals(readable)) return runtime;
        }
        return null;
    }

    /**
     * Every type a member could have been <b>declared</b> on, nearest first — and computed once per class.
     *
     * <p>The walk exists because a mapping names the type that declares a member while a script calls it on
     * whatever it happens to be holding: without it, a mapped method declared on a supertype is invisible
     * the moment a script holds a subclass, which on a real deployment is nearly always.
     * {@code MappingSet}'s own javadoc calls resolving the declaring type the difficulty.</p>
     *
     * <p><b>Super-interfaces included</b>, which the first version left out — it climbed superclasses and
     * their <em>direct</em> interfaces only, so a mapping on {@code Collection} was missed for a class
     * implementing {@code List}. And {@code Object} is excluded, because nothing maps its members and it is
     * on the end of every walk.</p>
     *
     * <p>Cached in a {@link ClassValue}: this is asked on every property lookup a script makes, including
     * every call into an unmapped JDK class, and it was rebuilding the internal name of each type in the
     * hierarchy on every miss.</p>
     */
    private static List<Class<?>> hierarchyOf(Class<?> owner) {
        return HIERARCHIES.get(owner);
    }

    private static final ClassValue<List<Class<?>>> HIERARCHIES = new ClassValue<>() {
        @Override
        protected List<Class<?>> computeValue(Class<?> type) {
            List<Class<?>> found = new ArrayList<>();
            collect(type, found);
            return List.copyOf(found);
        }

        private void collect(@Nullable Class<?> type, List<Class<?>> into) {
            if (type == null || type == Object.class || into.contains(type)) return;
            into.add(type);
            collect(type.getSuperclass(), into);
            for (Class<?> face : type.getInterfaces()) collect(face, into);
        }
    };

    /** {@code net/minecraft/world/World} — the form a mapping file is keyed by. Cached; see above. */
    private static String internalNameOf(Class<?> type) {
        return INTERNAL_NAMES.get(type);
    }

    private static final ClassValue<String> INTERNAL_NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    };
}
