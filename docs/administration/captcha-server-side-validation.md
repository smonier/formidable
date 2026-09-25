# CAPTCHA Server-side Validation

The captcha integration covers both the front-end widget (rendering + token collection) and server-side token verification.

## Configuration

CAPTCHA is configured globally via `org.jahia.modules.formidable.cfg` (or Felix Web Console). No JCR node is needed.

| Property | Description |
|---|---|
| `captchaSiteKey` | Public key — used by the front-end widget |
| `captchaScriptUrl` | Provider JS API URL — injected in the page to render the widget |
| `captchaWidgetVar` | Name of the global `window` object exposed by the provider script (e.g. `turnstile`, `hcaptcha`, `grecaptcha`) |
| `captchaTokenField` | Name of the hidden form field auto-injected by the widget (e.g. `cf-turnstile-response`) |
| `captchaVerifyUrl` | Provider siteverify endpoint — used for server-side verification |
| `captchaSecretKey` | Secret key — used for server-side verification, never exposed to the client |
| `captchaHttpConnectTimeoutSeconds` | Server-side connect timeout to the provider. Also used client-side as the maximum time to wait for the provider script to expose the widget API before giving up rendering (some scripts, like Google's `api.js`, expose `render` asynchronously after a second script loads) |
| `captchaHttpRequestTimeoutSeconds` | Server-side total timeout for the verification request |

To enable CAPTCHA on a form, apply the `fmdbmix:captcha` mixin to the `fmdb:form` node.
This author-facing mixin extends `fmdbmix:captchaProtectedForm`.

---

## When is the token verified server-side?

When CAPTCHA is enabled on the form, the submission pipeline verifies the token before
any byte of the body is read and before any action runs. The only checks ahead of it are
the zero-read gates (content type, routing params, size guard, form resolution,
read-only maintenance, authentication): a submission rejected by one of those — FMDB-014
during maintenance, for instance — is refused without the token ever being verified.

---

## How verification works

`FormSubmissionPipeline.verifyCaptcha(...)` runs in the submission pipeline. It:

1. Checks that the form carries `fmdbmix:captchaProtectedForm`
2. Reads the `X-Formidable-Captcha-Token` request header
3. Calls `FormidableConfigService.verifyCaptcha(token, remoteAddr)`
4. `FormidableConfigService` reads `captchaSecretKey` and `captchaVerifyUrl` from OSGi config
5. It POSTs to `captchaVerifyUrl` with `secret` + `response` + optional `remoteip`
6. If the provider rejects the submitted token, the pipeline stops with `FMDB-006` / HTTP 400
7. If server-side verification cannot complete because of a technical failure
   (for example timeout, DNS failure, network error, or provider outage), the
   pipeline stops with `FMDB-500` / HTTP 500

## Token field names (auto-injected by the widget)

| Provider | Widget's native hidden field |
|---|---|
| Cloudflare Turnstile | `cf-turnstile-response` |
| hCaptcha | `h-captcha-response` |
| Google reCAPTCHA v2 | `g-recaptcha-response` |

The token does NOT travel in the POST body: the submit hook reads it, deletes the
widget's native field from `FormData`, and sends it in the `X-Formidable-Captcha-Token`
header — the field name above is only what the module configures as `captchaTokenField`
so the hook knows which field to strip.

## Verification endpoints

| Provider | Endpoint |
|---|---|
| Cloudflare Turnstile | `https://challenges.cloudflare.com/turnstile/v0/siteverify` |
| hCaptcha | `https://hcaptcha.com/siteverify` |
| Google reCAPTCHA v2 | `https://www.google.com/recaptcha/api/siteverify` |

All three share the same request shape (`secret` + `response` + optional `remoteip`) and the same `{ "success": boolean }` response.

---

## Error codes (Turnstile)

| Code | Meaning |
|---|---|
| `missing-input-secret` | Secret key not provided |
| `invalid-input-secret` | Secret key is invalid |
| `missing-input-response` | Token not provided |
| `invalid-input-response` | Token is malformed or expired |
| `timeout-or-duplicate` | Token already used or expired (300 s limit) |
