import {jahiaComponent} from "@jahia/javascript-modules-library";
import {actionKeyDetail, describeActionType} from "~/utils/actionTypeInfo";

interface FormActionProps {
	"jcr:title"?: string;
}

/**
 * One action as a card of the authoring zone (see ActionList/hidden.authoring): its rank
 * (a CSS counter on the list), the type icon, the contributor's title with the action's
 * key parameter — the first small text or choice its type declares after the title
 * (recipient, forward target), a choice shown by its label — and
 * under it, smaller, the type's own description — the tooltip its module declares for
 * the Content Editor. A view on the mixin so every action type, third-party included,
 * gets the card; nothing here reaches live.
 *
 * Registered below the default priority so that a module shipping its own `hidden.authoring`
 * view for its action type wins for sure: the engine picks among the candidate views of a node
 * (its type's and its mixins') by priority first, then by module name — a card view left at the
 * default priority would only beat this one when its module's name sorts first.
 */
jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdbmix:formAction",
		name: "hidden.authoring",
		priority: -1,
	},
	({"jcr:title": title}: FormActionProps, {currentNode, renderContext}) => {
		// Authoring-only, whatever asks for the view: recipients and targets stay out of live and preview.
		if (!renderContext.isEditMode()) {
			return null;
		}
		const type = describeActionType(currentNode, renderContext);
		const detail = actionKeyDetail(currentNode, renderContext);

		return (
			<div className="fmdb-authoring-action" data-fmdb-action-type={type.name}>
				{/* Decorative: the title carries the meaning, so the alt is empty; no icon (the engine
				    unreachable) draws nothing rather than a broken image. */}
				{type.iconUrl && (
					<img className="fmdb-authoring-action-icon" src={type.iconUrl} alt="" width={16} height={16}/>
				)}
				<div className="fmdb-authoring-action-body">
					<div className="fmdb-authoring-action-line">
						<span className="fmdb-authoring-action-title">{title || type.label}</span>
						{detail && <span className="fmdb-authoring-action-detail">{detail}</span>}
					</div>
					{type.description && (
						<div className="fmdb-authoring-action-description" title={type.description}>
							{type.description}
						</div>
					)}
				</div>
			</div>
		);
	},
);
