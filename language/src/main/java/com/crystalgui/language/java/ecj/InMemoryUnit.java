package com.crystalgui.language.java.ecj;

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;

/**
 * One script's source, handed to the compiler without ever touching a disk.
 *
 * <p>The batch front end takes file paths, so the previous design wrote the source into a temporary
 * directory and read class files back out of another. Both are gone: {@code ICompilationUnit} is what
 * the internal compiler actually consumes, and it is four accessors over a string.</p>
 *
 * <p>The file name is still required and still matters — ECJ enforces javac's rule that a unit's path
 * match the package it declares, and it is what every diagnostic's position is reported against — so it
 * is built from the package the SOURCE declares rather than from the class name, which is a different
 * thing whenever a script is compiled under a generated name.</p>
 */
final class InMemoryUnit implements ICompilationUnit {

    private final char[] contents;
    private final char[] fileName;
    private final char[] mainTypeName;
    private final char[][] packageName;

    InMemoryUnit(String className, String source) {
        this.contents = source.toCharArray();

        // THE PACKAGE IS THE SOURCE'S, EXCEPT WHERE THE CALLER HAS A PATH -- see
        // SourcePackages.effectivePackage, which is the one statement of the rule.
        //
        // The half this comment was originally written for still holds: a SCRIPT is compiled under a
        // generated class name while its own text declares whatever package the author wrote, and taking
        // the package from that name would put a `package foo;` unit at the root and fail the compile with
        // a message about the file's location rather than about the script. Such a name is unqualified, so
        // the declaration still wins.
        //
        // What changed with M15 S4 is that a file under a declared source root arrives QUALIFIED, named
        // from its path -- and there the path is authoritative, because it is what the index named the type
        // by. Handing ECJ that package is what makes a disagreeing `package` line report itself.
        String declared = SourcePackages.effectivePackage(className, source);

        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
        this.mainTypeName = simpleName.toCharArray();

        String path = declared.isEmpty() ? simpleName : declared.replace('.', '/') + "/" + simpleName;
        this.fileName = (path + ".java").toCharArray();

        if (declared.isEmpty()) {
            // Default package. An EMPTY array rather than null: ECJ reads its length before its
            // contents, and null is how a unit ends up looking like it belongs to no compilation at all.
            this.packageName = new char[0][];
        } else {
            String[] segments = declared.split("\\.");
            this.packageName = new char[segments.length][];
            for (int i = 0; i < segments.length; i++) this.packageName[i] = segments[i].toCharArray();
        }
    }

    @Override
    public char[] getContents() {
        return contents;
    }

    @Override
    public char[] getMainTypeName() {
        return mainTypeName;
    }

    @Override
    public char[][] getPackageName() {
        return packageName;
    }

    @Override
    public char[] getFileName() {
        return fileName;
    }

    @Override
    public boolean ignoreOptionalProblems() {
        return false;
    }
}
