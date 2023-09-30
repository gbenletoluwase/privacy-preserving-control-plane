# Security policy

This project handles personal data by design, so a defect in it can be a privacy
incident rather than an ordinary bug. Please report anything in the categories below
privately.

## What counts as a security issue

- **Redaction bypass.** Input that contains an identifier the pattern recognizer claims to
  cover (email, SSN, payment card, phone, IPv4) and comes out of `RedactionPipeline`
  unredacted under a policy that should have obscured it.
- **Token reversal or collision.** Any way to recover an original value from a
  `TOKENIZE` pseudonym without the key, or to force two distinct values to the same token
  more often than the truncated length implies.
- **Audit chain weakness.** An edit to an `EvidenceLog` record that `verify()` does not
  detect, other than a full rewrite of the chain, which is documented as out of scope.
- **Gate bypass.** A `ModelCard` that passes a `PromotionGate` it should fail.

## Reporting

Use GitHub's private vulnerability reporting:
<https://github.com/gbenletoluwase/privacy-preserving-control-plane/security/advisories/new>

Please include a minimal reproduction using synthetic data. Never include real personal
data in a report.

You can expect an acknowledgement within five working days. Fixes are released as a
patch version and credited in the changelog unless you ask otherwise.

## Supported versions

Only the latest release receives security fixes while the project is pre-1.0.

## Operational guidance

- Load the `TOKENIZE` key from a secrets manager. The key in `ControlPlaneDemo` is a
  placeholder and must never be used outside the demo.
- `HASH` is unkeyed. Use it only for high-entropy values; low-entropy values such as
  phone numbers can be reversed by enumeration.
- The built-in recognizer covers structured, mostly US-format identifiers. It does not
  find names or free-text personal data. Treat its output as reduced risk, not as
  anonymized.
