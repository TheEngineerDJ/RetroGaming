# SECURITY_SPEC.md

**Project:** RetroVault
**Role:** Security and safety requirements
**Authority:** `CONSTITUTION.md`

## 1. Threat model

Treat all external inputs as untrusted:

- filenames
- directory names
- SAF metadata
- DAT XML
- archive contents
- hash values
- imported metadata
- user-entered naming profiles

The app must protect user files, local database integrity, and user privacy.

## 2. Filesystem safety

Never construct a destination path from unvalidated user or DAT input.

Reject:

- path traversal
- invalid path components
- empty destination names
- ambiguous destinations
- unexpected directory replacement

Operate only inside user-authorized SAF locations.

## 3. Archive safety

Archives may be malicious or malformed.

Bound:

- nesting depth
- entry count
- decompressed size where applicable
- filename length
- metadata size

Do not extract archives merely to identify contained files when streaming inspection is sufficient.

## 4. XML safety

DAT parser must use safe XML parsing.

Disable unnecessary external entity/network behavior.

Bound input sizes where practical.

Malformed input must fail as data, not crash the application.

## 5. Resource exhaustion

Hashing and scanning must use bounded concurrency.

A pathological collection must not create unbounded coroutine, memory, file descriptor, or database workloads.

## 6. Privacy

ROM contents remain local.

No telemetry is required for core functionality.

Do not transmit hashes, filenames, storage paths, or collection data without explicit future product consent.

## 7. Sensitive local data

Database backups and exports may reveal collection information.

Treat them as user data.

Do not log database contents.

## 8. Secrets

Never commit:

- API keys
- signing keys
- credentials
- tokens
- personal filesystem paths
- private ROM data

## 9. Update safety

Application updates must not silently alter canonical naming rules in a way that causes automatic renames.

Changes to matching/naming behavior require visible versioning and regression tests.

## 10. Security principle

**The app must be incapable of damaging a user's collection merely because an input was malformed or an identification guess was wrong.**
