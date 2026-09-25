// The markup contract and the input-mask behaviour of Formidable forms, for
// every module that renders a form field: the two form modules of this monorepo
// through the yarn workspace (which reads these sources), modules outside it
// through the published `@jahia/formidable-library` package, whose tarball ships dist/
// (#308).
//
// - `HelpText` / `helpTextId`: the help block (`div.fmdb-form-help` with id
//   `help-<nodeId>`, referenced by the control's `aria-describedby`), and its
//   decorative repeat;
// - `validationDataAttributes`: the `data-fmdb-msg-*` attributes the validation
//   client reads its custom messages from;
// - `maskToPattern`, `applyMask`, `useMask`: what an input mask stands for — the
//   HTML `pattern`, the formatted default value, the formatting while typing.
//
// Admission criterion — this package is not a grab bag: only code a field view
// must reproduce byte for byte to stay compatible with Formidable's client
// (markup contracts, attribute names), or a behaviour a view outside the
// monorepo could not otherwise reach (the live mask), belongs here.
// Single-module helpers stay in their module, and the pieces the hook is built
// on (mask tokens, raw-value and caret arithmetic) stay inside this package:
// every export below is a permanent commitment to the modules built against the
// published package — keep README.md in step.
//
// Specifiers carry the `.js` extension: tsc copies them through untouched, and
// Node's ESM resolver needs it in dist/ (the build's `nodenext` setting errors
// on a missing one).
export {HelpText, helpTextId, type HelpTextProps} from "./HelpText.js";
export {
	validationDataAttributes,
	type BaseValidationMessageProps,
	type TextValidationMessageProps,
	type RangeValidationMessageProps
} from "./validationProps.js";
export {applyMask, maskToPattern} from "./mask.js";
export {useMask, type UseMaskOptions} from "./useMask.js";
