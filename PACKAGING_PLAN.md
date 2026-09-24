# Packaging jolt-diplomat for external use

Goal: someone runs this against their own Diplomat crate without cloning rust-jolt.

Note: bd (beads) DB unreachable this session — sandbox blocked `~/.dolt/config_global.json`.
Tracking here per explicit request instead.

## Steps

- [x] 1. Publish `jolt-diplomat-macros` to crates.io — live at https://crates.io/crates/jolt-diplomat-macros
- [x] 2. Publish/ship `jolt-diplomat-backend` as installable binary — live at https://crates.io/crates/jolt-diplomat-backend
- [ ] 3. Version and ship `runtime/` as a proper Jolt/Clojure dep (git coord or Clojars)
- [x] 4. Make `bind.clj` location-independent (no repo-relative path assumptions)
- [ ] 5. Rewrite README "Usage" for external consumers
- [ ] 6. Coordinated version tag across macros+backend+runtime

## Progress log

- 2026-09-23: Plan written. Starting step 4: auditing bind.clj for hardcoded paths into runtime/ and backend/.
- 2026-09-23: Step 4 done. bind.clj hardcoded `backend/target/debug/jolt-diplomat-backend`
  relative to the script's own dir, and unconditionally ran `cargo build --manifest-path
  backend/Cargo.toml` — required a full rust-jolt checkout every run, even for an external
  crate. Fixed: bind.clj now checks `which jolt-diplomat-backend` first and uses the
  PATH-installed binary (from `cargo install --path backend` or, once published, `cargo
  install jolt-diplomat-backend`) when present, skipping the backend/ build entirely.
  Falls back to the old repo-relative dev build only when nothing is installed AND a
  local backend/ checkout exists; dies with a clear message otherwise.
  runtime/ (deps.edn, demo.clj) audited too — already free of repo-relative paths, no
  changes needed there.
  Verified: `jolt bind.clj` (no args) still parses and hits the usage guard correctly.
  Full pipeline not exercised live — no cargo on PATH in this sandbox and no test capi
  crate on hand; syntax/logic verified by inspection + the which-based PATH probe tested
  standalone.
  Not done yet: jolt-diplomat-backend isn't actually published to crates.io (step 2) —
  `cargo install --path backend` from a checkout still works today as the "installed"
  path, `cargo install jolt-diplomat-backend` doesn't exist until step 2 ships.

Next: step 1 (publish jolt-diplomat-macros to crates.io) or step 2 (ditto for the
backend binary) — either unblocks a true no-checkout-at-all external workflow.

- 2026-09-23: Step 1 prepped, not published. `macros/Cargo.toml` was missing `license`
  and `repository` — crates.io rejects publish without `license`. Added:
  `license = "MIT"` (matches repo LICENSE), `repository =
  "https://github.com/jolt-lang/jolt-diplomat"` (matches `git remote origin`).
  `cargo package --list --allow-dirty` shows a clean 8-file package (src/lib.rs +
  tests/, no stray files). `cargo package --allow-dirty` packages, verifies, and
  compiles standalone with no errors/warnings — confirms the crate has zero
  repo-relative assumptions and is genuinely publishable as-is.
  Stopped short of `cargo publish`: publishing a crate version is irreversible
  (crates.io has no unpublish, only yank) and is a shared-state action — needs
  explicit go-ahead. Also needs a crates.io account/API token logged in, which
  wasn't checked/set up this session.
- 2026-09-24: Step 1 published. Committed macros/Cargo.toml (28d18e9). First publish attempt
  failed: crates.io requires a verified email (400 Bad Request). After verifying email at
  crates.io/settings/profile, `cargo publish` succeeded — jolt-diplomat-macros v0.1.0 is
  live at https://crates.io/crates/jolt-diplomat-macros. Any external crate can now depend
  on it via `jolt-diplomat-macros = "0.1"` instead of a path/git dep into this repo.
- 2026-09-24: Discussed whether macros and backend should be one crate — no. Different
  consumers (macros compiles into the user's own capi crate; backend is a standalone
  codegen binary, never linked into anyone's crate), and a proc-macro crate can't also
  ship a `[[bin]]` in the same Cargo.toml anyway. Confirmed backend/src/main.rs only
  references jolt_diplomat_macros in comments (parses `#[jolt_diplomat::blocking]`
  textually, like it does `#[cfg(feature=...)]`) — no real dependency edge between them.
  Keeping them split.
- 2026-09-24: Step 2 published. backend/Cargo.toml was missing `license`/`repository`/
  `description` (same gap as macros). Added `license = "MIT"`, `repository` (same repo
  URL), and a one-line `description`. `cargo package --list` showed a clean 5-file
  package (just src/main.rs, no stray files); full `cargo package` verified and compiled
  standalone. Committed (79ca776), then `cargo publish` succeeded —
  jolt-diplomat-backend v0.1.0 is live at https://crates.io/crates/jolt-diplomat-backend.
  `cargo install jolt-diplomat-backend` now works for anyone, which means bind.clj's
  step-4 PATH-detection fix (which jolt-diplomat-backend) now has a real non-checkout
  install path to find, not just `cargo install --path backend` from a clone.

Steps 1, 2, and 4 are all done — the core "no full checkout needed" workflow now works:
  cargo install jolt-diplomat-backend
  # add jolt-diplomat-macros to the user's capi Cargo.toml
  # bind.clj + runtime/ still need to be obtained some way (step 3/5)

Next: step 3 (ship runtime/ as a proper dep) and step 5 (rewrite README for external
consumers) are what's left before this is a genuinely documented, no-checkout workflow.
