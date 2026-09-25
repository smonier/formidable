/**
 * Header carrying the submitter's time zone at submit time — the browser's own zone, an IANA id
 * such as `Europe/Paris` — for the results to say where a typed date-time applies. Request
 * metadata travels in a header, never among the form's own fields, like the captcha token and
 * the logic state; the server keeps it only when it names a zone it knows.
 */
export const TIME_ZONE_HEADER = 'X-Formidable-Time-Zone';

/** The browser's time zone; undefined outside a browser or when the runtime exposes none. */
export const submitterTimeZone = (): string | undefined => {
	try {
		return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
	} catch {
		return undefined;
	}
};
