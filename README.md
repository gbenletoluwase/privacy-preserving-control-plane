# privacy-preserving-control-plane

A control plane for the layer where cloud-native data pipelines actually break: not the
application logic, but the orchestration around it. Four mechanisms, each useful alone,
built to be operated together.

Java 21, no runtime dependencies, Apache-style package layout, MIT licensed.

```
forecast-driven autoscaling  ->  provision ahead of load instead of behind it
privacy-preserving redaction ->  protect data at the point of processing
governed model promotion     ->  a qualification gate inside the deployment path
drift and anomaly detection  ->  notice when the inputs stop resembling the training set
```

## Why these four

Most of what goes wrong in a production machine-learning pipeline is not a bug in the
model. It is a mismatch between the model and the system around it.

Capacity is reactive, so the pipeline is under-provisioned for exactly as long as it
takes to schedule and warm a replica, and on a replica that loads a model, that is the
window where the backlog forms. Sensitive data is redacted downstream, after it has
already been written to three places. Model versions reach production through a
checklist, and checklists are skipped under deadline pressure. Input distributions move
and nothing notices, because every request still returns 200.

Each failure has a well-understood fix. The fixes are usually built by four different
teams, at four different times, and never meet. This repository is the argument that they
belong in one place, because they are coupled: a promoted model changes the cost per
record, which changes the capacity the scaler should request, and input drift is the
first visible sign that the live model no longer fits, which is a promotion question
rather than a monitoring one.

## Quick start

```bash
git clone https://github.com/<your-username>/privacy-preserving-control-plane.git
cd privacy-preserving-control-plane
mvn verify
mvn compile exec:java -Dexec.mainClass=io.gbenle.controlplane.ControlPlaneDemo
```

The demo output:

```
=== redacted records ===
  Caller [EMAIL_8e9881] rang from [PHONE_9e60b4] about order 88213.
  Card [CREDIT_CARD] declined; retry from host 10.2.14.9 succeeded.
  Agent note: SSN [SSN] supplied for identity check, do not retain.

spans matched  : 5 {CREDIT_CARD=1, EMAIL=1, IPV4=1, PHONE=1, SSN=1}
scaling        : scale-in dampened from 2 to 4
                 6 -> 4 replicas
input drift    : PSI 0.0283 over 10 bins on 400 samples: STABLE
load anomalies : 0
promotion gate : redactor-ner:3.2.0 passed all gate rules
audit chain    : 3 records, intact=true
```

Note what the redaction did. The order number `88213` survived, and so did the host
address, because the policy keeps IPv4. The payment card was caught and the Luhn
checksum confirmed it was really a card. The email and phone became stable pseudonyms,
so the same caller is still joinable across records without the address being recoverable.

## The four mechanisms

### 1. Forecast-driven autoscaling

`io.gbenle.controlplane.predictive`

`PredictiveScaler` sizes the fleet from predicted peak load across a horizon rather than
from current utilization. Load on a shared pipeline is rarely random; it has a level, a
trend, and a daily shape driven by human working hours, so `HoltWintersForecaster`
carries all three with additive triple exponential smoothing.

Prediction invites instability, so the scaler has three guards: a safety factor, so a
forecast that comes in slightly low does not immediately hurt; dampening, so one quiet
interval cannot shed most of the fleet; and asymmetric cooldowns, scaling out eagerly and
scaling in reluctantly.

```java
PredictiveScaler scaler = new PredictiveScaler(
        HoltWintersForecaster.hourlyWithDailySeason(),
        ScalerConfig.defaults(500.0, 2, 40));

ScalingDecision decision = scaler.decide(loadHistory, currentReplicas, 6, Instant.now());
System.out.println(decision.rationale());
```

Every decision carries its reasoning. An autoscaler that changes capacity without leaving
a readable reason cannot be debugged at three in the morning.

### 2. Privacy-preserving redaction

`io.gbenle.controlplane.redaction`

`RedactionPipeline` runs recognizers over text, resolves their disagreements, and applies
a policy that decides what replaces each kind of span.

Redaction is usually discussed as though deletion were the only option, but deletion
destroys analytic value the business legitimately needs. A support organization that
cannot tell whether two complaints came from the same customer has lost the ability to
measure repeat contact rate. `Strategy.TOKENIZE` answers both concerns: the same value
yields the same opaque token everywhere, so records still join, while the token reveals
nothing without the key. Tokens are HMAC-SHA256 rather than a bare hash, because a bare
hash of a low-entropy value such as a phone number can be reversed by enumeration in
seconds.

```java
RedactionPolicy policy = RedactionPolicy.builder()
        .defaultStrategy(Strategy.MASK)
        .with("EMAIL", Strategy.TOKENIZE)
        .with("IPV4", Strategy.KEEP)
        .tokenKey(secretsManager.lookup("redaction-token-key"))
        .build();
```

Two implementation details carry most of the correctness. Overlap resolution: independent
recognizers routinely claim overlapping spans, and applying all of them produces nested,
corrupted output, so spans are sorted by position then confidence and walked greedily.
The losing span is returned in the result rather than discarded silently, which makes
disagreement between detectors measurable. And replacement happens in a single pass over
the original offsets, because replacing spans one at a time in a mutable buffer
invalidates every later offset the moment a replacement differs in length from what it
replaced.

`EntityRecognizer` is the extension point. The built-in `PatternEntityRecognizer` covers
structured identifiers, which regular expressions handle well, and validates payment
cards with the Luhn checksum so order numbers and timestamps are not swept up.
Unstructured identifiers, a person's name in free text above all, need a sequence model;
such a model plugs in by implementing the same interface, and nothing else in the
pipeline changes.

### 3. Governed model promotion

`io.gbenle.controlplane.governance`

`PromotionGate` is a set of rules a model must satisfy before it reaches production
traffic. The argument for putting this in the deployment path rather than in a document
is that a document is skipped under pressure and code is not.

```java
PromotionGate gate = PromotionGate.builder()
        .recordingTo(evidenceLog)
        .requireMetricAtLeast("f1", 0.90)
        .requireMetricAtMost("falsePositiveRate", 0.02)
        .requireFreshness(Duration.ofDays(90))
        .requireDatasetProvenance()
        .requireOwner()
        .build();

GateResult result = gate.evaluate(candidateCard, Instant.now());
```

Rules return the reason they failed rather than a boolean, so the gate tells an engineer
what to fix. Every evaluation is written to an `EvidenceLog`, an append-only chain in
which each record commits to the hash of the one before it.

The property that buys is worth stating precisely, because hash chains are routinely
oversold. It does not prevent tampering, and it does not help against an attacker who can
rewrite the whole log and recompute every hash. What it gives you is that a silent edit of
one row, the realistic failure in a shared operational datastore, becomes detectable, and
`verify()` reports where the chain first breaks. For a durable deployment, persist each
record as it is appended and anchor the head hash outside the same trust boundary.

### 4. Drift and anomaly detection

`io.gbenle.controlplane.observability`

`DriftDetector` compares a recent window against a reference distribution using the
population stability index. Bins are quantiles of the reference sample rather than
equal-width intervals, so the measure stays meaningful on skewed data, and empty bins are
floored at a small epsilon so an unseen bin contributes large but finite evidence instead
of an infinity. The conventional 0.10 and 0.25 bands are heuristics rather than
thresholds derived from your data, and a team should recalibrate them against its own
tolerance for false alarms.

`AnomalyMonitor` scores points against the median and the median absolute deviation
rather than the mean and standard deviation. The usual z-score has a flaw that bites in
exactly the case you care about: the outlier you are hunting is itself inflating both
statistics, so a large enough spike hides itself. The MAD is scaled by 1.4826 so scores
stay comparable to conventional z-scores on normal data.

## Design notes

**No runtime dependencies.** Everything is JDK built-ins. A control plane that is itself a
dependency-resolution problem is not a control plane, and the parts most likely to be
replaced, forecaster and recognizer, are interfaces precisely so a heavier implementation
can be swapped in without the rest moving.

**Time is a parameter, not a reading.** Every method that cares about the clock takes an
`Instant`. Code that calls `Instant.now()` internally cannot be tested at interesting
moments, such as the second a cooldown expires.

**Thread safety is documented per class.** `PredictiveScaler` and `EvidenceLog` are
synchronized because they hold state. Everything else is immutable and safe to share.

**Failures are explained, not signalled.** Gate rules, scaling decisions and drift reports
all carry prose explaining themselves, because the value of these components at two in the
morning is entirely in what they can tell you.

## What is deliberately not here

A transformer backend for `EntityRecognizer`. Shipping a stub that pretends to be a model
would be worse than shipping the interface and saying what belongs behind it.

Kubernetes integration. `ScalingDecision` is a value, not a side effect. Translating it
into a replica count belongs in whatever operator or controller owns that concern.

Persistence. `EvidenceLog` is in memory, and the class documents what a durable
implementation needs.

## Testing

```bash
mvn verify
```

Seventy-one assertions across five test classes. The forecaster is checked against a
synthetic series with known trend and seasonality and must stay within five percent mean
absolute percentage error a full season ahead, and must beat a flat baseline. The scaler
is tested with a fixed forecaster so the scaling logic is isolated from forecast quality.
Redaction is checked for offset correctness when replacements differ in length, for Luhn
rejection, for token stability, and for overlap resolution. The audit chain is checked for
tamper detection.

## License

MIT. See [LICENSE](LICENSE).
