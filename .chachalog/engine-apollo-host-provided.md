---
# Allowed version bumps: patch, minor, major
formidable: patch
---

The formidable-engine editor bundle no longer ships its own `@apollo/client`: it declares it host-provided and pinned to the app shell's version, so the federation runtime cannot elect a second Apollo copy after a server restart (white jContent page with `Invariant Violation`).
