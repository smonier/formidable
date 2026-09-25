package org.jahia.modules.formidable.engine.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates submitted text values against the field semantics and constraints
 * collected from JCR before multipart parsing starts.
 */
final class FieldValidator {

    private static final Logger log = LoggerFactory.getLogger(FieldValidator.class);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private static final DateTimeFormatter DATETIME_LOCAL_FMT =
            new DateTimeFormatterBuilder()
                    .append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral('T')
                    .appendPattern("HH:mm[:ss[.SSS]]")
                    .toFormatter();

    private FieldValidator() {
    }

    static void validateTextField(
            String fieldName,
            String value,
            FormDataParser.FieldMetadata metadata
    ) throws FormDataParser.ParseException {
        if (value == null || value.isEmpty()) {
            validateEmptyValue(fieldName, metadata);
            return;
        }

        validateChoice(fieldName, value, metadata);

        FormDataParser.FieldInfo fieldInfo = metadata.field(fieldName);
        if (fieldInfo != null) {
            validateTypeSemantics(fieldName, value, fieldInfo);
        }

        FormDataParser.FieldConstraints constraints = metadata.constraints(fieldName);
        if (constraints != null) {
            validateConstraints(fieldName, value, constraints, fieldInfo);
        }
    }

    /**
     * D11: when the options source is down, an empty value only passes on an
     * optional field; a required choice cannot be silently skipped.
     */
    private static void validateEmptyValue(String fieldName, FormDataParser.FieldMetadata metadata)
            throws FormDataParser.ParseException {
        if (metadata.choicesUnresolvable(fieldName) && isRequired(metadata, fieldName)) {
            log.warn("[FieldValidator] Rejected empty value: required field options source is unavailable");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': required field options are currently unavailable.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    private static void validateChoice(String fieldName, String value, FormDataParser.FieldMetadata metadata)
            throws FormDataParser.ParseException {
        // D11, no tolerance — a non-empty value must be present in the re-resolved list.
        // When the source cannot be resolved, the value cannot be verified and is rejected.
        if (metadata.choicesUnresolvable(fieldName)) {
            log.warn("[FieldValidator] Rejected submitted value: field options source is unavailable");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': submitted value cannot be verified, options are currently unavailable.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }

        // Gate on the field being a choice field, not on the allowlist being non-empty:
        // a choice field whose list resolves to nothing renders nothing selectable, so
        // any non-empty submitted value is forged and must be rejected — an empty
        // allowlist must not disable the check.
        FormDataParser.FieldInfo fieldInfo = metadata.field(fieldName);
        if (fieldInfo != null && fieldInfo.choiceField() && !metadata.allowedChoices(fieldName).contains(value)) {
            log.warn("[FieldValidator] Rejected submitted value: not in allowed choices");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': submitted value is not an allowed choice.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    /** One check per field semantic; a field can carry several (they are mixins). */
    private static void validateTypeSemantics(String fieldName, String value, FormDataParser.FieldInfo fieldInfo)
            throws FormDataParser.ParseException {
        if (fieldInfo.emailField()) {
            validateEmail(fieldName, value);
        }
        if (fieldInfo.dateField()) {
            validateDate(fieldName, value);
        }
        if (fieldInfo.datetimeLocalField()) {
            validateDatetimeLocal(fieldName, value);
        }
        if (fieldInfo.colorField()) {
            validateColor(fieldName, value);
        }
        if (fieldInfo.numberField()) {
            validateNumber(fieldName, value);
        }
        if (fieldInfo.booleanField()) {
            validateBoolean(fieldName, value);
        }
    }

    private static boolean isRequired(FormDataParser.FieldMetadata metadata, String fieldName) {
        FormDataParser.FieldConstraints constraints = metadata.constraints(fieldName);
        return constraints != null && constraints.required();
    }

    private static void validateConstraints(
            String fieldName,
            String value,
            FormDataParser.FieldConstraints constraints,
            FormDataParser.FieldInfo fieldInfo
    ) throws FormDataParser.ParseException {
        validateLengthBounds(fieldName, value, constraints);
        validatePattern(fieldName, value, constraints);
        if (fieldInfo != null && constraints.minDate() != null) {
            validateDateBound(fieldName, value, constraints.minDate(), fieldInfo, true);
        }
        if (fieldInfo != null && constraints.maxDate() != null) {
            validateDateBound(fieldName, value, constraints.maxDate(), fieldInfo, false);
        }
        if (constraints.minNumber() != null || constraints.maxNumber() != null) {
            validateNumberBounds(fieldName, value, constraints);
        }
    }

    private static void validateLengthBounds(String fieldName, String value, FormDataParser.FieldConstraints constraints)
            throws FormDataParser.ParseException {
        if (constraints.minLength() >= 0 && value.length() < constraints.minLength()) {
            log.warn("[FieldValidator] Rejected submitted value: below minimum length");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value too short (min " + constraints.minLength() + " chars).",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
        if (constraints.maxLength() >= 0 && value.length() > constraints.maxLength()) {
            log.warn("[FieldValidator] Rejected submitted value: exceeds maximum length");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value too long (max " + constraints.maxLength() + " chars).",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    private static void validatePattern(String fieldName, String value, FormDataParser.FieldConstraints constraints)
            throws FormDataParser.ParseException {
        if (constraints.pattern() == null || constraints.pattern().isBlank()) {
            return;
        }
        try {
            if (!value.matches(constraints.pattern())) {
                log.warn("[FieldValidator] Rejected submitted value: does not match configured pattern");
                throw new FormDataParser.ParseException(
                        "Field '" + fieldName + "': value does not match required format.",
                        FormDataParser.ParseException.FailureType.VALIDATION
                );
            }
        } catch (PatternSyntaxException e) {
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': invalid validation pattern configuration.",
                    FormDataParser.ParseException.FailureType.CONFIGURATION,
                    e
            );
        }
    }

    private static void validateNumberBounds(
            String fieldName,
            String value,
            FormDataParser.FieldConstraints constraints
    ) throws FormDataParser.ParseException {
        double number = parseFiniteNumber(fieldName, value);
        if (constraints.minNumber() != null && number < constraints.minNumber()) {
            log.warn("[FieldValidator] Rejected submitted value: below minimum number");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value below minimum (" + constraints.minNumber() + ").",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
        if (constraints.maxNumber() != null && number > constraints.maxNumber()) {
            log.warn("[FieldValidator] Rejected submitted value: exceeds maximum number");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value above maximum (" + constraints.maxNumber() + ").",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    private static void validateDateBound(
            String fieldName,
            String value,
            String bound,
            FormDataParser.FieldInfo fieldInfo,
            boolean minBound
    ) throws FormDataParser.ParseException {
        try {
            if (fieldInfo.dateField()) {
                validateLocalDateBound(fieldName, value, bound, minBound);
                return;
            }
            if (fieldInfo.datetimeLocalField()) {
                validateDateTimeLocalBound(fieldName, value, bound, minBound);
            }
        } catch (DateTimeParseException e) {
            // Type-specific validation already handles malformed submitted values.
        }
    }

    private static void validateLocalDateBound(String fieldName, String value, String bound, boolean minBound)
            throws FormDataParser.ParseException {
        LocalDate submitted = LocalDate.parse(value);
        LocalDate limit = LocalDate.parse(bound);
        if (isOutOfBounds(submitted, limit, minBound)) {
            log.warn("[FieldValidator] Rejected submitted value: date outside configured bounds");
            throw boundViolation(fieldName, "date", minBound);
        }
    }

    private static void validateDateTimeLocalBound(String fieldName, String value, String bound, boolean minBound)
            throws FormDataParser.ParseException {
        LocalDateTime submitted = LocalDateTime.parse(value, DATETIME_LOCAL_FMT);
        LocalDateTime limit = LocalDateTime.parse(bound, DATETIME_LOCAL_FMT);
        if (isOutOfBounds(submitted, limit, minBound)) {
            log.warn("[FieldValidator] Rejected submitted value: datetime outside configured bounds");
            throw boundViolation(fieldName, "datetime", minBound);
        }
    }

    private static <T extends Comparable<? super T>> boolean isOutOfBounds(T submitted, T limit, boolean minBound) {
        return minBound ? submitted.compareTo(limit) < 0 : submitted.compareTo(limit) > 0;
    }

    private static FormDataParser.ParseException boundViolation(String fieldName, String kind, boolean minBound) {
        return new FormDataParser.ParseException(
                "Field '" + fieldName + "': " + kind + " is "
                        + (minBound ? "before minimum" : "after maximum") + ".",
                FormDataParser.ParseException.FailureType.VALIDATION
        );
    }

    private static void validateEmail(String fieldName, String value) throws FormDataParser.ParseException {
        String[] parts = value.contains(",") ? value.split(",") : new String[]{value};
        for (String part : parts) {
            String email = part.trim();
            if (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches()) {
                log.warn("[FieldValidator] Rejected submitted value: invalid email format");
                throw new FormDataParser.ParseException(
                        "Field '" + fieldName + "': invalid email format.",
                        FormDataParser.ParseException.FailureType.VALIDATION
                );
            }
        }
    }

    private static void validateDate(String fieldName, String value) throws FormDataParser.ParseException {
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("[FieldValidator] Rejected submitted value: invalid date format");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': invalid date format (expected yyyy-MM-dd).",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    private static void validateDatetimeLocal(String fieldName, String value) throws FormDataParser.ParseException {
        try {
            LocalDateTime.parse(value, DATETIME_LOCAL_FMT);
        } catch (DateTimeParseException e) {
            log.warn("[FieldValidator] Rejected submitted value: invalid datetime-local format");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': invalid datetime format.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    private static void validateColor(String fieldName, String value) throws FormDataParser.ParseException {
        if (!COLOR_PATTERN.matcher(value).matches()) {
            log.warn("[FieldValidator] Rejected submitted value: invalid color format");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': invalid color format (expected #rrggbb).",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    // Number fields (fmdbmix:numberField, e.g. rating/scale) render constrained native
    // controls, but that is browser-side only: a forged submission can carry anything.
    private static void validateNumber(String fieldName, String value) throws FormDataParser.ParseException {
        parseFiniteNumber(fieldName, value);
    }

    private static double parseFiniteNumber(String fieldName, String value) throws FormDataParser.ParseException {
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number)) {
                throw new NumberFormatException("not finite");
            }
            return number;
        } catch (NumberFormatException e) {
            log.warn("[FieldValidator] Rejected submitted value: not a number");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value is not a number.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }

    // Boolean fields (fmdbmix:booleanField, e.g. switch/consent) submit their state as
    // "true" (lone checkbox / on radio) or "false" (explicit off radio). "on" is also
    // accepted — it is what a plain checkbox without a value attribute submits, and the
    // evaluators treat it as on — so third-party boolean fields are not rejected here.
    private static void validateBoolean(String fieldName, String value) throws FormDataParser.ParseException {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value) && !"on".equalsIgnoreCase(value)) {
            log.warn("[FieldValidator] Rejected submitted value: not a boolean");
            throw new FormDataParser.ParseException(
                    "Field '" + fieldName + "': value is not a boolean.",
                    FormDataParser.ParseException.FailureType.VALIDATION
            );
        }
    }
}
