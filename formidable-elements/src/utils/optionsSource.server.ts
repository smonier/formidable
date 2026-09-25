import {server} from "@jahia/javascript-modules-library";
import type {JCRNodeWrapper} from "org.jahia.services.content";
import type {RenderContext} from "org.jahia.services.render";
import {parseChoices, type ParsedChoice} from "./choiceUtils";

const OPTIONS_SOURCE_SERVICE = "org.jahia.modules.formidable.engine.options.FormidableOptionsSourceService";
const MANUAL_OPTIONS_DISPLAY_SERVICE = "org.jahia.modules.formidable.engine.options.ManualOptionsDisplayService";
// Mixins whose options are resolved by the engine at render time; the actual mode
// dispatch lives in FormidableOptionsSourceService.resolveForField.
const RESOLVED_OPTIONS_MIXINS = ["fmdbmix:sourcedOptions", "fmdbmix:categoryOptions", "fmdbmix:contentOptions"];

export interface FieldOptions {
	choices: ParsedChoice[];
	// True when the field is in sourced mode and its source could not deliver
	// (unknown key, initializer missing or failing). The field must render an
	// error instead of an empty option list (D10).
	sourceError: boolean;
}

/** A Java String[] crossing the GraalVM bridge, as a JS array. */
const toStringArray = (javaArray: {length: number}): string[] => {
	const values: string[] = [];
	for (let i = 0; i < javaArray.length; i++) {
		values.push(String((javaArray as unknown as string[])[i]));
	}

	return values;
};

/**
 * The manual options to render: the site default language's values, order and default
 * selections, with this language's own labels.
 *
 * The stored list of the rendered language is not the identity. Values belong to the
 * default language and the submission validation reads them from there, while
 * publication is per language — so live can hold this translation at an older
 * generation and rendering it verbatim would offer values the server rejects as
 * forged. The engine owns the alignment rule; a failure to reach it falls back to the
 * stored list, which renders something rather than nothing.
 *
 * The alignment reads the site's "Replace untranslated content" setting, so the
 * fragment depends on the site node: the setting is saved in edit and auto-published,
 * and that live change is what flushes the fragment.
 */
const alignManualOptions = (currentNode: JCRNodeWrapper, manualOptions: string[], renderContext: RenderContext): string[] => {
	try {
		const site = currentNode.getResolveSite();
		if (site) {
			server.render.addCacheDependency({node: site}, renderContext);
		}

		const service = server.osgi.getService(MANUAL_OPTIONS_DISPLAY_SERVICE);
		const aligned = service.forDisplay(currentNode, currentNode.getLanguage());

		return aligned === null || aligned === undefined ? manualOptions : toStringArray(aligned);
	} catch (error) {
		console.error(`[Formidable] Could not align the manual options of field ${currentNode.getPath()}`, error);
		return manualOptions;
	}
};

// The root a category- or content-mode field reads its options under, by mixin.
const SOURCE_ROOT_PROPERTIES = ["optionsRootCategory", "optionsRootNode"];

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Category and content options are read live under a picked root, so the fragment
 * depends on that whole subtree: a category or content published, renamed or removed
 * under it must flush the field. Declared as a path regexp — the cache matches
 * every modified node path against it. An unreadable root is left to the resolution,
 * which reports it as a failing source.
 */
const declareSourceRootDependency = (currentNode: JCRNodeWrapper, renderContext: RenderContext): void => {
	for (const property of SOURCE_ROOT_PROPERTIES) {
		try {
			if (currentNode.hasProperty(property)) {
				const rootPath = currentNode.getProperty(property).getNode().getPath();
				server.render.addCacheDependency({flushOnPathMatchingRegexp: `${escapeRegExp(rootPath)}(/.*)?`}, renderContext);
			}
		} catch {
			// Resolution below reports the unreadable root.
		}
	}
};

/**
 * Resolves the option list of a choice field at render time.
 *
 * Manual mode renders the stored options values, realigned on the default
 * language's identity (see alignManualOptions). Sourced mode asks the engine's
 * options-source resolver (in-process OSGi call) for the current language; the
 * resolver answers in the manual-options JSON format, so both modes feed the same
 * parsing and rendering path.
 */
export const resolveFieldOptions = (currentNode: JCRNodeWrapper, manualOptions: string[], renderContext: RenderContext): FieldOptions => {
	try {
		if (!RESOLVED_OPTIONS_MIXINS.some(mixin => currentNode.isNodeType(mixin))) {
			return {choices: parseChoices(alignManualOptions(currentNode, manualOptions, renderContext)), sourceError: false};
		}
	} catch (error) {
		console.error(`[Formidable] Could not read the options mode of field ${currentNode.getPath()}`, error);
		return {choices: [], sourceError: true};
	}

	declareSourceRootDependency(currentNode, renderContext);

	try {
		const service = server.osgi.getService(OPTIONS_SOURCE_SERVICE);
		const resolved = service.resolveForField(currentNode, currentNode.getLanguage());
		if (resolved === null || resolved === undefined) {
			return {choices: parseChoices(manualOptions), sourceError: false};
		}

		return {choices: parseChoices(toStringArray(resolved)), sourceError: false};
	} catch (error) {
		console.error(`[Formidable] Options source failed for field ${currentNode.getPath()}`, error);
		return {choices: [], sourceError: true};
	}
};
