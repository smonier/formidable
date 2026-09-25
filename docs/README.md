# Formidable documentation

One folder per reader. Each folder opens with a `README.md` that says who it is for and what
is in it.

| Folder | For | Contents |
|---|---|---|
| [`styling/`](styling/README.md) | The template set | How the rendered form is structured, the class hooks, the CSS variables |
| [`extension/`](extension/README.md) | A third-party module | Adding a server-side form action; adding views, containers and fields |
| [`administration/`](administration/README.md) | The administrator | Upgrade notes, error codes, CAPTCHA verification, results permissions |
| [`architecture/`](architecture/README.md) | The maintainer | How the modules work, and why they are built this way |

Every file here is internal documentation: Markdown without front matter, never synchronised to
the Academy. A relative link that no longer resolves fails the static-analysis job
(`scripts/check-doc-links.mjs`), and so does a `docs/…md` path quoted in a code comment.
