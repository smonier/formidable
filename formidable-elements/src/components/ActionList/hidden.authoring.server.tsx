import {AddContentButtons, jahiaComponent, Render} from "@jahia/javascript-modules-library";
import {useTranslation} from "react-i18next";
import {nodeTypeIconUrl} from "~/utils/actionTypeInfo";

// The one glyph drawn here (Lucide-inspired), for the warning: no icon library. The header
// shows the list's own type icon instead (fmdb_actionList.png, the one jContent shows on
// the list's box and in the tree), so the zone and the list read as one thing.
const AlertIcon = () => (
	<svg className="fmdb-authoring-actions-glyph" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
		<path d="M12 3 2 20h20L12 3z"/>
		<line x1="12" y1="9" x2="12" y2="14"/>
		<line x1="12" y1="17" x2="12.01" y2="17"/>
	</svg>
);

/**
 * The form's actions, as a zone of the Page Builder (edit mode only — the form's default
 * view renders it inside the form, under the buttons). Actions run after the submission
 * and are otherwise invisible on a page: the zone lists them in their execution order
 * (the list is orderable, so the Page Builder's drag reorders the pipeline), calls out a
 * form that has none (its submissions are neither stored nor sent), and carries the
 * list's own create button — its module declares the accepted type to jContent, the
 * fmdbmix:formAction mixin: one "New Form Action" button, then the type chooser.
 * Authoring chrome, deliberately not styled like the form: it is not the visitor's form.
 */
jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:actionList",
		name: "hidden.authoring",
	},
	(_props, {currentNode, renderContext}) => {
		const {t} = useTranslation("formidable-elements", {keyPrefix: "fmdb_actionList"});
		// Authoring-only, whatever asks for the view: a live or preview request of it gets nothing.
		if (!renderContext.isEditMode()) {
			return null;
		}
		const actionNodes = Array.from(currentNode.getNodes()).filter((node) => node.isNodeType("fmdbmix:formAction"));
		const count = actionNodes.length;
		const iconUrl = nodeTypeIconUrl(currentNode, renderContext);

		return (
			<aside className="fmdb-authoring-actions" aria-label={t("heading", {count})}>
				<div className="fmdb-authoring-actions-header">
					<span className="fmdb-authoring-actions-title">
						{iconUrl && <img className="fmdb-authoring-actions-glyph" src={iconUrl} alt="" width={16} height={16}/>}
						{t("heading", {count})}
					</span>
					<span className="fmdb-authoring-actions-hint">{count > 0 ? t("inOrder") : t("notLive")}</span>
				</div>

				{count === 0 && (
					<p className="fmdb-authoring-actions-empty">
						<AlertIcon/>
						{t("empty")}
					</p>
				)}

				{/* An ordered list: the execution order is the meaning, so assistive technology gets it too. */}
				{count > 0 && (
					<ol className="fmdb-authoring-actions-list">
						{actionNodes.map((actionNode) => (
							<li key={actionNode.getIdentifier()} className="fmdb-authoring-actions-item">
								<Render node={actionNode} view="hidden.authoring"/>
							</li>
						))}
					</ol>
				)}

				<AddContentButtons/>
			</aside>
		);
	},
);
