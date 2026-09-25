import { AddResources, buildModuleFileUrl } from "@jahia/javascript-modules-library";
import { useTranslation } from "react-i18next";
import { validationDataAttributes, type BaseValidationMessageProps } from "@jahia/formidable-library";
import { HelpText, helpTextId } from "@jahia/formidable-library";
import "~/design/edit-warning.css";
import "./scale.css";

export interface ScaleProps extends BaseValidationMessageProps {
  "jcr:title"?: string;
  "helpText"?: string;
  "minValue"?: number;
  "maxValue"?: number;
  "step"?: number;
  "minLabel"?: string;
  "maxLabel"?: string;
  "required"?: boolean;
}

export interface ScaleRenderInput {
  props: ScaleProps;
  inputName: string;
  nodeId: string;
  // True when rendering in edit mode: configuration warnings are shown to the contributor
  editMode?: boolean;
  // Set by the nps view to force the standard NPS presentation
  forced?: {
    min: number;
    max: number;
    step: number;
    defaultMinLabel: string;
    defaultMaxLabel: string;
  };
}

const MAX_ITEMS = 21;

// Configured JCR numbers arrive as string | number | undefined; fall back only
// when the property is absent or not a number, so an explicit 0 is honored.
const configuredNumber = (value: unknown, fallback: number): number => {
  if (value === undefined || value === null || value === "") return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

export default function ScaleField({ props, inputName, nodeId, editMode, forced }: ScaleRenderInput) {
  const { t } = useTranslation("formidable-extended-inputs", { keyPrefix: "fmdbext_scale" });
  const { "jcr:title": label, helpText, minLabel, maxLabel, required, ...rest } = props;
  const { minValue, maxValue, step, ...validationMsgs } = rest;
  const vAttrs = validationDataAttributes(validationMsgs);
  const helpId = helpText ? helpTextId(nodeId) : undefined;

  const min = forced ? forced.min : configuredNumber(minValue, 0);
  const rawMax = forced ? forced.max : configuredNumber(maxValue, 10);
  const inc = forced ? forced.step : Math.max(configuredNumber(step, 1), 1);
  const max = Math.max(rawMax, min + inc);

  const values: number[] = [];
  for (let v = min; v <= max && values.length < MAX_ITEMS; v += inc) {
    values.push(v);
  }

  // Both adjustments are silent for visitors; tell the contributor in edit mode.
  const lastValue = values[values.length - 1];
  const configWarnings: string[] = [];
  if (editMode && !forced) {
    if (lastValue + inc <= max) {
      configWarnings.push(t("configTruncated", { max: MAX_ITEMS }));
    } else if (lastValue !== max) {
      configWarnings.push(t("configMaxUnreachable", { configured: max, step: inc, effective: lastValue }));
    }
  }

  const startLabel = minLabel || forced?.defaultMinLabel;
  const endLabel = maxLabel || forced?.defaultMaxLabel;

  return (
    <>
      <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
      {/* radiogroup: the explicit role a fieldset of radios needs to carry aria-readonly, which the
          jExperience prefill sets when the author locks the field */}
      <fieldset className="fmdb-form-group fmdbext-scale" role="radiogroup" aria-describedby={helpId}>
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
        {/* Shared shrink-wrapped body: the end labels span exactly the chip row's width */}
        <div className="fmdbext-scale-body">
          <div className="fmdbext-scale-items">
            {values.map((value) => {
              const inputId = `scale-${nodeId}-${value}`;
              return (
                <span key={value} className="fmdbext-scale-item">
                  <input
                    type="radio"
                    id={inputId}
                    name={inputName}
                    className="fmdb-form-control fmdbext-scale-input"
                    value={value}
                    required={required}
                    {...vAttrs}
                  />
                  <label htmlFor={inputId} className="fmdbext-scale-label">
                    {value}
                  </label>
                </span>
              );
            })}
          </div>
          {(startLabel || endLabel) && (
            <div className="fmdbext-end-labels">
              <span>{startLabel}</span>
              <span>{endLabel}</span>
            </div>
          )}
        </div>
        {configWarnings.map((warning) => (
          <span key={warning} className="fmdbext-edit-warning">
            {warning}
          </span>
        ))}
      </fieldset>
    </>
  );
}
