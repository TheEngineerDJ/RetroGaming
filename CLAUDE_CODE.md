# CLAUDE_CODE.md

## Authority

Read these files before changing code:

1. `Constitution.md`
2. `ARCHITECTURE.md`
3. `DOMAIN_MODEL.md`
4. `ROM_INTELLIGENCE.md`
5. `DATABASE.md`
6. `UX_SPEC.md`
7. `ENGINEERING_SPEC.md`
8. `TESTING_SPEC.md`
9. `BUILD_PLAN.md`

`Constitution.md` is highest authority.

## Operating rules

- Do not invent requirements when specifications are explicit.
- Do not weaken safety for convenience.
- Do not silently change domain meaning to fit existing code.
- If implementation conflicts with the Constitution, stop and document the conflict.
- Prefer small, testable changes.
- Preserve existing working behavior unless intentionally replacing it.
- Do not add network dependency to offline workflows.
- Do not commit ROM collections, secrets, credentials, or restricted DAT datasets.

## Architecture rules

Dependency direction:

`UI → Application → Domain ← Infrastructure`

Core domain must remain platform-independent.

Business logic does not belong in Compose screens or ViewModels.

## Matching rules

Filename is evidence, not identity.

Exact hash matches and heuristic matches must remain distinct.

Ambiguity must remain visible.

Never auto-rename an unresolved artifact.

## Safety rules

Before filesystem mutation:

1. resolve identity
2. generate plan
3. validate entire batch
4. detect collisions
5. persist journal
6. execute
7. reconcile result

## Testing rules

Every new identity rule requires tests.

Every production bug becomes regression coverage where practical.

False-positive tests are mandatory for matching changes.

## Workflow

1. Inspect repository and current implementation.
2. Map code against specifications.
3. Identify smallest next vertical slice.
4. Implement.
5. Test.
6. Review architecture boundaries.
7. Update documentation when behavior changes.
8. Commit coherent work.

Do not rewrite the entire project simply because existing code is imperfect.

## First implementation target

Complete the ROM normalization vertical slice:

`SAF folder → scan → DAT lookup → hash resolution → fallback matching → evidence → rename preview → safe rename → audit`

## Definition of success

The program must prefer an honest unresolved result over a plausible wrong rename.
