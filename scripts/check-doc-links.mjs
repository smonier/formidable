// Checks every relative Markdown link (path and #anchor) in the repository's .md files, plus every
// `docs/<...>.md` path quoted anywhere (code comments, CND, workflows). Usage: node check-doc-links.mjs [repoRoot]
import {existsSync, readdirSync, readFileSync, statSync} from 'node:fs';
import {dirname, join, relative, resolve} from 'node:path';

const root = resolve(process.argv[2] ?? '.');
// .harness holds local working notes (git-ignored), never part of the repository.
// ISSUE_TEMPLATE: the issue templates are synced from Jahia/.github (#314), so their
// anchors are not ours to fix here.
const SKIP = new Set(['node_modules', 'target', 'dist', '.git', '.yarn', 'results', '.harness', 'ISSUE_TEMPLATE']);
const walk = dir => readdirSync(dir).flatMap(name => {
    if (SKIP.has(name)) return [];
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
});
const files = walk(root);
const markdown = files.filter(f => f.endsWith('.md'));
const withoutFences = text => text.replace(/```[\s\S]*?```/g, '');

// GitHub's heading slug: lower case, punctuation dropped, every space a hyphen, duplicates suffixed -1, -2…
const slug = heading => heading.toLowerCase().replace(/`/g, '').replace(/[^\p{L}\p{N} -]/gu, '').replace(/ /g, '-');
const anchorsOf = new Map();
const anchors = file => {
    if (!anchorsOf.has(file)) {
        const set = new Set();
        const seen = new Map();
        const text = withoutFences(readFileSync(file, 'utf8'));
        for (const m of text.matchAll(/^#{1,6}[ \t]+(.+?)[ \t]*#*[ \t]*$/gm)) {
            const s = slug(m[1]);
            const n = seen.get(s) ?? 0;
            seen.set(s, n + 1);
            set.add(n ? `${s}-${n}` : s);
        }
        for (const m of text.matchAll(/(?:id|name)="([^"]+)"/g)) set.add(m[1]);
        anchorsOf.set(file, set);
    }
    return anchorsOf.get(file);
};
const hasAnchor = (file, anchor) => anchors(file).has(anchor) || anchors(file).has(anchor.replace(/^user-content-/, ''));

const errors = [];
for (const file of markdown) {
    const text = withoutFences(readFileSync(file, 'utf8'));
    for (const m of text.matchAll(/\]\(([^)\s]+)\)/g)) {
        const target = m[1];
        if (/^(https?:|mailto:)/.test(target)) continue;
        if (target.startsWith('#')) {
            if (!hasAnchor(file, target.slice(1))) errors.push(`${relative(root, file)}: missing anchor ${target}`);
            continue;
        }
        const [path, anchor] = target.split('#');
        const abs = resolve(dirname(file), path);
        if (!existsSync(abs)) {
            errors.push(`${relative(root, file)}: broken link ${target}`);
        } else if (anchor && abs.endsWith('.md') && !hasAnchor(abs, anchor)) {
            errors.push(`${relative(root, file)}: missing anchor ${target}`);
        }
    }
}
// A quoted docs/ path resolves from the repository root, or from the quoting file's own folder (a module's docs/).
for (const file of files.filter(f => /\.(md|ts|tsx|java|cnd|yml|yaml|json|properties|mjs|js)$/.test(f))) {
    for (const m of readFileSync(file, 'utf8').matchAll(/(?<![\w/.-])docs\/[\w./-]+\.md/g)) {
        if (!existsSync(join(root, m[0])) && !existsSync(resolve(dirname(file), m[0]))) {
            errors.push(`${relative(root, file)}: quoted path does not exist: ${m[0]}`);
        }
    }
}
if (errors.length > 0) {
    console.error(errors.join('\n'));
    process.exit(1);
}
console.log(`ok: ${markdown.length} markdown files, every relative link and quoted docs/ path resolves`);
