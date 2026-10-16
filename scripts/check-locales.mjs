// Every translation says what the English says, key for key. Usage: node check-locales.mjs [repoRoot]
//
// i18next locale files: in every `locales/` folder holding an `en.json` (the front-end modules' `settings/locales/`,
// the engine's `javascript/locales/`), each other `<language>.json` carries exactly the keys of `en.json`, in the same
// order, and each value the same `{{placeholders}}`. A key missing from one language renders as the raw key for its
// visitors, an extra one is a key no view reads, and a translated or dropped placeholder shows `{{count}}` or loses the
// value. The plural suffixes (`_one`, `_other`) are keys like the others: the languages shipped so far (en, fr, pt) use
// the same two forms, so the parity holds for them too.
//
// Resource bundles: in every `resources/` folder, each `<bundle>_<language>.properties` holds only keys of the base
// `<bundle>.properties`, with the same `${name}` and `{0}` placeholders (tooltips excepted: their `${...}` are
// example field names, translated with the sentence). A translated bundle may hold fewer keys than
// the base, on purpose: the Portuguese bundles carry the texts a visitor reads and leave the editor labels to the base
// bundle (docs/administration/languages.md). A key the base does not have is a typo, or a key renamed in English only.
import {readdirSync, readFileSync, statSync} from 'node:fs';
import {basename, dirname, join, relative, resolve} from 'node:path';

const root = resolve(process.argv[2] ?? '.');
const SKIPPED_DIRS = new Set(['node_modules', 'target', 'dist', '.git', '.yarn', 'apps']);

const walk = dir => readdirSync(dir).flatMap(name => {
    if (SKIPPED_DIRS.has(name)) return [];
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
});
const files = walk(root);
const errors = [];

// --- i18next locale files
const flatten = (object, prefix = '') => Object.entries(object).flatMap(([key, value]) =>
    value !== null && typeof value === 'object' ? flatten(value, `${prefix}${key}.`) : [[`${prefix}${key}`, value]]);
const jsonPlaceholders = value => [...String(value).matchAll(/\{\{\s*([^}\s]+)\s*\}\}/g)].map(match => match[1]).sort().join(',');

let localeFiles = 0;
for (const english of files.filter(file => basename(file) === 'en.json' && basename(dirname(file)) === 'locales')) {
    const reference = flatten(JSON.parse(readFileSync(english, 'utf-8')));
    const referenceKeys = reference.map(([key]) => key);
    const referenceValues = new Map(reference);
    for (const name of readdirSync(dirname(english)).filter(name => name.endsWith('.json') && name !== 'en.json')) {
        localeFiles++;
        const file = join(dirname(english), name);
        const translation = flatten(JSON.parse(readFileSync(file, 'utf-8')));
        const keys = translation.map(([key]) => key);
        const where = relative(root, file);
        const missing = referenceKeys.filter(key => !keys.includes(key));
        const extra = keys.filter(key => !referenceValues.has(key));
        missing.forEach(key => errors.push(`${where}: missing key ${key}`));
        extra.forEach(key => errors.push(`${where}: key ${key} is not in en.json`));
        if (missing.length === 0 && extra.length === 0 && keys.join('\n') !== referenceKeys.join('\n')) {
            const first = keys.findIndex((key, index) => key !== referenceKeys[index]);
            errors.push(`${where}: keys out of the order of en.json from ${keys[first]} (en.json has ${referenceKeys[first]} there)`);
        }
        for (const [key, value] of translation) {
            if (referenceValues.has(key) && jsonPlaceholders(value) !== jsonPlaceholders(referenceValues.get(key))) {
                errors.push(`${where}: ${key} carries {{${jsonPlaceholders(value)}}}, en.json {{${jsonPlaceholders(referenceValues.get(key))}}}`);
            }
        }
    }
}

// --- Resource bundles
const LANGUAGE_SUFFIX = /_([a-z]{2}(?:_[A-Z]{2})?)$/;
// key → raw value; a continuation line (a value ending with an odd number of backslashes) joins the next one.
const readBundle = file => {
    const entries = new Map();
    const lines = readFileSync(file, 'utf-8').split(/\r?\n/);
    for (let index = 0; index < lines.length; index++) {
        let line = lines[index].trimStart();
        if (line === '' || line.startsWith('#') || line.startsWith('!')) continue;
        while (/(^|[^\\])(\\\\)*\\$/.test(line) && index + 1 < lines.length) {
            line = line.slice(0, -1) + lines[++index].trimStart();
        }
        const match = line.match(/^((?:\\.|[^\s=:\\])+)\s*[=:]?\s*(.*)$/);
        if (match) entries.set(match[1].replace(/\\(.)/g, '$1'), match[2]);
    }
    return entries;
};
const bundlePlaceholders = value => [...value.matchAll(/\$\{[^}]+\}|\{\d+\}/g)].map(match => match[0]).sort().join(',');

let bundleFiles = 0;
for (const file of files.filter(file => file.endsWith('.properties') && basename(dirname(file)) === 'resources')) {
    const name = basename(file, '.properties');
    if (!LANGUAGE_SUFFIX.test(name)) continue;
    const base = join(dirname(file), `${name.replace(LANGUAGE_SUFFIX, '')}.properties`);
    const where = relative(root, file);
    let reference;
    try {
        reference = readBundle(base);
    } catch {
        errors.push(`${where}: no base bundle ${relative(root, base)}`);
        continue;
    }
    bundleFiles++;
    for (const [key, value] of readBundle(file)) {
        if (!reference.has(key)) {
            errors.push(`${where}: key ${key} is not in ${basename(base)}`);
        } else if (!key.endsWith('.ui.tooltip') && bundlePlaceholders(value) !== bundlePlaceholders(reference.get(key))) {
            // A tooltip's ${...} is an example field name the editor reads in its language (${prenom}), not a placeholder.
            errors.push(`${where}: ${key} carries [${bundlePlaceholders(value)}], ${basename(base)} [${bundlePlaceholders(reference.get(key))}]`);
        }
    }
}

if (errors.length > 0) {
    console.error(errors.join('\n'));
    process.exit(1);
}
console.log(`ok: ${localeFiles} locale files match their en.json, ${bundleFiles} translated resource bundles hold only keys of their base bundle`);
