package com.crystalgui.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

/**
 * Every way a document is refused, one case each — and each refusal names the document and the node.
 *
 * <p>A silent default is the failure this exists to prevent: a mistyped kind that decodes to nothing, a
 * state key nobody declared that is dropped on the way in, a parameter reference left in a label. All
 * three look like a rendering bug hours later.</p>
 */
public class UiTemplateValidationTest {

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    private static UiTemplate parse(String root) {
        UIElementRegistry.bootstrap();
        return UiTemplates.parse("{\"cgui\": 1, \"root\": " + root + "}", "test:doc");
    }

    private static UiTemplateException refused(String root) {
        try {
            parse(root).inflate();
        } catch (UiTemplateException expected) {
            return expected;
        }
        throw new AssertionError("this document should have been refused: " + root);
    }

    @Test
    public void anUnknownKindNamesTheNearestOne() {
        UiTemplateException refusal = refused("{\"kind\": \"texts\"}");

        assertEquals("test:doc", refusal.document());
        assertEquals("root", refusal.nodePath());
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("did you mean"));
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("text"));
    }

    @Test
    public void aRefusalNamesTheNodePath() {
        UiTemplateException refusal = refused("{\"kind\": \"element\", \"children\": ["
                + "{\"kind\": \"element\"}, {\"kind\": \"nonesuch\"}]}");

        assertEquals("root.children[1]", refusal.nodePath());
    }

    @Test
    public void aStateKeyTheWidgetDoesNotDeclareIsRefused() {
        UiTemplateException refusal = refused(
                "{\"kind\": \"text\", \"state\": {\"txet\": \"typo\"}}");

        assertTrue(refusal.getMessage(), refusal.getMessage().contains("txet"));
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("text"));
    }

    @Test
    public void aStateKeyOnAWidgetThatCarriesNoneIsRefused() {
        UiTemplateException refusal = refused(
                "{\"kind\": \"element\", \"state\": {\"anything\": 1}}");

        assertTrue(refusal.getMessage(), refusal.getMessage().contains("carries none at all"));
    }

    @Test
    public void aNodeWithoutAKindIsRefused() {
        UiTemplateException refusal = refused("{\"id\": \"nameless\"}");

        assertTrue(refusal.getMessage(), refusal.getMessage().contains("names its kind"));
    }

    /** Within a document that declares parameters, a reference to one it has not got is a typo. */
    @Test
    public void anUndeclaredParameterIsRefused() {
        UIElementRegistry.bootstrap();
        UiTemplate template = UiTemplates.parse("{\"cgui\": 1,"
                + "\"params\": {\"title\": {\"type\": \"string\", \"default\": \"x\"}},"
                + "\"root\": {\"kind\": \"text\", \"state\": {\"text\": \"$titel\"}}}", "test:typo");
        try {
            template.inflate();
            assertTrue("a reference to an undeclared parameter must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("$titel"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("title"));
        }
    }

    /** A document that declares none has no parameter syntax, so {@code $anything} is just text. */
    @Test
    public void aDocumentWithNoParametersHasNoParameterSyntax() {
        UiTemplate template = parse("{\"kind\": \"text\", \"state\": {\"text\": \"$gold\"}}");

        assertEquals("$gold", ((UIText) template.inflate()).getText());
    }

    /** A parameter with no default and no value is a hole, not an empty string. */
    @Test
    public void aParameterWithNoDefaultAndNoValueIsRefused() {
        UIElementRegistry.bootstrap();
        UiTemplate template = UiTemplates.parse("{\"cgui\": 1,"
                + "\"params\": {\"title\": {\"type\": \"string\"}},"
                + "\"root\": {\"kind\": \"text\", \"state\": {\"text\": \"$title\"}}}", "test:params");
        try {
            template.inflate();
            assertTrue("a parameter with no answer must be refused", false);
        } catch (UiTemplateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no default"));
        }
    }

    /** And it is filled in when somebody supplies one. */
    @Test
    public void aSuppliedParameterIsUsed() {
        UIElementRegistry.bootstrap();
        UiTemplate template = UiTemplates.parse("{\"cgui\": 1,"
                + "\"params\": {\"title\": {\"type\": \"string\"}},"
                + "\"root\": {\"kind\": \"text\", \"id\": \"t\", \"state\": {\"text\": \"$title\"}}}",
                "test:params");

        UIElement tree = template.inflate(Map.of("title", "Given"), Map.of());

        assertEquals("Given", ((UIText) tree).getText());
    }

    /** Money is not a parameter: only a reference the pattern recognises is substituted. */
    @Test
    public void aDollarThatIsNotAReferenceIsLeftAlone() {
        UIElementRegistry.bootstrap();
        UiTemplate template = UiTemplates.parse("{\"cgui\": 1,"
                + "\"params\": {\"title\": {\"type\": \"string\", \"default\": \"x\"}},"
                + "\"root\": {\"kind\": \"text\", \"state\": {\"text\": \"$5.00\"}}}", "test:money");

        assertEquals("$5.00", ((UIText) template.inflate()).getText());
    }
}
