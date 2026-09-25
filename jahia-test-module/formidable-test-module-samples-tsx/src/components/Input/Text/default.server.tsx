import { Island, jahiaComponent } from "@jahia/javascript-modules-library";
import {
  applyMask,
  HelpText,
  helpTextId,
  maskToPattern,
  type TextValidationMessageProps,
  validationDataAttributes,
} from "@jahia/formidable-library";
import MaskedTextInput from "./Text.client";

/** The values of fmdbsamplemix:helpTextPosition; "up" is the built-in rendering. */
type HelpTextPosition = "up" | "down" | "both";

/**
 * The props of the built-in text input. The `msg*` props of fmdbmix:textValidationMessages come
 * from the library's type, so a message Formidable adds to the mixin reaches this view as a type
 * error to act on, not as a silent omission.
 */
interface InputTextProps extends TextValidationMessageProps {
  "jcr:title"?: string;
  "helpText"?: string;
  /** From fmdbsamplemix:helpTextPosition, absent until the node is saved with the mixin. */
  "helpTextPosition"?: string;
  "placeholder"?: string;
  "defaultValue"?: string;
  "list"?: string[];
  "minLength"?: number;
  "maxLength"?: number;
  "required"?: boolean;
  "autocomplete"?: string;
  // fmdbmix:advancedInputTextSettings
  "mask"?: string;
  "pattern"?: string;
  "readonly"?: boolean;
  "autofocus"?: boolean;
  "disabled"?: boolean;
  "form"?: string;
  "dirname"?: boolean;
  "spellcheck"?: boolean;
  "size"?: number;
  "title"?: string;
}

const DEFAULT_LIST: string[] = [];

const readPosition = (value: string | undefined): HelpTextPosition =>
  value === "down" || value === "both" ? value : "up";

/**
 * TAKES THE PLACE of Formidable's default view of the text input. Formidable registers its
 * `default` view of fmdb:inputText at the default priority (0); this one is registered under the
 * same name with a higher priority, so on every site where this module is enabled, every text input
 * renders through this file — nothing to pick in the View chooser, and the built-in view is no
 * longer reachable there.
 *
 * What it adds: the help text goes where the contributor put it (the sample mixin
 * fmdbsamplemix:helpTextPosition) — above the field, as Formidable renders it, below it, or in both
 * places. Taking a default view over means owning the whole built-in contract
 * (docs/extension/how-to-extend-views-and-elements-from-third-party-module.md): the field's name
 * and id, the fmdb-* hooks, one help block with the `help-<nodeId>` id the control references, the
 * data-fmdb-msg-* validation messages, and everything a mask stands for — the `pattern`, the
 * formatted default, the formatting while typing. None of it is written out here: the contract
 * comes from @jahia/formidable-library, the package Formidable's own views are built on, so this view stays
 * byte-compatible with them and a change of contract shows up as a type error when this module
 * builds. The live mask is Text.client.tsx, an island of this module built on the library's useMask
 * hook, hydrated only when a mask is configured — as in Formidable.
 */
jahiaComponent(
  {
    componentType: "view",
    nodeType: "fmdb:inputText",
    name: "default",
    // Above Formidable's own default view (priority 0): the highest priority wins.
    priority: 1,
  },
  (
    {
      "jcr:title": label,
      helpText,
      helpTextPosition,
      placeholder,
      defaultValue,
      list = DEFAULT_LIST,
      minLength,
      maxLength,
      required,
      autocomplete,
      mask,
      pattern,
      readonly,
      autofocus,
      disabled,
      form,
      dirname,
      spellcheck = true,
      size,
      title,
      ...validationMsgs
    }: InputTextProps,
    { currentNode },
  ) => {
    const position = readPosition(helpTextPosition);
    const nodeId = currentNode.getIdentifier();
    const inputId = `input-${nodeId}`;
    const inputName = currentNode.getName();
    const datalistId = list.length > 0 ? `datalist-${nodeId}` : undefined;
    // The one block assistive technology is pointed to, wherever it stands.
    const helpId = helpText ? helpTextId(nodeId) : undefined;
    const helpAbove = position !== "down";
    const helpBelow = position !== "up";

    // Shared between the static input and the masked island so both render identical markup
    const inputAttributes = {
      "type": "text",
      "id": inputId,
      "name": inputName,
      "aria-describedby": helpId,
      "className": "fmdb-form-control",
      placeholder,
      "list": datalistId,
      minLength,
      maxLength,
      // An explicit pattern wins; a mask alone stands for one the browser checks without JavaScript
      "pattern": pattern || maskToPattern(mask),
      "data-mask": mask,
      required,
      "autoComplete": autocomplete,
      "readOnly": readonly,
      "autoFocus": autofocus,
      disabled,
      form,
      // Submits the field's text direction as `{name}.dir`, like the built-in view
      "dirName": dirname ? `${inputName}.dir` : undefined,
      "spellCheck": spellcheck,
      size,
      title,
      ...validationDataAttributes(validationMsgs),
    };

    return (
      // The data attribute tells this rendering from Formidable's, whatever the position.
      <div className="fmdb-form-group" data-fmdbsample-help-position={position}>
        {label && (
          <label htmlFor={inputId} className="fmdb-form-label">
            {label}
            {required && (
              <span className="fmdb-required-indicator" aria-hidden="true">
                *
              </span>
            )}
          </label>
        )}

        {helpAbove && <HelpText id={helpId} text={helpText} />}

        {mask ? (
          // Hydrated only when a mask is configured; the default value is pre-formatted server-side
          <Island
            component={MaskedTextInput}
            props={{
              mask,
              defaultValue: defaultValue ? applyMask(defaultValue, mask) : undefined,
              inputAttributes,
            }}
          />
        ) : (
          <input {...inputAttributes} defaultValue={defaultValue} />
        )}

        {/* "down": the help block, with the id, follows the field. "both": the block after the
            field is the decorative repeat of the one the control already describes — no id (ids
            are unique), hidden from assistive technology, so a screen reader hears the help once. */}
        {helpBelow && <HelpText id={helpId} text={helpText} decorative={helpAbove} />}

        {list.length > 0 && (
          <datalist id={datalistId}>
            {list.map((option) => (
              <option key={option} value={option} />
            ))}
          </datalist>
        )}
      </div>
    );
  },
);
