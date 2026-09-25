import type { ComponentProps } from "react";
import { useMask } from "@jahia/formidable-library";

interface MaskedTextInputProps {
  mask: string;
  defaultValue?: string;
  /** All input attributes are computed server-side and must stay serialisable (no handlers). */
  inputAttributes: ComponentProps<"input">;
}

/**
 * The text input hydrated when a mask is configured — the island Formidable's own view has, built
 * on the same hook: the value is formatted on every input event and the caret stays where the user
 * is editing. The server pre-formats the default value and derives the HTML `pattern`, so native
 * validation keeps working without JavaScript.
 */
export default function MaskedTextInput({
  mask,
  defaultValue,
  inputAttributes,
}: MaskedTextInputProps) {
  const { inputRef, handleInput } = useMask({ mask });

  return (
    <input {...inputAttributes} ref={inputRef} defaultValue={defaultValue} onInput={handleInput} />
  );
}
