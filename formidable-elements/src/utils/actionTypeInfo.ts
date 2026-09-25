import {server} from "@jahia/javascript-modules-library";
import {tooltipText} from "./tooltipText";
import type {JCRNodeWrapper} from "org.jahia.services.content";
import type {RenderContext} from "org.jahia.services.render";
import type {Locale} from "java.util";

/** What the authoring zone shows about an action's TYPE: label, description, icon. */
export interface ActionTypeInfo {
	/** Node type name, e.g. fmdb:emailNotificationAction. */
	name: string;
	/** Type label in the editor's UI language (the Content Editor's name for the type). */
	label: string;
	/** Type-level tooltip of the Content Editor (`<type>.ui.tooltip`), plain text, if declared. */
	description?: string;
	/** URL of the type icon the Content Editor shows: the type's own, else a supertype's. */
	iconUrl?: string;
}

const ACTION_SUMMARY_SERVICE = "org.jahia.modules.formidable.engine.actions.common.ActionSummaryService";

// The engine's service, reached over OSGi; its records come back with accessor methods.
interface ActionSummaryServiceLike {
	describeType(node: JCRNodeWrapper, locale: Locale): {
		name(): string;
		label(): string;
		description(): string | null;
		iconUrl(): string;
	};
	keyParameter(node: JCRNodeWrapper, locale: Locale): {name(): string; value(): string} | null;
}

const summaryService = (): ActionSummaryServiceLike =>
	server.osgi.getService(ACTION_SUMMARY_SERVICE) as ActionSummaryServiceLike;

// The Java side of the node type: the library types only the javax.jcr surface, and
// not even getPrimaryNodeType() on the node.
const primaryTypeName = (node: JCRNodeWrapper): string =>
	(node as unknown as {getPrimaryNodeType(): {getName(): string}}).getPrimaryNodeType().getName();

// Editor texts — type labels, tooltips, choice labels — follow the UI language, not the content
// language, as every label of the Content Editor does; the accessor falls back to the content
// locale when the user set no preference.
const uiLocale = (renderContext: RenderContext): Locale => renderContext.getUILocale();

/**
 * Describes the primary type of an action node from what its module declares for the Content
 * Editor — the type label, the type-level tooltip, the type icon — resolved by the engine through
 * the platform's own resource bundle chain and icon lookup: a site rewording a label, a bundle
 * named as the module declares it, whatever its encoding, a locale's fallbacks, an icon inherited
 * from a supertype. Nothing is duplicated in this module — a third-party action documented for the
 * editor is documented for the zone. Without the engine, the bare type name stands in.
 */
export const describeActionType = (node: JCRNodeWrapper, renderContext: RenderContext): ActionTypeInfo => {
	const name = primaryTypeName(node);
	try {
		const type = summaryService().describeType(node, uiLocale(renderContext));
		const tooltip = type.description();
		return {
			name: String(type.name()),
			label: String(type.label()),
			description: tooltip ? tooltipText(String(tooltip)) || undefined : undefined,
			// The platform hands the icon path without its extension, as it serves it to jContent.
			iconUrl: `${String(type.iconUrl())}.png`,
		};
	} catch (error) {
		console.warn(`[Formidable] Could not describe the type of action ${node.getPath()}`, error);
		return {name, label: name.replace(/^.*:/, "")};
	}
};

/**
 * URL of the icon the Content Editor shows for a node's primary type — the type's own or, failing
 * that, a supertype's — resolved by the engine through the platform's icon lookup, so the zone
 * and jContent draw the same glyph. Undefined when the engine is unreachable.
 */
export const nodeTypeIconUrl = (node: JCRNodeWrapper, renderContext: RenderContext): string | undefined => {
	try {
		return `${String(summaryService().describeType(node, uiLocale(renderContext)).iconUrl())}.png`;
	} catch (error) {
		console.warn(`[Formidable] Could not resolve the type icon of ${node.getPath()}`, error);
		return undefined;
	}
};

/**
 * The one parameter shown next to an action's title: the first small text or choice its type
 * declares after the title (recipient, forward target...), a choice shown by its label. The
 * engine resolves it from the type declaration, so a third-party action needs no code here.
 */
export const actionKeyDetail = (node: JCRNodeWrapper, renderContext: RenderContext): string | undefined => {
	try {
		const parameter = summaryService().keyParameter(node, uiLocale(renderContext));
		return parameter ? String(parameter.value()) || undefined : undefined;
	} catch (error) {
		console.warn(`[Formidable] Could not read the key parameter of action ${node.getPath()}`, error);
		return undefined;
	}
};
