import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Does an engine band actually WORK on the JVM it is pinned for?
 *
 * <h3>Why this is a standalone Java 8 program and not a JUnit test</h3>
 *
 * <p>The question is about a <em>JVM</em>, and a test in {@code language/} cannot ask it: that module
 * compiles to Java 21 bytecode, so on a Java 8 host the test class would not load and the engine's
 * behaviour would never be reached. Everything here is therefore Java 8 source with no dependency on
 * anything in this repository — the whole point is that the only Java 21 thing in the room is Gradle,
 * which is not in the room at all when this runs.</p>
 *
 * <h3>What it proves that the other checks cannot</h3>
 *
 * <p>{@code checkEngineBands} reads class-file majors, which proves the jars are <em>loadable</em>.
 * {@code EngineApiSurfaceTest} reflects over them, which proves the API is <em>present</em>. Neither
 * runs a compiler. §6.4 rejected the downgrade-the-newest-jar alternative precisely because "ECJ's
 * runtime behaviour includes reading {@code ct.sym}/jrt images and JPMS metadata, which API stubbing
 * cannot fake" — and the same sentence is the reason a loadability check is not enough for the real
 * jars either. This resolves a binding against the running VM's own class library, which is the thing
 * that differs most between a Java 8 host and a Java 17 one.</p>
 *
 * <p>Usage: {@code BandSmoke <band> <classpath>} — exits non-zero, loudly, on any failure.</p>
 */
public final class BandSmoke {

    private static final List<String> FAILURES = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: BandSmoke <band> <path-separated jars>");
            System.exit(2);
        }
        String band = args[0];
        URLClassLoader engines = loaderOver(args[1]);

        System.out.println("== band " + band + " on Java "
                + System.getProperty("java.specification.version")
                + " (" + System.getProperty("java.vendor") + ") ==");

        try {
            rhinoEvaluates(engines);
            rhinoClassShutterRefuses(engines);
            jdtResolvesABindingAgainstTheRunningVm(engines);
            jdtReportsTheMEMBERSOfATypeFromTheClassLibrary(engines);
            jdtRecoversBindingsFromBrokenSource(engines);
            jdtCompilesAndTheScriptActuallyRuns(engines);
        } finally {
            engines.close();
        }

        if (FAILURES.isEmpty()) {
            System.out.println("   band " + band + " OK");
        } else {
            for (String failure : FAILURES) System.err.println("   FAIL " + failure);
            System.exit(1);
        }
    }

    private static URLClassLoader loaderOver(String pathList) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (String entry : pathList.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.trim().length() == 0) continue;
            File jar = new File(entry.trim());
            if (jar.isFile()) urls.add(jar.toURI().toURL());
        }
        if (urls.isEmpty()) throw new IllegalStateException("no jars on " + pathList);
        // Plain parent-last is enough here: this program has nothing of its own to share, so the
        // bridge-package carve-out EngineClassLoader needs has nothing to protect.
        return new URLClassLoader(urls.toArray(new URL[0]), BandSmoke.class.getClassLoader());
    }

    private static void check(String what, boolean ok, Object detail) {
        if (ok) {
            System.out.println("   ok   " + what + (detail == null ? "" : "  -> " + detail));
        } else {
            FAILURES.add(what + (detail == null ? "" : "  -> " + detail));
        }
    }

    // ── Rhino ────────────────────────────────────────────────────────────────────────────────────

    private static void rhinoEvaluates(ClassLoader engines) throws Exception {
        Class<?> contextClass = Class.forName("org.mozilla.javascript.Context", true, engines);
        Object context = contextClass.getMethod("enter").invoke(null);
        try {
            contextClass.getMethod("setOptimizationLevel", int.class).invoke(context, -1);
            Object scope = contextClass.getMethod("initStandardObjects").invoke(context);
            Class<?> scriptable = Class.forName("org.mozilla.javascript.Scriptable", true, engines);
            Method evaluate = contextClass.getMethod("evaluateString",
                    scriptable, String.class, String.class, int.class, Object.class);

            // NUMERICALLY, not as a string. JavaScript has one number type and Rhino hands back a
            // Double, so `String.valueOf` gives "2.0" and a string comparison fails against a
            // perfectly correct answer.
            Object sum = evaluate.invoke(context, scope, "1 + 1", "smoke", 1, null);
            check("rhino evaluates arithmetic",
                    sum instanceof Number && ((Number) sum).intValue() == 2, sum);

            // ES2015 syntax the plan promises on every band -- let/const, arrows, template literals.
            // NOT `class`, which is the permanent gap (§2 row 2) and would fail here by design.
            Object modern = evaluate.invoke(context, scope,
                    "const xs = [1,2,3]; xs.map(x => x * 2).join('-')", "smoke", 1, null);
            check("rhino accepts ES2015 (const, arrow, map)", "2-4-6".equals(String.valueOf(modern)), modern);
        } finally {
            contextClass.getMethod("exit").invoke(null);
        }
    }

    /**
     * The sandbox's one genuinely enforcing mechanism (§19.2), exercised rather than assumed.
     *
     * <p>Rhino is the only engine here where a refusal is call-time. Java's is advisory — a compiled
     * script that got a reference some other way can still use it — so the JS side is where a shutter
     * has to demonstrably work rather than merely exist.</p>
     */
    private static void rhinoClassShutterRefuses(ClassLoader engines) throws Exception {
        Class<?> contextClass = Class.forName("org.mozilla.javascript.Context", true, engines);
        Class<?> shutterClass = Class.forName("org.mozilla.javascript.ClassShutter", true, engines);
        Object shutter = java.lang.reflect.Proxy.newProxyInstance(engines,
                new Class<?>[]{shutterClass}, new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] arguments) {
                        if (!"visibleToScripts".equals(method.getName())) return null;
                        String className = String.valueOf(arguments[0]);
                        return Boolean.valueOf(!className.startsWith("java.lang.System"));
                    }
                });

        Object context = contextClass.getMethod("enter").invoke(null);
        try {
            contextClass.getMethod("setOptimizationLevel", int.class).invoke(context, -1);
            contextClass.getMethod("setClassShutter", shutterClass).invoke(context, shutter);
            Object scope = contextClass.getMethod("initStandardObjects").invoke(context);
            Class<?> scriptable = Class.forName("org.mozilla.javascript.Scriptable", true, engines);
            Method evaluate = contextClass.getMethod("evaluateString",
                    scriptable, String.class, String.class, int.class, Object.class);
            boolean refused = false;
            try {
                evaluate.invoke(context, scope,
                        "java.lang.System.getProperty('user.home')", "smoke", 1, null);
            } catch (java.lang.reflect.InvocationTargetException thrown) {
                refused = true;
            }
            check("rhino ClassShutter refuses a blocked class", refused, null);
        } finally {
            contextClass.getMethod("exit").invoke(null);
        }
    }

    // ── JDT ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * A binding resolved against <b>this VM's own class library</b>.
     *
     * <p>{@code includeRunningVMBootclasspath = true} makes JDT read {@code rt.jar} on Java 8 and the
     * jrt image on 9+. Those are different mechanisms, and which one works is a property of the JVM
     * rather than of the jar — which is the whole reason this program runs under two launchers.</p>
     */
    private static void jdtResolvesABindingAgainstTheRunningVm(ClassLoader engines) throws Exception {
        Object unit = parse(engines,
                "public class Smoke {\n"
                        + "    java.util.List<String> names = new java.util.ArrayList<String>();\n"
                        + "    int size() { return names.size(); }\n"
                        + "}\n");

        Object[] problems = (Object[]) unit.getClass().getMethod("getProblems").invoke(unit);
        check("jdt parses well-formed source with no problems", problems.length == 0,
                problems.length == 0 ? null : String.valueOf(problems[0]));

        // The GENERIC name, not the erasure -- if this comes back as bare java.util.List the bindings
        // resolved against something, but not against a real class library.
        String qualified = qualifiedNameOf(engines, typeOfFirstField(engines, unit));
        check("jdt resolves a generic type against the running VM",
                "java.util.List<java.lang.String>".equals(qualified), qualified);
    }

    /**
     * <b>A resolved type is asked for its MEMBERS, not merely for its name.</b>
     *
     * <h3>The check above passes while the editor is empty</h3>
     *
     * <p>Resolution and member enumeration are separate operations and they fail separately. A defect
     * shipped that left the first perfect and the second silent: {@code getDeclaredMethods()} threw
     * internally, JDT's DOM caught it — the method wraps its work in {@code catch (RuntimeException)} —
     * logged a line with no stack, and returned an <b>empty array</b>. Every completion popup in the game
     * was empty while every script compiled and ran, and the check above went on reporting
     * {@code java.util.List<java.lang.String>} throughout.</p>
     *
     * <h3>Why it has to be a CLASS out of an ARCHIVE, on this JVM in particular</h3>
     *
     * <p>The cause was a name environment whose classpath was closed before the bindings were read.
     * {@code FileSystem.cleanup()} closes each {@code ClasspathJar} and nulls its handle; the next
     * {@code getModulesDeclaringPackage} rebuilds its package cache from that null and throws. So it
     * only bites where the class library is an <b>archive</b> — which is Java 8 and {@code rt.jar}, and
     * from 9 onward is a jrt image that survives the same call untouched. <b>This program is the only
     * place in the build that runs on such a JVM.</b></p>
     *
     * <p>An INTERFACE would not catch it either: JDT synthesises interface members rather than reading
     * them off the binding, so {@code Comparable} answered correctly while {@code String} answered with
     * nothing. Hence {@code ArrayList}, and hence a member only {@code ArrayList} declares — an
     * inherited one would come from a supertype that had already resolved.</p>
     */
    private static void jdtReportsTheMEMBERSOfATypeFromTheClassLibrary(ClassLoader engines)
            throws Exception {
        Object unit = parse(engines,
                "public class Smoke {\n"
                        + "    java.util.ArrayList<String> made = new java.util.ArrayList<String>();\n"
                        + "}\n");

        Object type = typeOfFirstField(engines, unit);
        if (type == null) {
            check("jdt reports the members of a class-library type", false, "no binding for the field");
            return;
        }

        Class<?> typeBinding = Class.forName("org.eclipse.jdt.core.dom.ITypeBinding", true, engines);
        Class<?> methodBinding = Class.forName("org.eclipse.jdt.core.dom.IMethodBinding", true, engines);
        Object[] declared = (Object[]) typeBinding.getMethod("getDeclaredMethods").invoke(type);

        check("jdt reports the members of a class-library type at all", declared.length > 0,
                Integer.valueOf(declared.length));

        boolean own = false;
        for (Object method : declared) {
            // ArrayList's own, so an answer assembled purely from supertypes fails rather than passes.
            if ("ensureCapacity".equals(methodBinding.getMethod("getName").invoke(method))) own = true;
        }
        check("jdt reports a member the type itself declares", own,
                own ? null : "only inherited members came back from " + declared.length);
    }

    /**
     * §15.1's broken-code story, on real jars: a script under the caret is nearly always incomplete.
     *
     * <p>This is §23 row 4 for band 8 in particular — the plan flagged old-band binding recovery as
     * unverified, and "the compiler answers only for well-formed input" would mean it answers exactly
     * when it is not needed.</p>
     */
    private static void jdtRecoversBindingsFromBrokenSource(ClassLoader engines) throws Exception {
        Object unit = parse(engines,
                "public class Smoke {\n"
                        + "    java.util.List<String> names = new java.util.ArrayList<String>();\n"
                        + "    int size() { return names. \n"
                        + "}\n");

        Object[] problems = (Object[]) unit.getClass().getMethod("getProblems").invoke(unit);
        check("jdt reports the syntax error rather than throwing", problems.length > 0, problems.length);

        String qualified = qualifiedNameOf(engines, typeOfFirstField(engines, unit));
        check("jdt still resolves bindings in BROKEN source (setBindingsRecovery)",
                "java.util.List<java.lang.String>".equals(qualified), qualified);
    }

    /**
     * <b>A script compiles to bytecode and runs</b> — on this band's own JVM.
     *
     * <p>Everything above this proves the engine <em>analyses</em>. This is the one that proves it
     * produces something the host can execute, which is the actual product. It matters most here
     * rather than in a JUnit test on a modern JVM: the class files are targeted at this JVM's version,
     * loaded by this JVM's loader, and running them is the only way to find out that the target level
     * was spelled correctly. A wrong one fails as {@code UnsupportedClassVersionError}, which names a
     * number and says nothing about the script.</p>
     *
     * <p>Uses {@code BatchCompiler} — public API present in all three bands — for the same reason the
     * adapter does: {@code EclipseCompiler}, ECJ's {@code javax.tools.JavaCompiler}, is <b>absent from
     * band 8</b>, so the standard-API route would compile, pass on a modern JVM, and fail exactly here.</p>
     */
    private static void jdtCompilesAndTheScriptActuallyRuns(ClassLoader engines) throws Exception {
        java.io.File work = java.io.File.createTempFile("cgui-band-smoke", "");
        if (!work.delete() || !work.mkdirs()) throw new IllegalStateException("no temp dir");
        java.io.File sources = new java.io.File(work, "src");
        java.io.File output = new java.io.File(work, "out");
        sources.mkdirs();
        output.mkdirs();

        java.io.File source = new java.io.File(sources, "Smoke.java");
        String text = "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "public class Smoke {\n"
                + "    public static String run() {\n"
                + "        List<String> parts = new ArrayList<String>();\n"
                + "        for (int i = 1; i <= 3; i++) parts.add(String.valueOf(i * i));\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        for (String part : parts) out.append(part).append('-');\n"
                + "        return out.toString();\n"
                + "    }\n"
                + "}\n";
        java.io.OutputStream stream = new java.io.FileOutputStream(source);
        try {
            stream.write(text.getBytes("UTF-8"));
        } finally {
            stream.close();
        }

        // The level this JVM can LOAD, which is the binding constraint -- not the level the compiler
        // could reach.
        String spec = System.getProperty("java.specification.version");
        int feature = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2))
                : Integer.parseInt(spec.split("[^0-9]")[0]);
        String level = feature <= 8 ? "1." + feature : String.valueOf(feature);

        Class<?> batch = Class.forName("org.eclipse.jdt.core.compiler.batch.BatchCompiler", true, engines);
        Class<?> progress = Class.forName("org.eclipse.jdt.core.compiler.CompilationProgress", true, engines);
        Method compile = batch.getMethod("compile", String.class,
                java.io.PrintWriter.class, java.io.PrintWriter.class, progress);

        java.io.StringWriter out = new java.io.StringWriter();
        java.io.StringWriter err = new java.io.StringWriter();
        String command = "-source " + level + " -target " + level + " -proc:none -nowarn"
                + " -d \"" + output.getAbsolutePath() + "\" \"" + source.getAbsolutePath() + "\"";
        Object ok = compile.invoke(null, command,
                new java.io.PrintWriter(out), new java.io.PrintWriter(err), null);
        // stderr is shown ONLY on failure. With no -classpath given, ECJ inherits java.class.path and
        // warns about entries that do not exist -- true, harmless, and nothing to do with the script.
        // Printing it on success makes a passing check look like a failing one.
        check("ecj compiles a script to bytecode", Boolean.TRUE.equals(ok),
                Boolean.TRUE.equals(ok) ? null : err.toString().trim());

        java.io.File produced = new java.io.File(output, "Smoke.class");
        check("the class file exists", produced.isFile(), produced.length() + " bytes");

        URLClassLoader scripts = new URLClassLoader(
                new URL[]{output.toURI().toURL()}, BandSmoke.class.getClassLoader());
        try {
            Object answer = Class.forName("Smoke", true, scripts).getMethod("run").invoke(null);
            check("THE SCRIPT RUNS on this JVM", "1-4-9-".equals(String.valueOf(answer)), answer);
        } finally {
            scripts.close();
        }
    }

    /**
     * Calls {@code getQualifiedName} through the <b>interface</b>, which is the only way it works.
     *
     * <p>{@code binding.getClass()} is {@code org.eclipse.jdt.core.dom.TypeBinding} — package-private,
     * so a {@code Method} looked up on it throws {@code IllegalAccessException} on invoke even though
     * the method itself is public. The method has to be looked up on {@code ITypeBinding}, which is the
     * public type. Reflection over an API that returns interfaces hits this every time.</p>
     */
    private static String qualifiedNameOf(ClassLoader engines, Object typeBinding) throws Exception {
        if (typeBinding == null) return null;
        Class<?> iface = Class.forName("org.eclipse.jdt.core.dom.ITypeBinding", true, engines);
        return (String) iface.getMethod("getQualifiedName").invoke(typeBinding);
    }

    private static Object parse(ClassLoader engines, String source) throws Exception {
        Class<?> astClass = Class.forName("org.eclipse.jdt.core.dom.AST", true, engines);
        Class<?> parserClass = Class.forName("org.eclipse.jdt.core.dom.ASTParser", true, engines);

        int level = highestJlsLevel(astClass);
        Object parser = parserClass.getMethod("newParser", int.class).invoke(null, Integer.valueOf(level));

        // Source and target must be spelled for the level, or JDT parses modern syntax as errors while
        // reporting nothing about why.
        String levelName = level >= 9 ? String.valueOf(level) : "1." + level;
        Map<String, String> options = new HashMap<String, String>();
        options.put("org.eclipse.jdt.core.compiler.source", levelName);
        options.put("org.eclipse.jdt.core.compiler.compliance", levelName);
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", levelName);
        parserClass.getMethod("setCompilerOptions", Map.class).invoke(parser, options);

        parserClass.getMethod("setSource", char[].class).invoke(parser, source.toCharArray());
        parserClass.getMethod("setUnitName", String.class).invoke(parser, "Smoke.java");
        parserClass.getMethod("setResolveBindings", boolean.class).invoke(parser, Boolean.TRUE);
        parserClass.getMethod("setBindingsRecovery", boolean.class).invoke(parser, Boolean.TRUE);
        parserClass.getMethod("setStatementsRecovery", boolean.class).invoke(parser, Boolean.TRUE);
        parserClass.getMethod("setEnvironment",
                        String[].class, String[].class, String[].class, boolean.class)
                .invoke(parser, new String[0], new String[0], new String[0], Boolean.TRUE);

        Class<?> monitor = Class.forName("org.eclipse.core.runtime.IProgressMonitor", true, engines);
        return parserClass.getMethod("createAST", monitor).invoke(parser, new Object[]{null});
    }

    /** The same discovery {@code JlsLevel} does, repeated here because this program shares no code. */
    private static int highestJlsLevel(Class<?> astClass) throws Exception {
        int highest = 0;
        java.lang.reflect.Field[] fields = astClass.getFields();
        for (int i = 0; i < fields.length; i++) {
            java.lang.reflect.Field field = fields[i];
            String name = field.getName();
            if (!name.startsWith("JLS") || field.getType() != int.class) continue;
            if (field.isAnnotationPresent(Deprecated.class)) continue;
            String suffix = name.substring(3);
            boolean digits = suffix.length() > 0;
            for (int c = 0; c < suffix.length(); c++) {
                if (!Character.isDigit(suffix.charAt(c))) digits = false;
            }
            if (!digits) continue;
            int value = field.getInt(null);
            if (value > highest) highest = value;
        }
        if (highest == 0) throw new IllegalStateException("no AST.JLS* constant");
        return highest;
    }

    /** The declared type of {@code Smoke}'s first field, resolved. */
    private static Object typeOfFirstField(ClassLoader engines, Object unit) throws Exception {
        List<?> types = (List<?>) unit.getClass().getMethod("types").invoke(unit);
        if (types.isEmpty()) return null;
        Object declaration = types.get(0);
        // Same interface rule as qualifiedNameOf -- AbstractTypeDeclaration is public but the concrete
        // TypeDeclaration lookup is safe here because it IS public; the fragments below are not.
        Object[] bodyDeclarations = (Object[]) declaration.getClass()
                .getMethod("getFields").invoke(declaration);
        if (bodyDeclarations.length == 0) return null;
        Object field = bodyDeclarations[0];
        List<?> fragments = (List<?>) field.getClass().getMethod("fragments").invoke(field);
        if (fragments.isEmpty()) return null;
        Object fragment = fragments.get(0);
        Object binding = fragment.getClass().getMethod("resolveBinding").invoke(fragment);
        if (binding == null) return null;
        Class<?> variableBinding = Class.forName("org.eclipse.jdt.core.dom.IVariableBinding", true, engines);
        return variableBinding.getMethod("getType").invoke(binding);
    }

    private BandSmoke() {
    }
}
