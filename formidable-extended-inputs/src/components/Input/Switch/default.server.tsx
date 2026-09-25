import {
  AddResources,
  buildModuleFileUrl,
  jahiaComponent,
} from "@jahia/javascript-modules-library";
import { useTranslation } from "react-i18next";
import { type BaseValidationMessageProps, validationDataAttributes } from "@jahia/formidable-library";
import { HelpText, helpTextId } from "@jahia/formidable-library";
import "./switch.css";

interface SwitchProps extends BaseValidationMessageProps {
  "jcr:title"?: string;
  "helpText"?: string;
  "displayMode"?: string;
  "onLabel"?: string;
  "offLabel"?: string;
  "defaultState"?: boolean;
  "required"?: boolean;
}

jahiaComponent(
  {
    componentType: "view",
    nodeType: "fmdbext:switch",
    name: "default",
  },
  (
    {
      "jcr:title": label,
      helpText,
      displayMode = "toggle",
      onLabel,
      offLabel,
      defaultState,
      required,
      ...validationMsgs
    }: SwitchProps,
    { currentNode },
  ) => {
    const { t } = useTranslation("formidable-extended-inputs", { keyPrefix: "fmdbext_switch" });
    const inputName = currentNode.getName();
    const nodeId = currentNode.getIdentifier();
    const vAttrs = validationDataAttributes(validationMsgs);
    const helpId = helpText ? helpTextId(nodeId) : undefined;
    // The CND autocreates onLabel/offLabel from the resource bundle, but an i18n
    // property can still be empty in a given language — fall back to the current
    // locale's bundle value rather than a hardcoded English string.
    const onText = onLabel || t("defaultOnLabel");
    const offText = offLabel || t("defaultOffLabel");

    if (displayMode === "buttons") {
      const onId = `switch-${nodeId}-on`;
      const offId = `switch-${nodeId}-off`;
      return (
        <>
          <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
          {/* aria-label fallback: without a title the fieldset has no legend, and the
              group would otherwise have no accessible name. radiogroup: the explicit role
              a fieldset of radios needs to carry aria-readonly, which the jExperience
              prefill sets when the author locks the field */}
          <fieldset
            className="fmdb-form-group fmdbext-switch"
            role="radiogroup"
            aria-describedby={helpId}
            aria-label={label ? undefined : inputName}
          >
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
            <div className="fmdbext-switch-buttons">
              <span className="fmdbext-switch-button">
                <input
                  type="radio"
                  id={onId}
                  name={inputName}
                  className="fmdb-form-control fmdbext-switch-input"
                  value="true"
                  defaultChecked={defaultState === true}
                  required={required}
                  {...vAttrs}
                />
                <label htmlFor={onId} className="fmdbext-switch-button-label">
                  {onText}
                </label>
              </span>
              {/* Never precheck OFF: a stored defaultState=false (any editor save writes it)
                  must keep the field unanswered, so 'required' still means "pick one" and
                  results can tell "answered no" from "not answered". */}
              <span className="fmdbext-switch-button">
                <input
                  type="radio"
                  id={offId}
                  name={inputName}
                  className="fmdb-form-control fmdbext-switch-input"
                  value="false"
                  required={required}
                  {...vAttrs}
                />
                <label htmlFor={offId} className="fmdbext-switch-button-label">
                  {offText}
                </label>
              </span>
            </div>
          </fieldset>
        </>
      );
    }

    const inputId = `switch-${nodeId}`;
    return (
      <>
        <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
        <div className="fmdb-form-group fmdbext-switch">
          <span className="fmdbext-switch-toggle-wrapper">
            <input
              type="checkbox"
              id={inputId}
              name={inputName}
              className="fmdb-form-control fmdbext-switch-input"
              value="true"
              defaultChecked={defaultState}
              required={required}
              aria-describedby={helpId}
              // aria-label fallback: the visible label text is {label}; when empty, the
              // state texts are aria-hidden and the checkbox would have no accessible name
              aria-label={label ? undefined : inputName}
              {...vAttrs}
            />
            <label htmlFor={inputId} className="fmdbext-switch-toggle-label">
              <span className="fmdbext-switch-track" aria-hidden="true" />
              <span className="fmdbext-switch-text">
                {label}
                {required && (
                  <span className="fmdb-required-indicator" aria-hidden="true">
                    *
                  </span>
                )}
              </span>
              <span className="fmdbext-switch-state" aria-hidden="true">
                <span className="fmdbext-switch-state-on">{onText}</span>
                <span className="fmdbext-switch-state-off">{offText}</span>
              </span>
            </label>
          </span>
          <HelpText id={helpId} text={helpText} />
        </div>
      </>
    );
  },
);
