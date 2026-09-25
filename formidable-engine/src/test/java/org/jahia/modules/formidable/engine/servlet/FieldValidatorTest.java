package org.jahia.modules.formidable.engine.servlet;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldValidatorTest {

    @Test
    void acceptsValidEmailForEmailField() {
        // Verifies the positive email-validation path for an email field.
        FormDataParser.FieldMetadata metadata = metadata("email", fieldInfo(
                "fmdb:inputEmail", false, false, false, true, false, false, false, null
        ));

        // Expected outcome: a valid email passes validation.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("email", "alice@example.com", metadata));
    }

    @Test
    void rejectsInvalidEmailForEmailField() {
        // Verifies type-specific validation: an email field must reject a value
        // that does not match the supported email format.
        FormDataParser.FieldMetadata metadata = metadata("email", fieldInfo(
                "fmdb:inputEmail", false, false, false, true, false, false, false, null
        ));

        // Expected outcome: the validator rejects the malformed email value.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("email", "not-an-email", metadata));
    }

    @Test
    void acceptsValidDateWithinConfiguredBounds() {
        // Verifies the positive date-validation path with an in-range date.
        FormDataParser.FieldMetadata metadata = metadata("startDate", fieldInfo(
                "fmdb:inputDate",
                false, false, false, false, true, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, null, "2026-01-10", "2026-01-20")
        ));

        // Expected outcome: the valid in-range date passes validation.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("startDate", "2026-01-15", metadata));
    }

    @Test
    void rejectsDateBeforeConfiguredMinimum() {
        // Verifies date-bound enforcement: a submitted date before the configured minimum
        // must be rejected for a date field.
        FormDataParser.FieldMetadata metadata = metadata("startDate", fieldInfo(
                "fmdb:inputDate",
                false, false, false, false, true, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, null, "2026-01-10", null)
        ));

        // Expected outcome: the validator rejects the value because it is before the min date.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("startDate", "2026-01-09", metadata));
    }

    @Test
    void rejectsDateAfterConfiguredMaximum() {
        // Verifies date-bound enforcement on the maximum side.
        FormDataParser.FieldMetadata metadata = metadata("startDate", fieldInfo(
                "fmdb:inputDate",
                false, false, false, false, true, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, null, null, "2026-01-20")
        ));

        // Expected outcome: a date after the configured maximum is rejected.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("startDate", "2026-01-21", metadata));
    }

    @Test
    void rejectsMalformedDateForDateField() {
        // Verifies date format validation for date fields.
        FormDataParser.FieldMetadata metadata = metadata("startDate", fieldInfo(
                "fmdb:inputDate", false, false, false, false, true, false, false, null
        ));

        // Expected outcome: a malformed yyyy-MM-dd value is rejected.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("startDate", "01/15/2026", metadata));
    }

    @Test
    void acceptsValidDatetimeLocalWithinConfiguredBounds() {
        // Verifies the positive datetime-local validation path with an in-range timestamp.
        FormDataParser.FieldMetadata metadata = metadata("appointment", fieldInfo(
                "fmdb:inputDatetimeLocal",
                false, false, false, false, false, true, false,
                new FormDataParser.FieldConstraints(false, -1, -1, null, "2026-01-10T09:00", "2026-01-10T18:00")
        ));

        // Expected outcome: the valid in-range datetime-local value passes validation.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("appointment", "2026-01-10T10:30", metadata));
    }

    @Test
    void rejectsMalformedDatetimeLocalForDatetimeLocalField() {
        // Verifies datetime-local format validation.
        FormDataParser.FieldMetadata metadata = metadata("appointment", fieldInfo(
                "fmdb:inputDatetimeLocal", false, false, false, false, false, true, false, null
        ));

        // Expected outcome: a malformed datetime-local value is rejected.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("appointment", "2026-01-10 10:30", metadata));
    }

    @Test
    void acceptsValidColorForColorField() {
        // Verifies the positive color-validation path for #RRGGBB values.
        FormDataParser.FieldMetadata metadata = metadata("themeColor", fieldInfo(
                "fmdb:inputColor", false, false, false, false, false, false, true, null
        ));

        // Expected outcome: a valid six-digit hexadecimal color passes validation.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("themeColor", "#A1b2C3", metadata));
    }

    @Test
    void rejectsInvalidColorForColorField() {
        // Verifies color format validation for color fields.
        FormDataParser.FieldMetadata metadata = metadata("themeColor", fieldInfo(
                "fmdb:inputColor", false, false, false, false, false, false, true, null
        ));

        // Expected outcome: an invalid color format is rejected.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("themeColor", "#FFF", metadata));
    }

    @Test
    void acceptsMatchingRegexConstraint() {
        // Verifies the positive regex-constraint path.
        FormDataParser.FieldMetadata metadata = metadata("username", fieldInfo(
                "fmdb:inputText",
                false, false, false, false, false, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, "^[a-z]+$", null, null)
        ));

        // Expected outcome: a value matching the configured pattern passes validation.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("username", "alice", metadata));
    }

    @Test
    void rejectsNonMatchingRegexConstraint() {
        // Verifies the negative regex-constraint path.
        FormDataParser.FieldMetadata metadata = metadata("username", fieldInfo(
                "fmdb:inputText",
                false, false, false, false, false, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, "^[a-z]+$", null, null)
        ));

        // Expected outcome: a value not matching the configured pattern is rejected.
        FormDataParser.ParseException error = assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("username", "alice123", metadata));

        assertEquals(FormDataParser.ParseException.FailureType.VALIDATION, error.failureType());
    }

    @Test
    void rejectsInvalidRegexPatternAsConfigurationError() {
        // Verifies fail-closed validation: an invalid admin-configured regex pattern
        // must be treated as a form configuration error, not silently ignored.
        FormDataParser.FieldMetadata metadata = metadata("username", fieldInfo(
                "fmdb:inputText",
                false, false, false, false, false, false, false,
                new FormDataParser.FieldConstraints(false, -1, -1, "[", null, null)
        ));

        FormDataParser.ParseException error = assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("username", "alice", metadata));

        assertEquals(FormDataParser.ParseException.FailureType.CONFIGURATION, error.failureType());
    }

    @Test
    void rejectsValueShorterThanConfiguredMinimumLength() {
        // Verifies minimum-length enforcement.
        FormDataParser.FieldMetadata metadata = metadata("username", fieldInfo(
                "fmdb:inputText",
                false, false, false, false, false, false, false,
                new FormDataParser.FieldConstraints(false, 3, -1, null, null, null)
        ));

        // Expected outcome: a value shorter than the configured minimum is rejected.
        FormDataParser.ParseException error = assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("username", "ab", metadata));

        assertEquals(FormDataParser.ParseException.FailureType.VALIDATION, error.failureType());
    }

    @Test
    void rejectsValueLongerThanConfiguredMaximumLength() {
        // Verifies maximum-length enforcement.
        FormDataParser.FieldMetadata metadata = metadata("username", fieldInfo(
                "fmdb:inputText",
                false, false, false, false, false, false, false,
                new FormDataParser.FieldConstraints(false, -1, 5, null, null, null)
        ));

        // Expected outcome: a value longer than the configured maximum is rejected.
        FormDataParser.ParseException error = assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("username", "abcdef", metadata));

        assertEquals(FormDataParser.ParseException.FailureType.VALIDATION, error.failureType());
    }

    @Test
    void acceptsNumericValueForNumberField() {
        // Verifies the positive number-validation path (fmdbmix:numberField, e.g. rating/scale).
        FormDataParser.FieldMetadata metadata = metadata("satisfaction",
                numberFieldInfo("fmdbext:rating", null, null));

        assertDoesNotThrow(() -> FieldValidator.validateTextField("satisfaction", "4", metadata));
        assertDoesNotThrow(() -> FieldValidator.validateTextField("satisfaction", "4.5", metadata));
    }

    @Test
    void rejectsNonNumericValueForNumberField() {
        // Verifies tampering protection: the rendered control constrains values browser-side
        // only, so a forged non-numeric submission must be rejected server-side.
        FormDataParser.FieldMetadata metadata = metadata("satisfaction",
                numberFieldInfo("fmdbext:rating", null, null));

        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("satisfaction", "abc", metadata));
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("satisfaction", "Infinity", metadata));
    }

    @Test
    void enforcesNumberBoundsFromMinValueMaxValue() {
        // Verifies range enforcement: rating/scale declare their range through
        // minValue/maxValue, and out-of-range submissions must be rejected.
        FormDataParser.FieldMetadata metadata = metadata("agreement",
                numberFieldInfo("fmdbext:scale", 0.0, 10.0));

        assertDoesNotThrow(() -> FieldValidator.validateTextField("agreement", "0", metadata));
        assertDoesNotThrow(() -> FieldValidator.validateTextField("agreement", "10", metadata));
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("agreement", "11", metadata));
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("agreement", "-1", metadata));
    }

    @Test
    void acceptsBooleanValuesIncludingCheckboxDefaultForBooleanField() {
        // Verifies the boolean value kind (fmdbmix:booleanField, e.g. switch/consent):
        // "true"/"false" case-insensitively, plus "on" — the browser default for a plain
        // checkbox without a value attribute, treated as on by both evaluators.
        FormDataParser.FieldMetadata metadata = metadata("newsletter",
                booleanFieldInfo("fmdbext:switch"));

        assertDoesNotThrow(() -> FieldValidator.validateTextField("newsletter", "true", metadata));
        assertDoesNotThrow(() -> FieldValidator.validateTextField("newsletter", "false", metadata));
        assertDoesNotThrow(() -> FieldValidator.validateTextField("newsletter", "TRUE", metadata));
        assertDoesNotThrow(() -> FieldValidator.validateTextField("newsletter", "on", metadata));
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("newsletter", "yes", metadata));
    }

    private static FormDataParser.FieldMetadata metadata(String fieldName, FormDataParser.FieldInfo info) {
        return new FormDataParser.FieldMetadata(Map.of(fieldName, info));
    }

    private static FormDataParser.FieldInfo numberFieldInfo(String nodeType, Double minNumber, Double maxNumber) {
        FormDataParser.FieldConstraints constraints = minNumber == null && maxNumber == null
                ? null
                : new FormDataParser.FieldConstraints(false, -1, -1, null, null, null, minNumber, maxNumber);
        return new FormDataParser.FieldInfo(
                nodeType, false, false, false, false, false, false, false,
                true, false, Set.of(), Set.of(), constraints);
    }

    private static FormDataParser.FieldInfo booleanFieldInfo(String nodeType) {
        return new FormDataParser.FieldInfo(
                nodeType, false, false, false, false, false, false, false,
                false, true, Set.of(), Set.of(), null);
    }

    @Test
    void rejectsNonEmptyValueWhenOptionsSourceIsUnresolvable() {
        // Verifies D11 (no tolerance): a value that cannot be checked against its source
        // must be rejected, never accepted blindly.
        FormDataParser.FieldMetadata metadata = metadata("country", unresolvableChoiceFieldInfo(false));

        // Expected outcome: the unverifiable value is rejected.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("country", "FR", metadata));
    }

    @Test
    void rejectsEmptyValueOnRequiredFieldWhenOptionsSourceIsUnresolvable() {
        // Verifies D11 on the required path: a required choice cannot be skipped
        // just because its options source is down.
        FormDataParser.FieldMetadata metadata = metadata("country", unresolvableChoiceFieldInfo(true));

        // Expected outcome: the empty value is rejected on the required field.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("country", "", metadata));
    }

    @Test
    void acceptsEmptyValueOnOptionalFieldWhenOptionsSourceIsUnresolvable() {
        // Verifies D11 on the optional path: the form stays usable without the
        // broken optional field.
        FormDataParser.FieldMetadata metadata = metadata("country", unresolvableChoiceFieldInfo(false));

        // Expected outcome: the empty value passes on the optional field.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("country", "", metadata));
    }

    @Test
    void rejectsNonEmptyValueOnChoiceFieldWithEmptyAllowlist() {
        // A choice field whose option list resolves to nothing renders nothing
        // selectable: any non-empty submitted value is forged. The empty allowlist
        // must not disable the check.
        FormDataParser.FieldInfo info = new FormDataParser.FieldInfo(
                "fmdb:select", false, true, false, false, false, false, false,
                Set.of(), Set.of(), null);
        FormDataParser.FieldMetadata metadata = metadata("emptyChoices", info);

        // Expected outcome: the value is rejected even though the allowlist is empty.
        assertThrows(FormDataParser.ParseException.class,
                () -> FieldValidator.validateTextField("emptyChoices", "anything", metadata));
    }

    @Test
    void acceptsAnyValueOnNonChoiceFieldWithoutAllowlist() {
        // Non-choice fields carry an empty allowlist by construction: the choice
        // check must not apply to them.
        FormDataParser.FieldInfo info = fieldInfo(
                "fmdb:inputText", false, false, false, false, false, false, false, null);
        FormDataParser.FieldMetadata metadata = metadata("freeText", info);

        // Expected outcome: the free-text value passes.
        assertDoesNotThrow(() -> FieldValidator.validateTextField("freeText", "anything", metadata));
    }

    private static FormDataParser.FieldInfo unresolvableChoiceFieldInfo(boolean required) {
        FormDataParser.FieldConstraints constraints = required
                ? new FormDataParser.FieldConstraints(true, -1, -1, null, null, null)
                : null;
        return new FormDataParser.FieldInfo(
                "fmdb:select", false, true, false, false, false, false, false,
                false, false, Set.of(), true, Set.of(), constraints);
    }

    private static FormDataParser.FieldInfo fieldInfo(
            String nodeType,
            boolean nonSubmittable,
            boolean choiceField,
            boolean fileField,
            boolean emailField,
            boolean dateField,
            boolean datetimeLocalField,
            boolean colorField,
            FormDataParser.FieldConstraints constraints
    ) {
        return new FormDataParser.FieldInfo(
                nodeType,
                nonSubmittable,
                choiceField,
                fileField,
                emailField,
                dateField,
                datetimeLocalField,
                colorField,
                Set.of(),
                Set.of(),
                constraints
        );
    }
}
