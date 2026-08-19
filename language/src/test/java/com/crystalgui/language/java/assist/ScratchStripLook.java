package com.crystalgui.language.java.assist;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

/** Scratch: what a real JDK method looks like after the strip. */
public class ScratchStripLook {

    @Test
    public void look() throws Exception {
        String src = "C:/Program Files/Java/jdk-21/lib/src.zip";
        try (ZipFile zip = new ZipFile(src)) {
            show(zip, "java.base/java/util/ArrayList.java", "public boolean add(E e)");
            show(zip, "java.base/java/lang/String.java", "public String substring(int beginIndex)");
        }
    }

    private void show(ZipFile zip, String entry, String needle) throws Exception {
        byte[] raw = zip.getInputStream(zip.getEntry(entry)).readAllBytes();
        String stripped = SourceHeaders.strip(new String(raw, StandardCharsets.UTF_8));
        int at = stripped.indexOf(needle);
        System.out.println("STRIP ===== " + entry + " =====");
        System.out.println(stripped.substring(Math.max(0, at - 700), Math.min(stripped.length(), at + 90)));
        System.out.println("STRIP ===== end =====");
    }
}
