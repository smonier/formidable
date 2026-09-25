// The engine's CND and the constants it exports say the same thing, nothing else in Java spells one
// of those names out, and every use of a constant is qualified. Usage: node check-nodetype-names.mjs [repoRoot]
//
// Three checks, the first two from one reading of formidable-engine/src/main/resources/META-INF/definitions.cnd:
//
//  1. Parity. Every node type and mixin the engine declares has a constant in FmdbNodeType
//     or FmdbMixin, and every constant there is declared in that file. A mixin is how
//     another module opts into engine behaviour, so all of them are contract — which is why this
//     direction is enforced too, and why a new one cannot be added without exporting its name.
//     Properties and child nodes are checked one way only, each holder against its own kind — FmdbProperty
//     against the `-` declarations, FmdbNodeName against the `+` ones: a property is local to the type that
//     declares it until something outside reads it, so the two hold the ones that crossed.
//  2. No second spelling. An engine-declared name appears in the MAIN sources only in those classes —
//     anywhere else the constant exists and the literal is a copy that no rename would follow. Test
//     sources are out of scope on purpose: a test that writes the name out is how a constant holding
//     the WRONG declared name gets caught, which parity alone cannot do — see FieldShapesTest, which
//     writes its mixin names out for exactly that reason. A migration
//     is exempt: it is a frozen script that must keep naming the vocabulary of the release it
//     heals, not follow the live one. A name another module declares (fmdb:form and the concrete
//     field types, from formidable-elements) has no constant to use, and is counted, not refused.
//     A property or child node name carries no namespace, so it is looked for where alone it can be told
//     apart: at a JCR call site (`hasProperty("options")`, `getNode("submissions")`) in the same sources.
//  3. Qualified use. The holder names the kind and the constant names the thing, so a static import of
//     a holder, member or wildcard, is refused, in main and test sources alike: `FILE_FIELD` alone no longer
//     says what it is, and FmdbNodeType.SUBMISSIONS and FmdbNodeName.SUBMISSIONS would collide.
//
// See docs/architecture/cnd-module-ownership.md, "Naming these types from Java".
import {readFileSync, readdirSync, statSync} from 'node:fs';
import {join, relative, resolve, sep} from 'node:path';

const root = resolve(process.argv[2] ?? '.');
const CND = 'formidable-engine/src/main/resources/META-INF/definitions.cnd';
const API = 'formidable-engine/src/main/java/org/jahia/modules/formidable/engine/api';
// Stamped by a 0.4 content migration to record that it has already healed a node. The declarations
// outlive the migrations (docs/administration/upgrade-notes.md) so that marked content stays valid,
// but the names are the engine talking to itself: engine-internal, in migration/MigrationMarker.java.
const TRANSITIONAL = new Set(['fmdbmix:elementsReactivated', 'fmdbmix:migratedChoiceOptions']);

const cnd = readFileSync(join(root, CND), 'utf8');
const declared = {type: new Set(), mixin: new Set(), property: new Set(), childNode: new Set()};
for (const line of cnd.split('\n')) {
    const type = line.match(/^\[([\w]+:[\w]+)]/);
    if (type) declared[/\bmixin\b/.test(line) ? 'mixin' : 'type'].add(type[1]);
    // '-' declares a property, '+' a child node: each kind has its own holder, and one name may be both
    const item = line.match(/^\s*([-+])\s+([\w:]+)\s*\(/);
    if (item) declared[item[1] === '+' ? 'childNode' : 'property'].add(item[2]);
}

// A commented-out declaration declares nothing: read the code, as the compiler does.
const uncommented = text => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
const constantsOf = file => {
    const out = new Map();
    for (const m of uncommented(readFileSync(join(root, API, file), 'utf8')).matchAll(/public static final String (\w+) = "([^"]+)";/g)) {
        out.set(m[1], m[2]);
    }
    return out;
};
const exported = {
    type: constantsOf('FmdbNodeType.java'),
    mixin: constantsOf('FmdbMixin.java'),
    property: constantsOf('FmdbProperty.java'),
    childNode: constantsOf('FmdbNodeName.java'),
};

const errors = [];
for (const kind of ['type', 'mixin']) {
    for (const name of declared[kind]) {
        if (!TRANSITIONAL.has(name) && ![...exported[kind].values()].includes(name)) {
            errors.push(`${CND}: ${name} has no constant in Fmdb${kind === 'type' ? 'NodeType' : 'Mixin'}`);
        }
    }
}
for (const [kind, klass] of [['type', 'FmdbNodeType'], ['mixin', 'FmdbMixin'], ['property', 'FmdbProperty'], ['childNode', 'FmdbNodeName']]) {
    for (const [constant, value] of exported[kind]) {
        if (!declared[kind].has(value)) errors.push(`${klass}.${constant}: ${value} is not declared in ${CND}`);
    }
}

const engineOwned = new Set([...declared.type, ...declared.mixin]);
// The exported property and child node names: no namespace, so only a JCR call site tells one apart from a word.
const exportedItems = new Set([...exported.property.values(), ...exported.childNode.values()]);
const jcrCall = /\.(?:getNode|hasNode|addNode|getNodes|getProperty|hasProperty|setProperty|getPropertyAsString)\(\s*"([^"]+)"/g;
const SKIP = new Set(['node_modules', 'target', 'dist', '.git', '.yarn', 'results']);
const walk = dir => readdirSync(dir).flatMap(name => {
    if (SKIP.has(name)) return [];
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
});
const javaSources = walk(root).filter(f => f.endsWith('.java'));
const sources = javaSources.filter(f => f.includes('/src/main/java/'));
// Which module declares each name of the repository, so that a literal can be told apart: a name
// another module owns is the residue a marker would remove, a name the spelling module declares
// itself is not residue at all, and a name nothing declares is a 0.4 spelling a migration still reads.
const moduleOf = path => relative(root, path).split(sep)[0];
const declaredBy = new Map();
for (const file of walk(root).filter(f => f.endsWith('.cnd'))) {
    for (const m of readFileSync(file, 'utf8').matchAll(/^\[([\w]+:[\w]+)]/gm)) declaredBy.set(m[1], moduleOf(file));
}
const allowed = f => f.startsWith(join(root, API)) || f.includes('/migration/');
const elsewhere = {owed: 0, own: 0, legacy: 0};
for (const file of sources) {
    // A name quoted in a comment documents; only code can spell a second one.
    const code = uncommented(readFileSync(file, 'utf8'));
    for (const m of code.matchAll(/"(fmdb\w*:\w+)"/g)) {
        if (!engineOwned.has(m[1])) {
            // A migration is exempt from the rule, so its literals are not residue either: counting them
            // would report as "owed a marker" the one case where naming the old vocabulary is the point.
            const owner = declaredBy.get(m[1]);
            elsewhere[owner === undefined || allowed(file) ? 'legacy' : owner === moduleOf(file) ? 'own' : 'owed']++;
        } else if (!allowed(file)) {
            errors.push(`${relative(root, file)}: ${m[1]} has a constant, use it`);
        }
    }
    if (!allowed(file)) {
        for (const m of code.matchAll(jcrCall)) {
            if (exportedItems.has(m[1])) errors.push(`${relative(root, file)}: "${m[1]}" has a constant, use it`);
        }
    }
}

// A holder is used qualified, never statically imported: the class carries the kind the constant dropped.
const staticImport = /^import static org\.jahia\.modules\.formidable\.engine\.(api\.Fmdb\w+|migration\.MigrationMarker)\.(?:\w+|\*);/gm;
for (const file of javaSources) {
    for (const m of readFileSync(file, 'utf8').matchAll(staticImport)) {
        errors.push(`${relative(root, file)}: static import of ${m[1]} — qualify the use, the class names the kind`);
    }
}

if (errors.length > 0) {
    console.error(errors.join('\n'));
    process.exit(1);
}
console.log(`ok: ${declared.type.size} node types and ${declared.mixin.size} mixins declared and exported,`
    + ` ${sources.length} Java sources hold no second spelling, ${javaSources.length} use the holders qualified.`
    + ` Literals naming what this CND does not declare: ${elsewhere.owed} owed a marker — live code naming`
    + ` another module's type — plus ${elsewhere.own} a module spells of its own and ${elsewhere.legacy} inside a migration.`);
