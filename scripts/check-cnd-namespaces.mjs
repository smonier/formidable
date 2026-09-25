// Checks that every CND namespace prefix is bound to ONE URI across the whole repository.
// Usage: node check-cnd-namespaces.mjs [repoRoot]
//
// A prefix is global to the platform, not to the module that declares it: two modules binding the
// same prefix to different URIs do not each get their own namespace, one of them simply loses. The
// types of the loser then fail to register with a NoSuchNodeTypeException naming the winner's URI,
// far from the module that caused it — which is how a sample module's mixin disappeared once, and
// only the integration run said so, fifteen minutes later.
import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';

const root = resolve(process.argv[2] ?? '.');
const SKIP = new Set(['node_modules', 'target', 'dist', '.git', '.yarn', 'results', '.harness']);
const walk = dir => readdirSync(dir).flatMap(name => {
    if (SKIP.has(name)) return [];
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
});

const bindings = new Map(); // prefix -> Map(uri -> [files])
for (const file of walk(root).filter(f => f.endsWith('.cnd'))) {
    for (const line of readFileSync(file, 'utf8').split('\n')) {
        const match = /^<(\w+)\s*=\s*'([^']+)'>/.exec(line.trim());
        if (!match) continue;
        const [, prefix, uri] = match;
        if (!bindings.has(prefix)) bindings.set(prefix, new Map());
        const uris = bindings.get(prefix);
        if (!uris.has(uri)) uris.set(uri, []);
        uris.get(uri).push(relative(root, file));
    }
}

const conflicts = [...bindings].filter(([, uris]) => uris.size > 1);
for (const [prefix, uris] of conflicts) {
    console.error(`error: the prefix '${prefix}' is bound to ${uris.size} different URIs`);
    for (const [uri, files] of uris) {
        console.error(`  ${uri}`);
        for (const file of [...new Set(files)]) console.error(`    ${file}`);
    }
    console.error('  A prefix belongs to the platform: pick the URI the other module already uses, or a prefix of your own.');
}
if (conflicts.length > 0) process.exit(1);
console.log(`ok: ${bindings.size} CND namespace prefixes, each bound to one URI`);
