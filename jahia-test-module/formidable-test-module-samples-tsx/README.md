# Formidable samples (JavaScript module)

This module shows how a JavaScript module of your own extends Formidable **without changing
Formidable**: it ships its own definitions, editor overrides and views, depends on
`formidable-elements` at run time, and builds on
[`@jahia/formidable-library`](../../packages/formidable/README.md) for the rendering contract, exactly as
a module of your own does. It is deployed on our test instances; nothing in it is meant for
production as is, everything in it is meant to be copied.

## Copying this module: what to change

`package.json` declares `"@jahia/formidable-library": "workspace:*"`. That value works **inside this
repository only**: `packages/formidable` is a workspace of the same monorepo, and `workspace:*`
tells Yarn to link its sources, so the sample always follows the current code.

In your module, write the published version instead — the one matching the Formidable release
you target (the package is published with each release, from 0.5.0 on):

```sh
yarn add @jahia/formidable-library@<release>
```

The rest of `package.json` is copyable, except the lines that are ours: `name` and `version`,
of course; `"jahia": { "snapshot": true }`, which makes the JavaScript modules engine suffix the
bundle version with `.SNAPSHOT` so that a build redeploys over the same version — a module you
release does not want it; and `jahia.module-dependencies`, which names `formidable-elements`
so that Jahia refuses to start your module until the elements module is deployed. The sample
leaves that dependency unversioned because it follows the current code; a module of yours pins
the release it was built against — `formidable-elements=0.5` for 0.5.x — the way
`formidable-extended-inputs` pins its own.

Why the sample does not write the published version itself: from inside the monorepo, a version
range that matches `packages/formidable` still resolves to the workspace, not to npm (Yarn's
`enableTransparentWorkspaces`, on by default) — only the `npm:` protocol, `"npm:^0.5.0"`, would
force the registry. Staying on `workspace:*` means a change of the contract and the sample that
exercises it land in the same pull request.

One page per sample:

| Sample                                                            | What it shows                                                                                                                                  |
| ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| [Two-column fieldset](docs/fieldset-two-columns.md)               | An extra view for a built-in container, picked in the **View** chooser                                                                         |
| [Custom CSS classes on a fieldset](docs/fieldset-custom-style.md) | A mixin adding a property to a built-in type, in an editor section of its own, and a view applying it                                          |
| [Help text position](docs/help-text-position.md)                  | A setting added to every field with a help text, shown right under **Help text**, honoured by a rendering that takes the place of Formidable's |

The rules the samples follow (rendering contracts, what a third-party view must keep, how the
editor form can be shaped) are in
[How to extend Formidable views and elements from a third-party module](../../docs/extension/how-to-extend-views-and-elements-from-third-party-module.md).
