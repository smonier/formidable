---
# Allowed version bumps: patch, minor, major
formidable: patch
---

The formidable-engine editor bundle pins `@apollo/client` to the app shell's exact version, so the federation runtime cannot elect a second Apollo copy after a server restart (white jContent page with `Invariant Violation`).
