package com.crystalgui.language.java.cfr;

import com.crystalgui.language.engine.bridge.Decompiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CFR, driven — the band-side half of {@link Decompiler}.
 *
 * <h3>Child-side, and mechanically so</h3>
 *
 * <p>It imports {@code org.benf.cfr}, which is a band jar, so {@code EngineClassLoader} defines it and
 * the child-side rules apply: JDK types, the bridge package, and {@code com.crystalgui.text.*} only.
 * {@code BandLoadedCodeAvoidsWorkspaceTypesTest} counts {@code org/benf/cfr/} among the markers that
 * make a class child-side, so this file is inside that scan rather than beside it.</p>
 *
 * <h3>The one jar that needs no per-band pin</h3>
 *
 * <p>CFR is written in Java 6, so a single artifact loads on every band — where ECJ and Rhino each need
 * a version chosen against the band's class-file ceiling. There is no "compile against the oldest band's
 * API" care to take here, because there is only one API.</p>
 *
 * <h3>Bytes come from the caller, never from a path</h3>
 *
 * <p>{@link ClassFileSource} is CFR's seam for "where do I find {@code java/util/List}", and it is asked
 * for far more than the class requested: supertypes, so an override renders as one, and nested classes,
 * so they land inside their outer. Answering it from {@link Decompiler.Bytes} is what lets the view show
 * the class <b>as the running game has it</b> — post-transformer, post-mixin, already remapped — which
 * a decompiler pointed at a jar cannot do.</p>
 */
public final class CfrDecompiler implements Decompiler {

    /** Public no-argument, because {@code EngineHost.adapter} instantiates this reflectively. */
    public CfrDecompiler() {
    }

    /**
     * CFR's options, chosen for a READER rather than for a recompile.
     *
     * <p>{@code hidebridgemethods} and {@code hideutf} remove artefacts of compilation that no author
     * wrote. {@code comments} off suppresses CFR's own commentary about what it could not do — a viewer
     * showing "// This method has failed to decompile" is honest and belongs in a banner rather than
     * interleaved with the code. {@code decodestringswitch} and friends are left at their defaults,
     * which are already the readable ones.</p>
     */
    private static final Map<String, String> OPTIONS = options();

    private static Map<String, String> options() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("hidebridgemethods", "true");
        options.put("hideutf", "true");
        options.put("comments", "false");
        return options;
    }

    @Override
    public String decompile(String binaryName, Bytes bytes) {
        if (binaryName == null || binaryName.isEmpty() || bytes == null) return null;
        String internal = binaryName.replace('.', '/');
        // A ONE-ELEMENT HOLDER rather than a field: this class is an adapter held for the process's life
        // and two viewers may decompile at once, so anything stateful here would braid their output.
        String[] java = new String[1];
        try {
            CfrDriver driver = new CfrDriver.Builder()
                    .withClassFileSource(new BytesSource(bytes))
                    .withOutputSink(new JavaOnlySink(java))
                    .withOptions(OPTIONS)
                    .build();
            driver.analyse(Collections.singletonList(internal));
        } catch (RuntimeException | LinkageError refused) {
            // NULL FOR ANYTHING AT ALL, which the interface promises and this is the reason for: a 2021
            // decompiler will meet bytecode it does not understand, and a viewer has to say so and keep
            // working rather than take a hover down with it. LinkageError as well as RuntimeException,
            // because the failure mode of a band jar meeting an unexpected class file is often the
            // former.
            return null;
        }
        return java[0] == null || java[0].isEmpty() ? null : java[0];
    }

    /** Answers CFR's every "where is this class" from the host's byte source. */
    private static final class BytesSource implements ClassFileSource {

        private final Bytes bytes;

        BytesSource(Bytes bytes) {
            this.bytes = bytes;
        }

        @Override
        public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
            // Nothing to inform: there are no paths here, only names.
        }

        @Override
        public Collection<String> addJar(String jarPath) {
            // NEVER ASKED, because `analyse` is handed a class name rather than a jar. Empty rather than
            // throwing, so a future CFR that probes for one degrades instead of failing.
            return Collections.emptyList();
        }

        @Override
        public String getPossiblyRenamedPath(String path) {
            return path;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String path) throws IOException {
            // CFR ASKS WITH THE `.class` SUFFIX and the host speaks in internal names, so it is stripped
            // here rather than at every call site of `Bytes`. Getting this wrong asks the runtime for
            // `java/util/List.class` as a type name, which nothing has, and every decompile answers
            // empty -- with no error, because "no bytes" is a legal answer.
            String name = path.endsWith(".class") ? path.substring(0, path.length() - ".class".length())
                    : path;
            byte[] found = bytes.read(name);
            if (found == null) throw new IOException("no bytes for " + name);
            return Pair.make(found, path);
        }
    }

    /**
     * Keeps the decompiled Java and discards everything else CFR offers.
     *
     * <p>The other sinks are progress, a summary and CFR's own exception messages. None belongs in a
     * document: the first two are noise and the third is a banner's job, said once about the whole file
     * rather than woven into it.</p>
     */
    private static final class JavaOnlySink implements OutputSinkFactory {

        private final String[] out;

        JavaOnlySink(String[] out) {
            this.out = out;
        }

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            // THE STRUCTURED FORM WHERE IT IS OFFERED. A `Decompiled` carries the class name beside the
            // text, so the sink can tell the class it asked about from a nested one CFR emitted on the
            // way -- which a bare string cannot, and which decides what a viewer shows.
            if (sinkType == SinkType.JAVA && available.contains(SinkClass.DECOMPILED)) {
                return Collections.singletonList(SinkClass.DECOMPILED);
            }
            return Collections.singletonList(SinkClass.STRING);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkType != SinkType.JAVA) return ignored -> { };
            if (sinkClass == SinkClass.DECOMPILED) {
                return value -> accept(((SinkReturns.Decompiled) value).getJava());
            }
            return value -> accept(String.valueOf(value));
        }

        /**
         * Keeps the FIRST answer.
         *
         * <p>CFR emits one document per top-level class, and asking for a nested type produces its outer
         * — which is the right answer and is the only one. Keeping the last instead would be the same
         * thing today and would silently become "whichever CFR happened to finish last" the day it emits
         * more than one.</p>
         */
        private void accept(String java) {
            if (out[0] == null && java != null && !java.isEmpty()) out[0] = java;
        }
    }
}
