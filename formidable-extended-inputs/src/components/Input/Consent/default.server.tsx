import {
  AddResources,
  buildModuleFileUrl,
  buildNodeUrl,
  jahiaComponent,
} from "@jahia/javascript-modules-library";
import { useTranslation } from "react-i18next";
import { type BaseValidationMessageProps, validationDataAttributes } from "@jahia/formidable-library";
import { HelpText, helpTextId } from "@jahia/formidable-library";
import "~/design/edit-warning.css";
import "./consent.css";

interface ConsentProps extends BaseValidationMessageProps {
  "jcr:title"?: string;
  "statement"?: string;
  "helpText"?: string;
  "termsTarget"?: Parameters<typeof buildNodeUrl>[0];
  "termsLinkLabel"?: string;
  "required"?: boolean;
}

jahiaComponent(
  {
    componentType: "view",
    nodeType: "fmdbext:consent",
    name: "default",
  },
  (
    { statement, helpText, termsTarget, termsLinkLabel, required, ...validationMsgs }: ConsentProps,
    { currentNode, renderContext },
  ) => {
    const { t } = useTranslation("formidable-extended-inputs", { keyPrefix: "fmdbext_consent" });
    const inputName = currentNode.getName();
    const nodeId = currentNode.getIdentifier();
    const inputId = `consent-${nodeId}`;
    const vAttrs = validationDataAttributes(validationMsgs);
    const helpId = helpText ? helpTextId(nodeId) : undefined;
    // The property is set but its target is not resolvable in this workspace
    // (unpublished or not visible): the link is silently dropped for visitors,
    // so surface it to the contributor in edit mode.
    const termsTargetBroken =
      !termsTarget && renderContext.isEditMode() && currentNode.hasProperty("termsTarget");


    return (
      <>
        <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
        <div className="fmdb-form-group fmdbext-consent">
          <input
            type="checkbox"
            id={inputId}
            name={inputName}
            className="fmdb-form-control fmdbext-consent-input"
            value="true"
            required={required}
            aria-describedby={helpId}
            // aria-label fallback: statement is mandatory but only enforced on edited
            // locales — an untouched language would leave the checkbox unnamed
            aria-label={statement ? undefined : inputName}
            {...vAttrs}
          />
          <label htmlFor={inputId} className="fmdbext-consent-label">
            {statement && <span className="fmdbext-consent-statement">{statement}</span>}
            {required && (
              <span className="fmdb-required-indicator" aria-hidden="true">
                *
              </span>
            )}
          </label>
          {/* The terms link belongs to the consent statement: keep them together,
              with the generic help text after. */}
          {termsTarget && (
            <a
              className="fmdbext-consent-terms-link"
              href={buildNodeUrl(termsTarget)}
              target="_blank"
              rel="noopener noreferrer"
            >
              {termsLinkLabel || t("defaultTermsLinkLabel")}
            </a>
          )}
          <HelpText id={helpId} text={helpText} />
          {termsTargetBroken && (
            <span className="fmdbext-edit-warning">{t("termsTargetUnresolved")}</span>
          )}
        </div>
      </>
    );
  },
);
