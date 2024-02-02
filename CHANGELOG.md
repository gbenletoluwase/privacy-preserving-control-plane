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
