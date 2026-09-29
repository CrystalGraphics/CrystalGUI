import java.util.ArrayList;
import java.util.Map;

/**
 * <b>The library viewer.</b> Ctrl+B — or Ctrl+Click — on any name below opens it read-only.
 *
 * <p>Three targets, taking three different routes, so one file says which of them works.</p>
 *
 * <table>
 *   <caption>What to press, and what should happen</caption>
 *   <tr><th>Target</th><th>Route</th><th>What you should see</th></tr>
 *   <tr>
 *     <td>{@link ArrayList}</td>
 *     <td>attached source</td>
 *     <td>{@code ArrayList.java} at the class declaration, with real method bodies</td>
 *   </tr>
 *   <tr>
 *     <td>{@code FlexDirection}</td>
 *     <td>decompiled</td>
 *     <td>a banner on line 1 saying it was reconstructed, then the class</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Map.Entry}</td>
 *     <td>nested type</td>
 *     <td>{@code Map.java}, positioned at {@code Entry} inside it</td>
 *   </tr>
 * </table>
 *
 * <h2>Once a viewer tab is open</h2>
 *
 * <ul>
 *   <li><b>Colours</b> are semantic, not grammar-only — an interface reads as an interface, and a
 *       field is a different colour from a parameter.</li>
 *   <li><b>Hover</b> draws the documentation popup, javadoc and all.</li>
 *   <li><b>Ctrl+B again</b> keeps drilling: from inside {@code ArrayList} into {@code List}, and on.</li>
 *   <li><b>Typing does nothing.</b> The document refuses edits; there is no dirty marker to chase.</li>
 *   <li><b>Problems stays empty</b> for the viewer tab. That is deliberate — a borrowed class's
 *       problems are ours, not the reader's, so they are suppressed rather than filed.</li>
 * </ul>
 *
 * @see ArrayList#add(Object)
 * @see java.nio.charset.Charset
 */
public class Viewer {

    /** A list, so {@code add} below is a MEMBER of a class that has source. */
    private final ArrayList<String> rows = new ArrayList<>();

    /**
     * A library type shipping no sources jar — this is the one that decompiles.
     *
     * <p>Taffy comes in as a plain Gradle artifact with no {@code -sources.jar} beside it, which is
     * exactly the shape a mod on the classpath has.</p>
     */
    private dev.vfyjxf.taffy.style.FlexDirection direction;

    void run() {
        // A MEMBER of a type with source: this should land on `add` inside ArrayList.java, not at its
        // first line, and not on the class declaration.
        rows.add("first");
        rows.isEmpty();

        // A NESTED type. It lives in Map.java -- a source archive is keyed by compilation unit, so
        // there is no Map$Entry.java anywhere to find.
        Map.Entry<String, String> row = null;
        System.out.println(row);

        // A package-private JDK class, reached through a field rather than by name.
        StringBuilder text = new StringBuilder();
        text.append(direction);
    }
}
