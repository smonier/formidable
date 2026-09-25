# Results viewing permissions

Formidable provides per-form access control for submission results. An admin can
grant specific users or groups the right to view a form's results without giving
them edit access to the form itself.

## How it works

All configuration happens through Jahia's native **Permissions** UI in jContent.
There is no custom permission screen in Formidable.

### Role: `fmdb-results-reader`

The module registers a single custom role called `fmdb-results-reader`. It is
created automatically when the module starts as a child of the built-in `reader`
role (`/roles/reader/fmdb-results-reader`), so it inherits `jcr:read` permissions.

### ACL inheritance is broken on results

By default, Jahia's `reader` role is assigned at the site level and grants
`jcr:read` to all content in live — including form results. To prevent this,
Formidable **breaks ACL inheritance** (`j:inherit = false`) on every
`fmdb:formResults` node. This ensures that only users with an explicit
`fmdb-results-reader` grant (or site administrators) can access submission data.

This protection is enforced at two levels:
- at `formResults` creation time by `SaveToJcrFormAction`
- on every ACL sync by `FormResultsAclSyncService` (repairs inheritance if
  it was accidentally restored)

### Granting access

1. In jContent, select a `fmdb:form` node and open **Permissions**.
2. In the **LIVE** section, assign the `fmdb-results-reader` role to a user or
   group.
3. **Publish** the form.

On publication, the module automatically propagates the role assignment to the
form's `fmdb:formResults` node in the live workspace. JCR inheritance ensures that
all submissions and files under that node are also accessible.

### How propagation works

Two components handle the synchronisation:

| Component | Trigger | Action |
|-----------|---------|--------|
| `FormPublicationAclSyncListener` | Form or ACE published to live | Reads `fmdb-results-reader` ACEs from the form, replicates them on `formResults` |
| `SaveToJcrFormAction` | First submission creates `formResults` | Immediately syncs ACEs from the form onto the newly created `formResults` |

Both delegate to `FormResultsAclSyncService`, which is idempotent: it compares
source and target ACEs, adds missing ones, and removes obsolete ones. Calling it
multiple times has no side effects.

### Key behaviours

- **Results are private by default.** ACL inheritance is broken on
  `fmdb:formResults`. Without an explicit `fmdb-results-reader` grant, no one
  except site administrators can read submission data — not via the dashboard,
  not via GraphQL, and not via JCR Browser.
- **Publication validates rights.** Modifying permissions in EDIT without
  publishing has no effect on results access.
- **Propagation is unidirectional.** ACEs flow from the form to the formResults
  node, never the other way. Do not edit formResults ACLs directly.
- **Form editing uses standard Jahia roles.** The `editor` and `contributor`
  roles control who can edit a form. No custom role is needed for this.
- **GraphQL respects ACLs.** The results dashboard only shows `formResults`
  nodes that the current user can read. No additional filtering is needed.

## Deletion permissions

The current permission model is intentionally read-only for delegated access:

- `fmdb-results-reader` grants read access only (inherited from `reader`)
- it does **not** grant deletion rights such as `jcr:removeNode` or
  `jcr:removeChildNodes`

This means a user with only `fmdb-results-reader` can view results but cannot
delete them through GraphQL mutations. This is the intended v1 behavior.

### Current product decision

For v1, deleting form results is treated as an administrative operation:

- delegated users can read results
- site administrators keep deletion rights
- no dedicated "results manager" role is introduced yet

This keeps the model simple and avoids over-designing delegation before a real
need appears.

### UI implication

Because deletion is not part of `fmdb-results-reader`, the dashboard should not
offer the `Delete` action to users who only have read access. The preferred
behavior is to hide the button when the user does not have sufficient rights.
If UI-level permission detection is not available yet, the mutation failure must
at least be handled gracefully.

### Alternatives kept in reserve

If finer delegation is needed later, two options remain open:

| Option | Approach | Trade-off |
|--------|----------|-----------|
| A | Keep the current model | Simplest. Only admins delete. |
| B | Add `fmdb-results-manager` | Clear separation between read and delete capabilities. Requires ACL propagation similar to `fmdb-results-reader`. |
| C | Enrich `fmdb-results-reader` with delete rights | Technically possible, but the role name becomes misleading. |

At this stage, option A is the chosen default. Option B is the most likely
evolution path if non-admin deletion becomes a real requirement.

## What happens when a form is deleted

If a form is deleted, its `formResults` node remains in live with all submission
data and ACLs intact. This is intentional: submission data is never destroyed
automatically. A cleanup mechanism for orphaned results may be added in a future
version.

## Content model

```
fmdb:form (jmix:accessControllableContent via fmdbmix:component)
  └─ j:acl
       └─ jnt:ace  ← admin assigns fmdb-results-reader here (LIVE section)

formidable-results/ (fmdb:resultsFolder, jmix:accessControlled)
  └─ my-form (fmdb:formResults, jmix:accessControlled)
       ├─ j:acl (j:inherit = false)  ← inheritance broken, blocks site-level reader
       │    └─ jnt:ace  ← explicitly synced from the form
       └─ submissions/
            └─ ...
```

## Implementation files

| File | Role |
|------|------|
| `formidable-engine/.../permissions/FormResultsRoleInitializer.java` | Creates the `fmdb-results-reader` role under `/roles/reader/` at module activation |
| `formidable-engine/.../permissions/FormResultsAclSyncService.java` | Idempotent ACL sync (reads source ACEs, writes target ACEs, enforces `j:inherit = false`) |
| `formidable-engine/.../permissions/FormPublicationAclSyncListener.java` | Live workspace listener for form publication and ACE changes |
| `formidable-engine/.../actions/form/storage/SaveToJcrFormAction.java` | Calls ACL sync when creating a new `formResults` node, sets `j:inherit = false` |
| `formidable-engine/src/main/resources/META-INF/definitions.cnd` | `fmdb:formResults` declared with `jmix:accessControlled` |
