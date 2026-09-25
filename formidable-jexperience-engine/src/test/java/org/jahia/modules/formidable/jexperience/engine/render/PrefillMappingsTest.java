package org.jahia.modules.formidable.jexperience.engine.render;

import org.jahia.modules.formidable.jexperience.engine.model.JxpMixin;
import org.jahia.modules.formidable.jexperience.engine.model.JxpProperty;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which fields the block tells the page to prefill, what the block depends on, and how the pairs are
 * written. Reading is a query of the mappable fields under the form, replaced here by a fixed list; the
 * rule of inclusion is the point: mapped, its prefill switched on, not sensitive — and every mappable
 * field, included or not, is a dependency.
 */
class PrefillMappingsTest {

    private static JCRNodeWrapper field(String name, String property, boolean mapped, boolean prefill, boolean sensitive) throws RepositoryException {
        return field(name, property, mapped, prefill, sensitive, null);
    }

    /** {@code then}: the author's choice of what follows the write, as stored — null for a field saved before the option existed. */
    private static JCRNodeWrapper field(String name, String property, boolean mapped, boolean prefill, boolean sensitive, String then) throws RepositoryException {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.hasProperty(JxpProperty.PREFILL_THEN)).thenReturn(then != null);
        when(node.getPropertyAsString(JxpProperty.PREFILL_THEN)).thenReturn(then);
        when(node.getName()).thenReturn(name);
        when(node.getPath()).thenReturn("/sites/mysite/contents/contact/fields/" + name);
        when(node.isNodeType(JxpMixin.MAPPING)).thenReturn(mapped);
        flag(node, JxpProperty.PREFILL, prefill);
        when(node.getPropertyAsString(JxpProperty.PROFILE_PROPERTY)).thenReturn(mapped ? property : null);
        flag(node, JxpProperty.SENSITIVE, sensitive);
        return node;
    }

    private static void flag(JCRNodeWrapper node, String name, boolean value) throws RepositoryException {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getBoolean()).thenReturn(value);
        when(node.hasProperty(name)).thenReturn(true);
        when(node.getProperty(name)).thenReturn(property);
    }

    private static PrefillMappings over(List<JCRNodeWrapper> fields) {
        Iterator<JCRNodeWrapper> iterator = fields.iterator();
        NodeIterator nodes = mock(NodeIterator.class);
        when(nodes.hasNext()).thenAnswer(call -> iterator.hasNext());
        when(nodes.nextNode()).thenAnswer(call -> iterator.next());
        return new PrefillMappings() {
            @Override
            NodeIterator mappableFields(JCRSessionWrapper session, JCRNodeWrapper form) {
                return nodes;
            }
        };
    }

    @Test
    void onlyAMappedFieldWithThePrefillOnAndNotSensitiveIsListed() throws Exception {
        PrefillMappings.Prefill prefill = over(List.of(
                field("firstName", "firstName", true, true, false),
                field("email", "email", true, true, false),
                field("phoneNumber", "phoneNumber", true, false, false), // mapped, prefill off
                field("secret", "nationality", true, true, true),         // prefill on, but sensitive
                field("switchedOn", "", true, true, false),              // mapping switched on, property left empty
                field("message", null, false, false, false)              // mappable, never mapped
        )).read(mock(JCRSessionWrapper.class), mock(JCRNodeWrapper.class));

        assertEquals(Map.of(
                "firstName", new PrefillMappings.Entry("firstName", null),
                "email", new PrefillMappings.Entry("email", null)), prefill.entries());
        // in the form's order, which is the query's
        assertEquals(List.of("firstName", "email"), List.copyOf(prefill.entries().keySet()));
    }

    @Test
    void theReasonAFieldIsLeftOutIsNamed() throws Exception {
        // Verifies the one trace an author's dropped prefill switch leaves: the debug line names which of
        // the four conditions failed, in the order the author meets them — nothing in the editor can say it,
        // since a switch cannot warn that the mapping above it names no property.
        assertEquals("the prefill is not switched on", PrefillMappings.leftOut(field("phoneNumber", "phoneNumber", true, false, false)));
        assertEquals("the field is not mapped", PrefillMappings.leftOut(field("message", null, false, true, false)));
        assertEquals("the field is mapped but names no profile property (none chosen, or the list no longer offers it)",
                PrefillMappings.leftOut(field("switchedOn", "", true, true, false)));
        assertEquals("the field is marked sensitive", PrefillMappings.leftOut(field("secret", "nationality", true, true, true)));
        assertNull(PrefillMappings.leftOut(field("firstName", "firstName", true, true, false)));
    }

    @Test
    void everyMappableFieldIsADependencyWhateverItMaps() throws Exception {
        // Verifies what the block's cache entry must be flushed for: mapping a field later, switching its
        // prefill on — changes to a field the block did not mention yet.
        PrefillMappings.Prefill prefill = over(List.of(
                field("firstName", "firstName", true, true, false),
                field("message", null, false, false, false)
        )).read(mock(JCRSessionWrapper.class), mock(JCRNodeWrapper.class));

        assertEquals(List.of("/sites/mysite/contents/contact/fields/firstName", "/sites/mysite/contents/contact/fields/message"), prefill.dependencies());
    }

    @Test
    void whatFollowsTheWriteTravelsOnlyWhenTheAuthorAskedForSomething() throws Exception {
        // Verifies the one option of the mapping fieldset as the block carries it: "editable" — the default the
        // editor stores, and what a field saved before the option existed has nothing of — says nothing, the
        // two others travel as they are stored.
        PrefillMappings.Prefill prefill = over(List.of(
                field("firstName", "firstName", true, true, false, "editable"),
                field("email", "email", true, true, false, "readOnly"),
                field("country", "countryName", true, true, false, "hidden"),
                field("kids", "kids", true, true, false, null)
        )).read(mock(JCRSessionWrapper.class), mock(JCRNodeWrapper.class));

        assertEquals(Map.of(
                "firstName", new PrefillMappings.Entry("firstName", null),
                "email", new PrefillMappings.Entry("email", "readOnly"),
                "country", new PrefillMappings.Entry("countryName", "hidden"),
                "kids", new PrefillMappings.Entry("kids", null)), prefill.entries());
    }

    @Test
    void theBlockCarriesNamesAndWhatFollowsTheWriteAndNothingElse() {
        Map<String, PrefillMappings.Entry> entries = new LinkedHashMap<>();
        entries.put("first\"Name", new PrefillMappings.Entry("firstName", null));
        entries.put("email", new PrefillMappings.Entry("email", "readOnly"));
        assertEquals("{\"first\\\"Name\":{\"property\":\"firstName\"},\"email\":{\"property\":\"email\",\"then\":\"readOnly\"}}", PrefillMappings.json(entries));
        assertEquals("{}", PrefillMappings.json(Map.of()));
    }
}
