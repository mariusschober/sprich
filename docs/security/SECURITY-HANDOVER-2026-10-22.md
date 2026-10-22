# Security handover — sprich — 2026-10-22

Branch: `security/2026-10-22`, created from `main` @ `20be03c`. Pushed without a PR. No merge, deploy or CI run.
The commits were cherry-picked (`-x`) from the local review branch `improve/2026-10-22`. That branch is not pushed and is waiting for review.
`.github/workflows/ci.yml` triggers only on push or PR to `main`, so this push starts no CI.

## Fixes on this branch

| Commit | Item | What it does |
|---|---|---|
| d2d6673 | SPRICH-12 | The legacy plaintext API-key cleanup now runs at Application startup, not only when the IME starts. It removes the old preference keys instead of setting them to "", and a one-time flag makes it idempotent (the flag is set last, so an interrupted cleanup is retried). Already bound encrypted secrets are kept. |
| d648bde | SPRICH-17 | Adds a `SecretHealth` probe: MISSING, AVAILABLE or UNAVAILABLE. API settings show "verified/Use" only after the stored key actually decrypts. A transient Keystore or IO failure keeps the ciphertext so it can be retried. A permanent invalidation (`KeyPermanentlyInvalidatedException`, or `IllegalArgumentException` in the cause chain) deletes it so the user can re-enter the key. **Behaviour change:** before, any decrypt exception deleted the only ciphertext. |
| a1b62f5 | SPRICH-16 | Removes the unused `ACCESS_NETWORK_STATE` permission and updates the privacy notice. |
| 649aaed | SPRICH-22 | `FLAG_SECURE` stays on through key entry, the recording/check step, the live preview and the returned transcript card. |

None were skipped: all 4 `security:` commits applied cleanly.

## Tests (Linux VM, this branch)
- `./gradlew testDebugUnitTest` passes with **392 tests**, 0 failures (baseline 385).
- `lintDebug assembleDebug` passes. Lint has 0 errors and 84 warnings, and the debug APK builds.
- Not run: instrumented/device tests, a real Keystore invalidation (lock-screen change), and a screenshot/recents check of FLAG_SECURE on a phone.

## Not done / needs Marius
- Review and merge are up to Marius. No PR was opened.
- Review note for SPRICH-17: a corrupt ciphertext that fails with `AEADBadTagException` (not an `IllegalArgumentException`) is classed UNAVAILABLE and kept, not removed. The user sees "key unavailable" and must re-enter the key, which overwrites the file. Decide whether that should count as permanent.
- Signing key, publisher identity and the redistribution review are unchanged (owner-only).
