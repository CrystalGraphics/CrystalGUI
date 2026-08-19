package com.crystalgui.language.map.format;

import com.crystalgui.language.map.MappingSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One mapping file format: recognise a file, and parse it into a {@link MappingSet.Builder}.
 *
 * <h3>A platform hands over PATHS, never parsed data</h3>
 *
 * <p>That is the whole point of the seam. A loader knows where its mapping files are and nothing else;
 * the parsing lives here, in the module that owns {@code MappingSet}, so every platform that ships a
 * format the core already knows does <b>zero</b> preprocessing. The alternative — each platform reducing
 * its data to some intermediate shape — puts a hand-written translation on every target, and the one
 * that gets it subtly wrong produces a mapping that is plausible and wrong.</p>
 *
 * <p>The escape hatch stays open: a platform with something genuinely exotic reduces it minimally to a
 * supported form rather than teaching this module a one-off dialect.</p>
 *
 * <h3>It does not generalise, and the design says so up front</h3>
 *
 * <p>1.7.10's MCP data is the easiest version of this problem that exists — flat CSV, globally unique
 * names, no owners. Modern targets are not variations on it: TSRG2 for Forge's SRG data, ProGuard
 * {@code .txt} for Mojmap, Tiny v2 for Fabric. Pretending one parser could stretch across them is how a
 * format SPI turns into a pile of conditionals; one parser per format is the version that does not
 * drift.</p>
 *
 * <h3>{@link #matches} is by CONTENT, not by file name</h3>
 *
 * <p>Names are a platform's choice and collide across formats — several ship a {@code mappings.txt}. A
 * format that identified itself by name would be right until two of them were installed together, and
 * then wrong in a way that reads as corrupt data rather than as the wrong parser.</p>
 */
public interface MappingFormat {

    /** A short name for logs and errors — {@code mcp-csv}, {@code tsrg2}. */
    String id();

    /**
     * Whether this file is one of mine, decided by reading it.
     *
     * <p>Cheap: the first few lines, never the whole file. It is asked once per candidate file per
     * launch, but a format that read 375 KB to answer would make "which parser" cost as much as parsing.</p>
     *
     * <p>Returns false rather than throwing on an unreadable file — an unreadable candidate is simply
     * not mine, and the caller reports one absence instead of each format reporting the same I/O error.</p>
     */
    boolean matches(Path file);

    /**
     * Reads {@code file} into {@code into}.
     *
     * <p>Adds; never clears. A {@link MappingSet} is routinely assembled from several files — MCP alone
     * is three — so a parser that reset the builder would leave whichever ran last as the whole mapping.</p>
     */
    void parse(Path file, MappingSet.Builder into) throws IOException;
}
