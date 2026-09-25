import {AddResources, buildModuleFileUrl} from "@jahia/javascript-modules-library";
import {styleTagCss} from "~/utils/richTextUtils";
import type {JSX, ReactNode} from "react";

/**
 * The shell shared by the cm inspection views (the form's and the containers'): the form's
 * own CSS, the module stylesheet, and the fmdb-form wrapper a business stylesheet keys its
 * look on. Not a <form>: nothing in an inspection preview submits. data-fmdb-cm-view is the
 * hook this surface offers to CSS, next to edit mode's data-fmdb-edit-mode (docs/styling/class-hooks.md).
 */
export const CmShell = ({css, children}: {css?: string; children: ReactNode}): JSX.Element => (
	<>
		{css && <style dangerouslySetInnerHTML={{__html: styleTagCss(css)}}/>}
		<AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")}/>
		<div className="fmdb-form" data-fmdb-cm-view="true">{children}</div>
	</>
);
