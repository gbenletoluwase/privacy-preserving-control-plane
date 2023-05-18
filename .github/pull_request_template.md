## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- The problem this solves, or the property it protects. -->

## Checklist

- [ ] `mvn verify` passes locally
- [ ] New behaviour has a test whose `@DisplayName` states the property it protects
- [ ] No new runtime dependencies
- [ ] Clock-dependent code takes an `Instant` rather than calling `Instant.now()`
- [ ] Thread-safety contract documented for any new stateful class
- [ ] `CHANGELOG.md` updated under *Unreleased*
