package org.jahia.modules.formidable.engine.servlet;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.util.Streams;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;
import org.jahia.modules.formidable.engine.config.FormidableConfigService;
import org.jahia.services.content.JCRContentUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses multipart/form-data from an HttpServletRequest, applying security controls:
 *
 *   1. Content-Type validation (must be multipart/form-data)
 *   2. Request and per-file size limits; file count limit (from config)
 *   3. Field whitelist — undeclared fields skipped before any read
 *   4. Text field validation — choice match, format (email/date/color), constraints (required/min/max/length/pattern)
 *   5. Filename sanitization (path components removed; JCR-reserved characters normalized)
 *   6. MIME type detection via Apache Tika, filename-aware (content still wins over the extension hint)
 *   7. MIME type allowlist check — per-field 'accept' takes priority over global cfg fallback
 *
 * Plain-text fields are validated on input and preserved as submitted. XSS protection is
 * enforced by escaping at each output sink (see {@link FieldEscaper}), never by mutating input.
 */
public class FormDataParser {

    private static final Logger log = LoggerFactory.getLogger(FormDataParser.class);

    private static final Tika TIKA = new Tika();

    /**
     * Server-side constraints for a single form field, collected from JCR before parsing.
     * Values of -1 (numeric) or null (string) mean "not set — skip this check".
     *
     * @param required   field must be present and non-blank
     * @param minLength  minimum character count (-1 = unconstrained)
     * @param maxLength  maximum character count (-1 = unconstrained)
     * @param pattern    full-match regex (like HTML pattern attribute); null = unconstrained
     * @param minDate    ISO-8601 lower bound for date/datetime-local fields; null = unconstrained
     * @param maxDate    ISO-8601 upper bound for date/datetime-local fields; null = unconstrained
     * @param minNumber  numeric lower bound for number fields; null = unconstrained
     * @param maxNumber  numeric upper bound for number fields; null = unconstrained
     */
    public record FieldConstraints(
            boolean required,
            long minLength,
            long maxLength,
            String pattern,
            String minDate,
            String maxDate,
            Double minNumber,
            Double maxNumber
    ) {
        public FieldConstraints(
                boolean required, long minLength, long maxLength,
                String pattern, String minDate, String maxDate
        ) {
            this(required, minLength, maxLength, pattern, minDate, maxDate, null, null);
        }
    }

    /**
     * Shared per-field metadata used by the parser and collected upstream from JCR.
     *
     * @param nodeType            JCR primary node type (e.g. "fmdb:inputEmail")
     * @param nonSubmittable      true when the node must never be accepted from submitted form data
     * @param choiceField         true when submitted text values must match a declared choice set
     * @param fileField           true when this field accepts uploaded files rather than plain text
     * @param emailField          true when the submitted text value must match the email validator
     * @param dateField           true when the submitted text value must match yyyy-MM-dd
     * @param datetimeLocalField  true when the submitted text value must match HTML datetime-local
     * @param colorField          true when the submitted text value must match #RRGGBB
     * @param numberField         true when the submitted text value must be a finite number
     * @param booleanField        true when the submitted text value must be "true" or "false"
     * @param allowedChoices      allowed submitted values for choice/radio/select fields
     * @param choicesUnresolvable true when the field's options source could not deliver at
     *                            collection time: a non-empty submitted value cannot be
     *                            verified and must be rejected (D11)
     * @param acceptTypes         pre-resolved MIME type allowlist for file fields
     * @param constraints         server-side constraints collected from JCR
     */
    public record FieldInfo(
            String nodeType,
            boolean nonSubmittable,
            boolean choiceField,
            boolean fileField,
            boolean emailField,
            boolean dateField,
            boolean datetimeLocalField,
            boolean colorField,
            boolean numberField,
            boolean booleanField,
            Set<String> allowedChoices,
            boolean choicesUnresolvable,
            Set<String> acceptTypes,
            FieldConstraints constraints
    ) {
        public FieldInfo {
            allowedChoices = allowedChoices == null ? Set.of() : Set.copyOf(allowedChoices);
            acceptTypes = acceptTypes == null ? Set.of() : Set.copyOf(acceptTypes);
        }

        public FieldInfo(
                String nodeType, boolean nonSubmittable, boolean choiceField, boolean fileField,
                boolean emailField, boolean dateField, boolean datetimeLocalField, boolean colorField,
                boolean numberField, boolean booleanField,
                Set<String> allowedChoices, Set<String> acceptTypes, FieldConstraints constraints
        ) {
            this(nodeType, nonSubmittable, choiceField, fileField, emailField, dateField,
                    datetimeLocalField, colorField, numberField, booleanField,
                    allowedChoices, false, acceptTypes, constraints);
        }

        public FieldInfo(
                String nodeType, boolean nonSubmittable, boolean choiceField, boolean fileField,
                boolean emailField, boolean dateField, boolean datetimeLocalField, boolean colorField,
                Set<String> allowedChoices, Set<String> acceptTypes, FieldConstraints constraints
        ) {
            this(nodeType, nonSubmittable, choiceField, fileField, emailField, dateField,
                    datetimeLocalField, colorField, false, false, allowedChoices, false, acceptTypes, constraints);
        }
    }

    /**
     * All per-field JCR-derived metadata needed for parsing and validation.
     *
     * @param fieldInfos fieldName → all parser-relevant field metadata
     */
    public record FieldMetadata(
            Map<String, FieldInfo> fieldInfos
    ) {
        public FieldMetadata {
            fieldInfos = fieldInfos == null ? Map.of() : Map.copyOf(fieldInfos);
        }

        public Set<String> allowedNames() {
            return fieldInfos.keySet();
        }

        public FieldInfo field(String fieldName) {
            return fieldInfos.get(fieldName);
        }

        public Set<String> allowedChoices(String fieldName) {
            FieldInfo fieldInfo = field(fieldName);
            return fieldInfo != null ? fieldInfo.allowedChoices() : Set.of();
        }

        public boolean choicesUnresolvable(String fieldName) {
            FieldInfo fieldInfo = field(fieldName);
            return fieldInfo != null && fieldInfo.choicesUnresolvable();
        }

        public Set<String> acceptTypes(String fieldName) {
            FieldInfo fieldInfo = field(fieldName);
            return fieldInfo != null ? fieldInfo.acceptTypes() : Set.of();
        }

        public FieldConstraints constraints(String fieldName) {
            FieldInfo fieldInfo = field(fieldName);
            return fieldInfo != null ? fieldInfo.constraints() : null;
        }
    }

    /**
     * Parsed representation of a single uploaded file.
     *
     * @param fieldName    the form field name
     * @param originalName sanitized original filename
     * @param mimeType     detected MIME type (via filename-aware Tika detection)
     * @param data         file bytes
     */
    public record FormFile(
            String fieldName,
            String originalName,
            String mimeType,
            byte[] data
    ) {
        public FormFile {
            data = data == null ? new byte[0] : data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        /** File size in bytes, without cloning the array the way {@link #data()} must. */
        public int size() {
            return data.length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FormFile that)) {
                return false;
            }
            return Objects.equals(fieldName, that.fieldName)
                    && Objects.equals(originalName, that.originalName)
                    && Objects.equals(mimeType, that.mimeType)
                    && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(fieldName, originalName, mimeType);
            result = 31 * result + Arrays.hashCode(data);
            return result;
        }

        @Override
        public String toString() {
            return "FormFile[fieldName=" + fieldName
                    + ", originalName=" + originalName
                    + ", mimeType=" + mimeType
                    + ", data=<" + data.length + " bytes>]";
        }
    }

    /**
     * Result of a full multipart parse: both text parameters and uploaded files.
     */
    public record ParseResult(
            Map<String, List<String>> parameters,
            List<FormFile> files
    ) {}

    public static class ParseException extends Exception {
        public enum FailureType {
            VALIDATION,
            TECHNICAL,
            CONFIGURATION
        }

        private final FailureType failureType;

        public ParseException(String message, FailureType failureType) {
            super(message);
            this.failureType = failureType;
        }

        public ParseException(String message, FailureType failureType, Throwable cause) {
            super(message, cause);
            this.failureType = failureType;
        }

        public FailureType failureType() {
            return failureType;
        }
    }


    /**
     * Parses the full multipart request: both text fields and file parts.
     * Use this from a servlet that is the first consumer of the request stream.
     *
     * @param req           the HTTP request (stream not yet consumed)
     * @param config        Formidable global config (size limits, fallback MIME allowlist)
     * @param fieldMetadata per-field JCR metadata (whitelist, types, choices, accept types, constraints)
     * @return ParseResult containing all text parameters and validated file entries
     * @throws ParseException if any security or validation check fails
     */
    public static ParseResult parseAll(
            HttpServletRequest req,
            FormidableConfigService config,
            FieldMetadata fieldMetadata
    ) throws ParseException {

        if (!ServletFileUpload.isMultipartContent(req)) {
            return new ParseResult(new HashMap<>(), List.of());
        }

        Map<String, List<String>> parameters = new HashMap<>();
        List<FormFile> files = new ArrayList<>();

        try {
            ServletFileUpload upload = new ServletFileUpload();
            upload.setFileSizeMax(config.getUploadMaxFileSizeBytes());
            upload.setSizeMax(config.getUploadMaxRequestSizeBytes());
            upload.setFileCountMax(config.getUploadMaxFileCount());
            FileItemIterator iterator = upload.getItemIterator(req);
            while (iterator.hasNext()) {
                parsePart(iterator.next(), fieldMetadata, config, parameters, files);
            }
        } catch (ParseException e) {
            throw e;
        } catch (org.apache.commons.fileupload.FileUploadBase.FileSizeLimitExceededException e) {
            throw new ParseException(
                    "File too large. Max allowed: " + config.getUploadMaxFileSizeBytes() + " bytes.",
                    ParseException.FailureType.TECHNICAL
            );
        } catch (org.apache.commons.fileupload.FileUploadBase.SizeLimitExceededException e) {
            throw new ParseException(
                    "Request too large. Max allowed: " + config.getUploadMaxRequestSizeBytes() + " bytes.",
                    ParseException.FailureType.TECHNICAL
            );
        } catch (Exception e) {
            throw new ParseException(
                    "Failed to parse multipart request while reading submitted fields and files.",
                    ParseException.FailureType.TECHNICAL,
                    e
            );
        }

        return new ParseResult(parameters, files);
    }

    private static boolean isFileField(String fieldName, FieldMetadata meta) {
        FieldInfo fieldInfo = meta.field(fieldName);
        return fieldInfo != null && fieldInfo.fileField();
    }

    private static void validateTextField(String fieldName, String value, FieldMetadata meta)
            throws ParseException {
        FieldValidator.validateTextField(fieldName, value, meta);
    }

    /**
     * One multipart part. Whitelist check first: fields not declared on the form node
     * are discarded without reading their content (the iterator advances past them
     * automatically), and the whitelist FAILS CLOSED — an empty one (a form with no
     * submittable field) discards every part, otherwise anything posted would skip
     * validation entirely and save2jcr would persist it through fmdb:submissionData's
     * residual properties. The part kind must then match the field kind: a text part
     * under a file field's name would bypass that field's files-only contract, and a
     * file part under a text field's name would bypass every text check — choice
     * allowlist included — and land in the file store of a form that declares no file
     * field at all.
     */
    private static void parsePart(FileItemStream item, FieldMetadata fieldMetadata, FormidableConfigService config,
            Map<String, List<String>> parameters, List<FormFile> files) throws ParseException, java.io.IOException {
        if (!fieldMetadata.allowedNames().contains(item.getFieldName())) {
            log.debug("[FormDataParser] Skipping undeclared field: {}", item.getFieldName());
            return;
        }

        if (!item.isFormField()) {
            if (!isFileField(item.getFieldName(), fieldMetadata)) {
                throw new ParseException("Field '" + item.getFieldName() + "': unexpected file upload.",
                        ParseException.FailureType.VALIDATION);
            }

            FormFile file = parseFilePart(item, fieldMetadata.acceptTypes(item.getFieldName()), config);
            if (file != null) {
                files.add(file);
            }

            return;
        }

        if (isFileField(item.getFieldName(), fieldMetadata)) {
            throw new ParseException("Field '" + item.getFieldName() + "': expected a file, got a text value.",
                    ParseException.FailureType.VALIDATION);
        }

        String value;
        try (InputStream inputStream = item.openStream()) {
            value = Streams.asString(inputStream, StandardCharsets.UTF_8.name());
        }
        validateTextField(item.getFieldName(), value, fieldMetadata);
        parameters.computeIfAbsent(item.getFieldName(), k -> new ArrayList<>()).add(value);
    }

    private static FormFile parseFilePart(
            FileItemStream item,
            Set<String> fieldAllowedTypes,
            FormidableConfigService config
    ) throws ParseException {
        String fieldName     = item.getFieldName();
        String sanitizedName = sanitizeFilename(item.getName());

        byte[] data;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = item.openStream()) {
                in.transferTo(buf);
            }
            data = buf.toByteArray();
        } catch (Exception e) {
            throw new ParseException(
                    "Failed to read uploaded file part for field '" + fieldName + "' from multipart request.",
                    ParseException.FailureType.TECHNICAL,
                    e
            );
        }

        if (data.length == 0) {
            log.debug("[FormDataParser] Skipping empty uploaded file part");
            return null;
        }

        // Use filename-aware detection so Tika can disambiguate common cases where content-only
        // detection is too generic, such as CSV, Matroska/WebM containers, and other formats
        // whose final MIME type benefits from the original extension hint.
        String detectedMime = TIKA.detect(data, sanitizedName);
        // Field-level allowlist (pre-resolved at collection time); falls back to global config
        Set<String> allowed = (fieldAllowedTypes != null && !fieldAllowedTypes.isEmpty())
                ? fieldAllowedTypes
                : config.getUploadAllowedMimeTypes();

        if (!allowed.isEmpty() && !isMimeAllowed(detectedMime, allowed)) {
            log.warn("[FormDataParser] Rejected uploaded file: detected MIME type is not in the configured allowlist");
            throw new ParseException(
                    "File '" + sanitizedName + "': type '" + detectedMime + "' is not allowed.",
                    ParseException.FailureType.VALIDATION
            );
        }

        log.debug("[FormDataParser] Accepted uploaded file part (size={} bytes)", data.length);
        return new FormFile(fieldName, sanitizedName, detectedMime, data);
    }

    /**
     * Normalizes the uploaded filename according to Jahia's standard JCR node-name escaping rules.
     * Returns "upload" if the input is null/blank or if the escaped name becomes blank.
     */
    static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        String safe = JCRContentUtils.escapeLocalNodeName(FilenameUtils.getName(raw));
        return (safe == null || safe.isBlank()) ? "upload" : safe;
    }


    /**
     * Resolves a single accept token to a MIME type.
     * Extensions like ".pdf" are normalised via Tika; MIME types are returned as-is.
     */
    public static String resolveAcceptToken(String token) {
        if (token.startsWith(".")) {
            String detected = TIKA.detect("file" + token);
            return detected != null ? detected : token;
        }
        return token;
    }

    /**
     * Checks whether the detected MIME type is permitted by the allowlist.
     * Supports wildcards (e.g. "image/*").
     */
    static boolean isMimeAllowed(String detectedMime, Set<String> allowed) {
        if (allowed.contains(detectedMime)) return true;
        String prefix = detectedMime.contains("/") ? detectedMime.substring(0, detectedMime.indexOf('/')) : "";
        return !prefix.isEmpty() && allowed.contains(prefix + "/*");
    }

}
