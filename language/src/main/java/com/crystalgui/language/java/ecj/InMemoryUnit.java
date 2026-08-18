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

        // THE PACKAGE COMES FROM THE SOURCE, NOT FROM THE CLASS NAME, and they routinely differ: a
        // script is compiled under a generated class name while its own text declares whatever package
        // the author wrote. ECJ enforces javac's rule that a unit's path match its declared package, so
        // taking the package from the class name puts a `package foo;` unit at the root and the compile
        // fails with a message about the file's location rather than about the script.
        //
        // SourcePackages already existed for exactly this and the batch path used it to choose a
        // directory; the only thing that has changed is that there is no directory any more.
        String declared = SourcePackages.declaredPackage(source);

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
