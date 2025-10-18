# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Continuous integration on JDK 21 and 25, with a JaCoCo coverage report.
- Runnable jar: `java -jar target/privacy-preserving-control-plane-0.1.0.jar` runs the demo.
- `SECURITY.md`, pull request and issue templates, Dependabot configuration.
- Package-level Javadoc for every package.

### Changed
- Build enforces JDK 21+ and compiles with `-Xlint:all`.

### Fixed
- Validate forecast shape and finite predictions before making scaling decisions or starting cooldowns.
- Snapshot batch inputs and cycle-report collections so caller mutations cannot change evaluation inputs or recorded results.
- Reject non-finite observations and anomaly thresholds so invalid monitoring input cannot appear healthy.
- Validate finite scaler capacity and safety factors and reject missing or negative cooldowns at configuration creation.
- Reject negative freshness limits and future model-training timestamps while preserving inclusive age boundaries.
- Reject non-finite observations in EWMA and Holt-Winters histories before generating forecasts.
- Make evidence hashes unambiguous across text-field boundaries and preserve nanosecond precision. Hashes use a new encoding; previously persisted hashes require their original encoding for verification.
- Prevent finite extreme observations from overflowing quantile boundaries and hiding distribution drift.
- Reject accepted recognizer spans whose text or offsets do not match the source before redaction or tokenization.
- Allow the first scaling decision at any evaluation timestamp; start cooldowns only after capacity changes.
- Block non-finite promotion metrics and reject non-finite gate thresholds.

## [0.1.0] - 2026-10-10

### Added
- Forecast-driven autoscaling: `PredictiveScaler`, `HoltWintersForecaster`, `EwmaForecaster`.
- Privacy-preserving redaction: `RedactionPipeline`, `PatternEntityRecognizer` with Luhn
  validation, keyed pseudonymization via `RedactionPolicy`.
- Governed model promotion: `PromotionGate` and the hash-chained `EvidenceLog`.
- Drift and anomaly detection: PSI-based `DriftDetector`, MAD-based `AnomalyMonitor`.
- `ControlPlane` facade and `ControlPlaneDemo`.

[Unreleased]: https://github.com/gbenletoluwase/privacy-preserving-control-plane/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gbenletoluwase/privacy-preserving-control-plane/releases/tag/v0.1.0
