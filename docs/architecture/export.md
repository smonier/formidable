# Export

The export feature allows users to download form submissions from the Form Results screen. It is designed to support multiple output formats through a simple plugin architecture.

## Architecture

```
FormResults/export/
├── index.ts                    Barrel re-export
├── ExportResultsDialog.tsx     UI dialog component
├── export.utils.ts             Shared utilities (filename, download)
└── formats/
    ├── index.ts                Barrel, exports available formats
    ├── ExportFormat.ts         Format interface
    └── csv.ts                  CSV format implementation
```

### ExportFormat interface

Each format implements the `ExportFormat` interface defined in `formats/ExportFormat.ts`:

```ts
interface ExportFormat {
    id: string;          // Unique identifier (e.g. "csv", "json")
    label: string;       // Display label in the UI (e.g. "CSV")
    extension: string;   // File extension without dot (e.g. "csv")
    mimeType: string;    // MIME type for the Blob (e.g. "text/csv;charset=utf-8;")
    buildContent: (
        submissions: SubmissionRow[],
        t: (key: string) => string,
        formFields: FormFields
    ) => string;         // Builds the file content from submissions
}
```

The `buildContent` function receives:
- `submissions` — an array of parsed submission rows with metadata, field values, and file references
- `t` — the i18n translation function, scoped to the `formidable-engine` namespace
- `formFields` — the form's declared fields, in FORM order, with their labels: this is
  what carries the human-readable headers and the column ordering. A format that ignores
  it compiles fine and silently loses both — always thread it through.

### ExportResultsDialog

The dialog component handles:
- Date range filtering (start date, end date, or all results)
- Batched GraphQL fetching of all matching submissions
- Delegating content generation to the active format
- Triggering the browser download

It does not contain any formatting logic.

### Shared utilities (`export.utils.ts`)

- `buildFilename(formResults, filters, extension)` — generates a sanitized download filename like `contact-form-20260501-20260507.csv`
- `downloadFile(filename, content, mimeType)` — creates a Blob and triggers a browser download
- `sanitizeFilenamePart(value)` — normalizes a string for safe use in filenames

## Adding a new format

1. Create a new file in `formats/` (CSV and JSON already ship — say `formats/ndjson.ts`):

```ts
import type {FormFields, SubmissionRow} from '../../FormResults.utils';
import type {ExportFormat} from './ExportFormat';

const buildNdjsonContent = (
    submissions: SubmissionRow[],
    _t: (key: string) => string,
    _formFields: FormFields
): string => {
    return submissions.map(row => JSON.stringify(row)).join('\n');
};

export const ndjsonFormat: ExportFormat = {
    id: 'ndjson',
    label: 'NDJSON',
    extension: 'ndjson',
    mimeType: 'application/x-ndjson;charset=utf-8;',
    buildContent: buildNdjsonContent
};
```

2. Register it in `formats/index.ts`:

```ts
export type {ExportFormat} from './ExportFormat';
export {csvFormat} from './csv';
export {jsonFormat} from './json';

import {csvFormat} from './csv';
import {jsonFormat} from './json';
import type {ExportFormat} from './ExportFormat';

export const exportFormats: ExportFormat[] = [csvFormat, jsonFormat];
```

3. That's it. The dialog already carries a multi-select format Dropdown fed by `exportFormats`: a registered format appears there with its label, and every selected format downloads as its own file.

## Data model

Each `SubmissionRow` contains:

| Field | Type | Description |
|---|---|---|
| `uuid` | `string` | JCR node identifier |
| `name` | `string` | JCR node name |
| `created` | `string` | ISO date of submission |
| `origin` | `string \| null` | Submission origin |
| `locale` | `string \| null` | Submission locale |
| `referer` | `string \| null` | HTTP referer, still persisted when present |
| `timeZone` | `string \| null` | The submitter's time zone (an IANA id such as `Europe/Paris`), when the browser declared one |
| `fieldValues` | `SubmissionFieldValue[]` | Submitted form field values |
| `files` | `SubmissionFile[]` | Uploaded file references with URLs |

Each `SubmissionFile` contains `fieldName`, `fileName`, `fileUrl`, `mimeType`, and `thumbnailUrl`.

## Field identification in exports

Both export formats include the field **name** (the JCR node name, stable technical identifier) and the **label** (the human-readable title set by the form author). This makes exported data both human-friendly and programmatically reliable: the label helps users understand what each column or field represents, while the name provides a stable key that does not change when labels are edited.

## CSV specifics

The CSV format (`formats/csv.ts`) produces:
- A header row with fixed metadata columns followed by dynamic field columns
- Field column headers use the format `Label (name)` when a label is available, or just `name` otherwise — this lets users read natural column names while still being able to identify the underlying field
- One data row per submission
- Multi-value fields joined with ` | `
- Files grouped by field name with absolute download URLs
- Proper CSV escaping (quotes, commas, newlines, carriage returns)
- Spreadsheet formula-injection neutralisation: a value starting with `=`, `+`, `-`, `@`
  (or a tab/CR variant) is prefixed with a single quote, per the OWASP recommendation

## JSON specifics

The JSON format (`formats/json.ts`) produces an array of submission objects. Each field entry in the `fields` object is keyed by the field name and contains:
- `name` — the JCR node name (always present), useful for consumers that iterate over the fields object values without access to the key
- `label` — the human-readable label (present only when set)
- `value` — a single string or an array of strings for multi-value fields

Files are grouped by field name with absolute download URLs, each entry containing `fileName`, `mimeType`, and `url`.
