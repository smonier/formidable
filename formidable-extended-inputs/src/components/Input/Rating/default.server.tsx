import {
  AddResources,
  buildModuleFileUrl,
  jahiaComponent,
} from "@jahia/javascript-modules-library";
import { useTranslation } from "react-i18next";
import { type BaseValidationMessageProps, validationDataAttributes } from "@jahia/formidable-library";
import { HelpText, helpTextId } from "@jahia/formidable-library";
import "~/design/edit-warning.css";
import "./rating.css";

interface RatingProps extends BaseValidationMessageProps {
  "jcr:title"?: string;
  "helpText"?: string;
  "icon"?: string;
  "maxValue"?: number;
  "minLabel"?: string;
  "maxLabel"?: string;
  "required"?: boolean;
}

const MAX_ITEMS = 10;

jahiaComponent(
  {
    componentType: "view",
    nodeType: "fmdbext:rating",
    name: "default",
  },
  (
    {
      "jcr:title": label,
      helpText,
      icon = "star",
      maxValue,
      minLabel,
      maxLabel,
      required,
      ...validationMsgs
    }: RatingProps,
    { currentNode, renderContext },
  ) => {
    const { t } = useTranslation("formidable-extended-inputs", { keyPrefix: "fmdbext_rating" });
    const inputName = currentNode.getName();
    const nodeId = currentNode.getIdentifier();
    const vAttrs = validationDataAttributes(validationMsgs);
    const helpId = helpText ? helpTextId(nodeId) : undefined;

    const configuredMax = Number(maxValue) || 5;
    const max = Math.min(Math.max(configuredMax, 2), MAX_ITEMS);
    // The clamp is silent for visitors; tell the contributor in edit mode.
    const clampWarning = renderContext.isEditMode() && configuredMax !== max
      ? t("configClamped", { configured: configuredMax, max: MAX_ITEMS, effective: max })
      : undefined;
    // Natural 1..max DOM order so keyboard arrows follow the visual direction;
    // the CSS fill-up effect selects preceding siblings with :has(~ :checked).
    const values = Array.from({ length: max }, (_, i) => i + 1);

    return (
      <>
        <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
        {/* radiogroup: the explicit role a fieldset of radios needs to carry aria-readonly, which the
            jExperience prefill sets when the author locks the field */}
        <fieldset className="fmdb-form-group fmdbext-rating" role="radiogroup" aria-describedby={helpId}>
          {label && (
            <legend className="fmdb-group-legend">
              {label}
              {required && (
                <span className="fmdb-required-indicator" aria-hidden="true">
                  *
                </span>
              )}
            </legend>
          )}
          <HelpText id={helpId} text={helpText} />
          {/* Shared shrink-wrapped body: the end labels span exactly the icon row's width */}
          <div className="fmdbext-rating-body">
            <div className="fmdbext-rating-items" data-fmdbext-icon={icon}>
              {values.map((value) => {
                const inputId = `rating-${nodeId}-${value}`;
                return (
                  <span key={value} className="fmdbext-rating-item">
                    <input
                      type="radio"
                      id={inputId}
                      name={inputName}
                      className="fmdb-form-control fmdbext-rating-input"
                      value={value}
                      required={required}
                      aria-label={`${value}/${max}`}
                      {...vAttrs}
                    />
                    <label htmlFor={inputId} className="fmdbext-rating-label" aria-hidden="true">
                      {icon === "number" ? value : null}
                    </label>
                  </span>
                );
              })}
            </div>
            {(minLabel || maxLabel) && (
              <div className="fmdbext-end-labels" aria-hidden="true">
                <span>{minLabel}</span>
                <span>{maxLabel}</span>
              </div>
            )}
          </div>
          {clampWarning && <span className="fmdbext-edit-warning">{clampWarning}</span>}
        </fieldset>
      </>
    );
  },
);
