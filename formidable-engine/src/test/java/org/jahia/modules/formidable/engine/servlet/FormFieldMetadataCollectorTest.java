package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.servlet.FormDataParser;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.utils.LanguageCodeConverters;
import org.junit.jupiter.api.Test;

import javax.jcr.Node;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormFieldMetadataCollectorTest {

    @Test
    void collectsChoiceFieldMetadataFromSemanticMixin() throws Exception {
        // Verifies that choice fields declared through semantic mixins expose their node type
        // and allowed option values to the submission parser.
        JCRNodeWrapper choiceField = node(
                "plan",
                "fmdb:radio",
                Set.of("fmdbmix:formElement", "fmdbmix:choiceField"),
                Map.of("options", multiValueProperty(
                        "{\"value\":\"basic\",\"label\":\"Basic\"}",
                        "{\"value\":\"pro\",\"label\":\"Pro\"}"
                )),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(choiceField)
        );

        FormDataParser.FieldInfo info = result.fieldInfos().get("plan");
        // Expected outcome: the field is recognized as a choice field with both configured options.
        assertNotNull(info);
        assertEquals("fmdb:radio", info.nodeType());
        assertTrue(info.choiceField());
        assertEquals(Set.of("basic", "pro"), info.allowedChoices());
    }

    @Test
    void collectsFileFieldMetadataAndAcceptTypes() throws Exception {
        // Verifies that file-field metadata includes the configured accept allowlist.
        JCRNodeWrapper fileField = node(
                "resume",
                "fmdb:inputFile",
                Set.of("fmdbmix:formElement", "fmdbmix:fileField"),
                Map.of("accept", multiValueProperty("application/pdf", "image/*")),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(fileField)
        );

        FormDataParser.FieldInfo info = result.fieldInfos().get("resume");
        // Expected outcome: the field is flagged as a file input with both accept tokens preserved.
        assertNotNull(info);
        assertTrue(info.fileField());
        assertEquals(Set.of("application/pdf", "image/*"), info.acceptTypes());
    }

    @Test
    void collectsDateAndDatetimeConstraintsFromSemanticMixins() throws Exception {
        // Verifies that date and datetime-local field mixins contribute normalized
        // min/max constraints. These nodes carry no bound mode, which is also the
        // legacy shape (stored before bound modes existed): fixed values must keep
        // applying until the startup migration stamps the mode.
        JCRNodeWrapper dateField = node(
                "birthday",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:dateField"),
                Map.of(
                        "min", dateProperty(calendar(2026, 6, 1, 0, 0)),
                        "max", dateProperty(calendar(2026, 6, 30, 0, 0))
                ),
                List.of()
        );
        JCRNodeWrapper datetimeField = node(
                "appointment",
                "fmdb:inputDatetimeLocal",
                Set.of("fmdbmix:formElement", "fmdbmix:datetimeLocalField"),
                Map.of(
                        "min", dateProperty(calendar(2026, 6, 1, 9, 15)),
                        "max", dateProperty(calendar(2026, 6, 1, 17, 45))
                ),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(dateField, datetimeField)
        );

        FormDataParser.FieldInfo dateInfo = result.fieldInfos().get("birthday");
        // Expected outcome: date constraints are normalized as ISO local-date strings.
        assertNotNull(dateInfo);
        assertTrue(dateInfo.dateField());
        assertNotNull(dateInfo.constraints());
        assertEquals("2026-06-01", dateInfo.constraints().minDate());
        assertEquals("2026-06-30", dateInfo.constraints().maxDate());

        FormDataParser.FieldInfo datetimeInfo = result.fieldInfos().get("appointment");
        // Expected outcome: datetime-local constraints are normalized as ISO local-date-time strings.
        assertNotNull(datetimeInfo);
        assertTrue(datetimeInfo.datetimeLocalField());
        assertNotNull(datetimeInfo.constraints());
        assertEquals("2026-06-01T09:15", datetimeInfo.constraints().minDate());
        assertEquals("2026-06-01T17:45", datetimeInfo.constraints().maxDate());
    }

    @Test
    void boundModesResolveDateBoundsAgainstTheSubmissionDay() throws Exception {
        // Verifies the bound-mode contract: 'today' resolves to the extreme calendar
        // day any inhabited timezone can currently be (UTC-12 for a minimum, UTC+14
        // for a maximum), so no visitor is rejected for a value their own picker
        // allowed; 'date' reads the fixed value, and the modes are exclusive per side.
        JCRNodeWrapper birthday = node(
                "birthday",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:dateField"),
                Map.of("maxBoundMode", stringProperty("today")),
                List.of()
        );
        // One side relative, the other fixed: each side resolves independently.
        JCRNodeWrapper appointment = node(
                "appointment",
                "fmdb:inputDatetimeLocal",
                Set.of("fmdbmix:formElement", "fmdbmix:datetimeLocalField"),
                Map.of(
                        "minBoundMode", stringProperty("today"),
                        "maxBoundMode", stringProperty("date"),
                        "max", dateProperty(calendar(2030, 6, 30, 18, 0))
                ),
                List.of()
        );
        // A relative bound is the submission day shifted by a signed offset, widened
        // to the same timezone extreme as the today mode; java.time carries the
        // month/year clamping.
        JCRNodeWrapper adult = node(
                "adult",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:dateField"),
                Map.of(
                        "maxBoundMode", stringProperty("relative"),
                        "maxRelativeAmount", longProperty(-18),
                        "maxRelativeUnit", stringProperty("years")
                ),
                List.of()
        );
        JCRNodeWrapper booking = node(
                "booking",
                "fmdb:inputDatetimeLocal",
                Set.of("fmdbmix:formElement", "fmdbmix:datetimeLocalField"),
                Map.of(
                        "maxBoundMode", stringProperty("relative"),
                        "maxRelativeAmount", longProperty(30),
                        "maxRelativeUnit", stringProperty("days")
                ),
                List.of()
        );
        // A datetime maximum on the submission day must cover the whole last minute:
        // the validator accepts seconds, so T23:59 alone would reject T23:59:30.
        JCRNodeWrapper deadline = node(
                "deadline",
                "fmdb:inputDatetimeLocal",
                Set.of("fmdbmix:formElement", "fmdbmix:datetimeLocalField"),
                Map.of("maxBoundMode", stringProperty("today")),
                List.of()
        );
        // An explicit 'none' wins over a residual fixed value left behind by an
        // earlier configuration: the bound is gone, not silently resurrected.
        JCRNodeWrapper unbounded = node(
                "unbounded",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:dateField"),
                Map.of(
                        "maxBoundMode", stringProperty("none"),
                        "max", dateProperty(calendar(2020, 6, 30, 0, 0))
                ),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(birthday, appointment, adult, booking, deadline, unbounded)
        );

        String easternExtremeDay = java.time.LocalDate.now(java.time.ZoneOffset.ofHours(14)).toString();
        String westernExtremeDay = java.time.LocalDate.now(java.time.ZoneOffset.ofHours(-12)).toString();
        assertEquals(easternExtremeDay, result.fieldInfos().get("birthday").constraints().maxDate());
        assertNull(result.fieldInfos().get("birthday").constraints().minDate());
        assertEquals(westernExtremeDay + "T00:00", result.fieldInfos().get("appointment").constraints().minDate());
        assertEquals("2030-06-30T18:00", result.fieldInfos().get("appointment").constraints().maxDate());
        assertEquals(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(14)).plusYears(-18).toString(),
                result.fieldInfos().get("adult").constraints().maxDate());
        assertEquals(java.time.LocalDate.now(java.time.ZoneOffset.ofHours(14)).plusDays(30) + "T23:59:59.999",
                result.fieldInfos().get("booking").constraints().maxDate());
        assertEquals(easternExtremeDay + "T23:59:59.999", result.fieldInfos().get("deadline").constraints().maxDate());
        assertNull(result.fieldInfos().get("unbounded").constraints());
    }

    @Test
    void legacyDefinitionLessBoundsAreReadOnTheUnderlyingNode() throws Exception {
        // A field stored before the bound modes existed carries fixed values whose
        // property definition has since moved into the fixed-bound mixins: the
        // wrapper API hides them, so validation reads the underlying node until
        // the startup migration re-homes them.
        JCRNodeWrapper legacy = node(
                "legacyDate",
                "fmdb:inputDate",
                Set.of("fmdbmix:formElement", "fmdbmix:dateField"),
                Map.of(),
                List.of()
        );
        Node realNode = mock(Node.class);
        javax.jcr.Property rawMin = mock(javax.jcr.Property.class);
        when(rawMin.getDate()).thenReturn(calendar(2020, 1, 1, 0, 0));
        when(realNode.hasProperty("min")).thenReturn(true);
        when(realNode.getProperty("min")).thenReturn(rawMin);
        when(realNode.hasProperty("max")).thenReturn(false);
        when(legacy.getRealNode()).thenReturn(realNode);

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(legacy)
        );

        assertEquals("2020-01-01", result.fieldInfos().get("legacyDate").constraints().minDate());
        assertNull(result.fieldInfos().get("legacyDate").constraints().maxDate());
    }

    @Test
    void skipsNonSubmittableNodes() throws Exception {
        // Verifies that helper nodes marked non-submittable are excluded from parser metadata.
        JCRNodeWrapper nonSubmittable = node(
                "csrfToken",
                "fmdb:hidden",
                Set.of("fmdbmix:formElement", "fmdbmix:nonSubmittable"),
                Map.of(),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(nonSubmittable)
        );

        // Expected outcome: the non-submittable helper node is omitted entirely.
        assertFalse(result.fieldInfos().containsKey("csrfToken"));
    }

    @Test
    void manualChoicesAreReadFromTheDefaultLanguage() throws Exception {
        // Option values are one identity set owned by the site's default language, so
        // a translation that diverged (or is simply a publication behind) can neither
        // smuggle a value in nor reject a legitimate one: 'mint' lives only in the
        // submitted locale's list and is not allowed, 'vanilla' lives only in the
        // master's and is.
        JCRNodeWrapper choiceField = manualChoiceField(
                "flavor",
                Map.of("options", multiValueProperty(
                        "{\"value\":\"mint\",\"label\":\"Menthe\"}",
                        "{\"value\":\"chocolate\",\"label\":\"Chocolat\"}"
                )),
                "en",
                Map.of(
                        "en", new String[]{
                                "{\"value\":\"vanilla\",\"label\":\"Vanilla\"}",
                                "{\"value\":\"chocolate\",\"label\":\"Chocolate\"}"
                        },
                        "fr", new String[]{
                                "{\"value\":\"mint\",\"label\":\"Menthe\"}",
                                "{\"value\":\"chocolate\",\"label\":\"Chocolat\"}"
                        }
                )
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(choiceField)
        );

        assertEquals(Set.of("vanilla", "chocolate"), result.fieldInfos().get("flavor").allowedChoices());
    }

    @Test
    void manualChoicesAreReadFromTheDefaultLanguageWithoutALocalizedList() throws Exception {
        // A form served in a language nobody translated renders the master's entries,
        // so the values it can legitimately submit are the master's too — the read
        // cannot hang on the submitted locale carrying the property.
        JCRNodeWrapper choiceField = manualChoiceField(
                "flavor",
                Map.of(),
                "en",
                Map.of("en", new String[]{"{\"value\":\"vanilla\",\"label\":\"Vanilla\"}"})
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(choiceField)
        );

        assertEquals(Set.of("vanilla"), result.fieldInfos().get("flavor").allowedChoices());
    }

    @Test
    void manualChoicesFallBackToTheStoredListWithoutAMasterList() throws Exception {
        // No default-language list to align on — a field authored in another language
        // only, or one whose options were cleared: the stored list stays the identity.
        JCRNodeWrapper choiceField = manualChoiceField(
                "flavor",
                Map.of("options", multiValueProperty("{\"value\":\"mint\",\"label\":\"Menthe\"}")),
                "en",
                Map.of("fr", new String[]{"{\"value\":\"mint\",\"label\":\"Menthe\"}"})
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(choiceField)
        );

        assertEquals(Set.of("mint"), result.fieldInfos().get("flavor").allowedChoices());
    }

    /**
     * A manual choice field whose i18n storage is visible: {@code properties} is what
     * the submitted locale reads, {@code optionsByLanguage} what each
     * j:translation_* subnode carries.
     */
    private static JCRNodeWrapper manualChoiceField(String name, Map<String, JCRPropertyWrapper> properties,
                                                    String defaultLanguage,
                                                    Map<String, String[]> optionsByLanguage) throws Exception {
        JCRNodeWrapper field = node(
                name,
                "fmdb:select",
                Set.of("fmdbmix:formElement", "fmdbmix:choiceField", "fmdbmix:manualOptions"),
                properties,
                List.of()
        );

        List<Node> translations = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : optionsByLanguage.entrySet()) {
            translations.add(translationNode(entry.getKey(), entry.getValue()));
        }

        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getDefaultLanguage()).thenReturn(defaultLanguage);
        when(field.getResolveSite()).thenReturn(site);

        // Answered per call: the iterator is one-shot.
        when(field.getI18Ns()).thenAnswer(invocation -> nodeIterator(translations));
        for (Node translation : translations) {
            String language = translation.getProperty("jcr:language").getString();
            Locale locale = LanguageCodeConverters.languageCodeToLocale(language);
            when(field.hasI18N(locale, false)).thenReturn(true);
            when(field.getI18N(locale, false)).thenReturn(translation);
        }

        return field;
    }

    private static Node translationNode(String language, String... options) throws Exception {
        // Every property mock is built BEFORE the stubbing that returns it: creating a
        // mock between when() and thenReturn() is unfinished stubbing.
        JCRPropertyWrapper languageProperty = stringProperty(language);
        JCRPropertyWrapper optionsProperty = options.length > 0 ? multiValueProperty(options) : null;

        Node translation = mock(Node.class);
        when(translation.getName()).thenReturn("j:translation_" + language);
        when(translation.hasProperty("jcr:language")).thenReturn(true);
        when(translation.getProperty("jcr:language")).thenReturn(languageProperty);
        when(translation.hasProperty("options")).thenReturn(optionsProperty != null);
        if (optionsProperty != null) {
            when(translation.getProperty("options")).thenReturn(optionsProperty);
        }

        return translation;
    }

    private static JCRNodeWrapper formNodeWithFields(JCRNodeWrapper... fields) throws Exception {
        JCRNodeWrapper fieldList = node(
                "fields",
                "fmdb:fieldList",
                Set.of(),
                Map.of(),
                List.of(fields)
        );

        return node(
                "form",
                "fmdb:form",
                Set.of(),
                Map.of(),
                List.of(fieldList)
        );
    }

    @Test
    void nestedConditionalContainersChainThroughTheParentMap() throws Exception {
        // A conditional fieldset inside a conditional step: the step's verdict must
        // reach the field THROUGH the fieldset, so the containers themselves need an
        // entry in the parent map — containers are either non-submittable (fieldset)
        // or not form elements at all (step), so registerField never records them.
        JCRPropertyWrapper stepLogics = multiValueProperty(
                "{\"sourceFieldName\":\"gate\",\"operator\":\"in\",\"values\":[\"open\"]}");
        JCRPropertyWrapper fieldsetLogics = multiValueProperty(
                "{\"sourceFieldName\":\"detail\",\"operator\":\"in\",\"values\":[\"yes\"]}");

        JCRNodeWrapper child = node(
                "child", "fmdb:text", Set.of("fmdbmix:formElement"), Map.of(), List.of());
        JCRNodeWrapper fieldset = node(
                "fieldset", "fmdb:fieldset",
                Set.of("fmdbmix:formContainer", "fmdbmix:nonSubmittable"),
                Map.of("logics", fieldsetLogics),
                List.of(child));
        JCRNodeWrapper step = node(
                "step", "fmdb:step",
                Set.of("fmdbmix:formContainer"),
                Map.of("logics", stepLogics),
                List.of(fieldset));

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(step)
        );

        // Expected outcome: each level points at its direct conditional container,
        // and both containers carry their own rules for the evaluator to walk.
        assertEquals(Set.of("step"), result.fieldParentContainers().get("fieldset"));
        assertEquals(Set.of("fieldset"), result.fieldParentContainers().get("child"));
        assertNotNull(result.fieldLogicRules().get("step"));
        assertNotNull(result.fieldLogicRules().get("fieldset"));
    }

    @Test
    void aFieldNamedLikeItsContainerDoesNotProduceASelfEdge() throws Exception {
        // JCR names are only unique among siblings: a field may share its conditional
        // fieldset's name. A self-edge in the parent map resolves through the
        // evaluator's cycle guard to VISIBLE, and one visible parent wins — the real
        // enclosing step's verdict would never be consulted, so an honestly hidden
        // field would be required server-side (FMDB-010). The self-edge is collision
        // noise and must not be collected; the step edge must survive.
        JCRPropertyWrapper stepLogics = multiValueProperty(
                "{\"sourceFieldName\":\"gate\",\"operator\":\"in\",\"values\":[\"open\"]}");
        JCRPropertyWrapper fieldsetLogics = multiValueProperty(
                "{\"sourceFieldName\":\"detail\",\"operator\":\"in\",\"values\":[\"yes\"]}");

        JCRNodeWrapper collidingField = node(
                "address", "fmdb:text", Set.of("fmdbmix:formElement"), Map.of(), List.of());
        JCRNodeWrapper fieldset = node(
                "address", "fmdb:fieldset",
                Set.of("fmdbmix:formContainer", "fmdbmix:nonSubmittable"),
                Map.of("logics", fieldsetLogics),
                List.of(collidingField));
        JCRNodeWrapper step = node(
                "step", "fmdb:step",
                Set.of("fmdbmix:formContainer"),
                Map.of("logics", stepLogics),
                List.of(fieldset));

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(step)
        );

        // Expected outcome: one parent only — the step. No self-edge.
        assertEquals(Set.of("step"), result.fieldParentContainers().get("address"));
    }

    @Test
    void collectsSourcedChoiceOptionsThroughTheResolver() throws Exception {
        // Verifies D11 plumbing: a sourced choice field gets its allowed values from the
        // re-resolved source, in the manual-options JSON format.
        JCRNodeWrapper sourced = node(
                "country",
                "fmdb:select",
                Set.of("fmdbmix:formElement", "fmdbmix:choiceField", "fmdbmix:sourcedOptions"),
                Map.of(),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(sourced),
                node -> new String[]{
                        "{\"value\":\"FR\",\"label\":\"France\",\"selected\":false}",
                        "{\"value\":\"DE\",\"label\":\"Germany\",\"selected\":false}"
                }
        );

        // Expected outcome: the resolved values are the allowlist, and the field is resolvable.
        assertEquals(Set.of("FR", "DE"), result.toParserMetadata().allowedChoices("country"));
        assertFalse(result.toParserMetadata().choicesUnresolvable("country"));
    }

    @Test
    void collectsCategoryModeChoicesThroughTheResolver() throws Exception {
        // Verifies the gate stays aligned with the service dispatch: a category-mode
        // field also gets its allowlist from the resolver, not from stored options.
        JCRNodeWrapper categoryField = node(
                "tvCategory",
                "fmdb:radio",
                Set.of("fmdbmix:formElement", "fmdbmix:choiceField", "fmdbmix:categoryOptions"),
                Map.of(),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(categoryField),
                node -> new String[]{"{\"value\":\"oled\",\"label\":\"OLED\",\"selected\":false}"}
        );

        // Expected outcome: the resolved category values are the allowlist.
        assertEquals(Set.of("oled"), result.toParserMetadata().allowedChoices("tvCategory"));
        assertFalse(result.toParserMetadata().choicesUnresolvable("tvCategory"));
    }

    @Test
    void flagsSourcedChoiceFieldWhenTheSourceCannotDeliver() throws Exception {
        // Verifies the D11 failure path: a failing source flags the field instead of
        // leaving an empty allowlist that would accept anything.
        JCRNodeWrapper sourced = node(
                "country",
                "fmdb:select",
                Set.of("fmdbmix:formElement", "fmdbmix:choiceField", "fmdbmix:sourcedOptions"),
                Map.of(),
                List.of()
        );

        FormFieldMetadataCollector.Result result = FormFieldMetadataCollector.collectFromFormNode(
                formNodeWithFields(sourced),
                node -> {
                    throw new IllegalStateException("source down");
                }
        );

        // Expected outcome: no allowed values, and the unresolvable flag is set.
        assertEquals(Set.of(), result.toParserMetadata().allowedChoices("country"));
        assertTrue(result.toParserMetadata().choicesUnresolvable("country"));
    }

    private static JCRNodeWrapper node(String name,
                                       String primaryType,
                                       Set<String> nodeTypes,
                                       Map<String, JCRPropertyWrapper> properties,
                                       List<JCRNodeWrapper> children) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getPath()).thenReturn("/" + name);
        when(node.getPrimaryNodeTypeName()).thenReturn(primaryType);
        when(node.isNodeType(anyString())).thenAnswer(invocation -> {
            String queriedType = invocation.getArgument(0);
            return primaryType.equals(queriedType) || nodeTypes.contains(queriedType);
        });
        when(node.hasProperty(anyString())).thenAnswer(invocation -> properties.containsKey(invocation.getArgument(0)));
        when(node.getProperty(anyString())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        JCRNodeIteratorWrapper childrenIterator = nodeIterator(children);
        when(node.getNodes()).thenReturn(childrenIterator);

        Map<String, JCRNodeWrapper> childMap = children.stream()
                .collect(java.util.stream.Collectors.toMap(
                        child -> {
                            try {
                                return child.getName();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        child -> child
                ));
        when(node.hasNode(anyString())).thenAnswer(invocation -> childMap.containsKey(invocation.getArgument(0)));
        when(node.getNode(anyString())).thenAnswer(invocation -> childMap.get(invocation.getArgument(0)));
        return node;
    }

    private static JCRNodeIteratorWrapper nodeIterator(List<? extends Node> nodes) {
        JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
        java.util.Iterator<? extends Node> delegate = nodes.iterator();
        when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
        when(iterator.nextNode()).thenAnswer(invocation -> delegate.next());
        return iterator;
    }

    private static JCRPropertyWrapper multiValueProperty(String... rawValues) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        JCRValueWrapper[] values = new JCRValueWrapper[rawValues.length];
        for (int i = 0; i < rawValues.length; i++) {
            JCRValueWrapper value = mock(JCRValueWrapper.class);
            when(value.getString()).thenReturn(rawValues[i]);
            values[i] = value;
        }
        when(property.getValues()).thenReturn(values);
        return property;
    }

    private static JCRPropertyWrapper dateProperty(Calendar calendar) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getDate()).thenReturn(calendar);
        return property;
    }

    private static JCRPropertyWrapper stringProperty(String value) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getString()).thenReturn(value);
        return property;
    }

    private static JCRPropertyWrapper longProperty(long value) throws Exception {
        JCRPropertyWrapper property = mock(JCRPropertyWrapper.class);
        when(property.getLong()).thenReturn(value);
        return property;
    }

    private static Calendar calendar(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar;
    }
}
