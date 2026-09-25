// Proves the tarball `yarn npm publish` would upload: packs it, unpacks the archive under a
// scratch node_modules and imports the package by name, the way a module outside the monorepo
// does — through the exports map publishConfig rewrites to dist/, under Node's ESM resolver,
// which needs the `.js` extensions tsc never adds — then type-checks a consumer of it under
// nodenext, so the `types` target and every declaration it re-exports must resolve. (`yarn pack
// --dry-run` only lists files and proves none of this.) Run after `yarn build`.
import {execFileSync} from "node:child_process";
import {existsSync, mkdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {createRequire} from "node:module";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

const packageDir = dirname(dirname(fileURLToPath(import.meta.url)));
const scratch = join(packageDir, ".smoke");
// The package name drives the unpack path and the probes' imports: a rename cannot desync them.
const {name} = JSON.parse(readFileSync(join(packageDir, "package.json"), "utf8"));
const installed = join(scratch, "node_modules", ...name.split("/"));

const fail = (message) => {
	console.error(`smoke: ${message}`);
	process.exit(1);
};

rmSync(scratch, {recursive: true, force: true});
mkdirSync(installed, {recursive: true});

const tarball = join(scratch, "package.tgz");
execFileSync("yarn", ["pack", "--out", tarball], {cwd: packageDir, stdio: "inherit"});
execFileSync("tar", ["-xzf", tarball, "-C", installed, "--strip-components=1"]);

for (const file of ["package.json", "README.md", "LICENSE"]) {
	if (!existsSync(join(installed, file))) fail(`${file} is missing from the tarball`);
}
// The exports map of the packed manifest (publishConfig applied): every target must exist. A
// consumer's TypeScript would silently fall back from a dangling `types` to the `.d.ts` next to
// `default`, so this is checked here explicitly rather than left to the type probe below.
const manifest = JSON.parse(readFileSync(join(installed, "package.json"), "utf8"));
for (const [subpath, conditions] of Object.entries(manifest.exports ?? {})) {
	for (const [condition, target] of Object.entries(conditions)) {
		if (!existsSync(join(installed, target))) fail(`exports["${subpath}"].${condition} points at ${target}, which is not in the tarball`);
	}
}

// The scratch directory is a package of its own: without this manifest the probe would sit in the
// scope of packages/formidable/package.json, and Node's self-reference rule would resolve
// the package name to the workspace sources instead of the unpacked tarball. `react` still
// resolves by walking up to the workspace's node_modules.
writeFileSync(join(scratch, "package.json"), JSON.stringify({name: "smoke", private: true, type: "module"}));
const probe = join(scratch, "probe.mjs");
writeFileSync(probe, `
import {HelpText, helpTextId, validationDataAttributes, maskToPattern, applyMask, useMask} from "${name}";

const checks = {
	helpTextId: helpTextId("n1") === "help-n1",
	HelpText: typeof HelpText === "function",
	validationDataAttributes: validationDataAttributes({msgValueMissing: "x"})["data-fmdb-msg-value-missing"] === "x",
	maskToPattern: maskToPattern("AA-9999") === "^[A-Za-z][A-Za-z]-[0-9][0-9][0-9][0-9]$",
	applyMask: applyMask("ab1234", "AA-9999") === "AB-1234",
	useMask: typeof useMask === "function"
};
const failed = Object.entries(checks).filter(([, ok]) => !ok).map(([name]) => name);
if (failed.length > 0) {
	console.error("smoke: " + failed.join(", ") + " failed");
	process.exit(1);
}
console.log("smoke: ${name} imports from its packed tarball under Node ESM, " + Object.keys(checks).length + " checks pass");
`);
execFileSync(process.execPath, [probe], {stdio: "inherit"});

// The declarations, as a consumer's tsc resolves them: a dangling `types` target or a missing
// `.d.ts` behind a re-export is an error here, not something the first user of the package finds.
const typeProbe = join(scratch, "probe.ts");
writeFileSync(typeProbe, `
import {HelpText, helpTextId, validationDataAttributes, maskToPattern, applyMask, useMask} from "${name}";
import type {HelpTextProps, TextValidationMessageProps, UseMaskOptions} from "${name}";

const props: HelpTextProps = {id: helpTextId("n1"), text: "help", decorative: false};
const messages: TextValidationMessageProps = {msgValueMissing: "required"};
const options: UseMaskOptions = {mask: "AA-9999"};
const pattern: string | undefined = maskToPattern(options.mask);
const formatted: string = applyMask("ab1234", "AA-9999");
const attributes: Record<string, string | undefined> = validationDataAttributes(messages);
export const probe = {HelpText, useMask, props, pattern, formatted, attributes};
`);
const tsc = createRequire(import.meta.url).resolve("typescript/bin/tsc");
execFileSync(process.execPath, [tsc, "--noEmit", "--strict", "--skipLibCheck", "false", "--module", "nodenext", "--moduleResolution", "nodenext", "--target", "es2022", "--jsx", "react-jsx", typeProbe], {stdio: "inherit"});
console.log("smoke: the declarations of the packed tarball type-check for a nodenext consumer");

rmSync(scratch, {recursive: true, force: true});
