# Architecture

## Shape

```
                        ControlPlane
                             |
        +----------+---------+---------+------------+
        |          |                   |            |
   redaction   predictive         governance   observability
        |          |                   |            |
  EntityRecognizer  Forecaster     GateRule      DriftDetector
  (interface)       (interface)    (interface)   AnomalyMonitor
```

`ControlPlane` is a facade. It owns no logic of its own beyond ordering the four
mechanisms within a cycle and writing the audit records that tie them together. Every
mechanism is usable on its own, and the test suite exercises them that way.

## The cycle

`ControlPlane.runCycle` does five things in a fixed order:

1. **Redact the batch.** This is first because everything after it may be logged, and
   nothing that is logged should contain raw identifiers.
2. **Decide capacity.** The scaler sees load history and the current replica count.
3. **Compare input distributions.** Drift against a reference sample supplied by the
   caller, so the caller controls when the baseline is refreshed.
4. **Scan load for anomalies.** Point outliers that a distribution comparison will miss,
   because a single extreme value barely moves a histogram.
5. **Evaluate the promotion gate.** Last, because a decision about which model should be
   live is better made with the drift signal from this cycle in hand.

Audit records are written at three points: after redaction, on any capacity change, and
on any actionable drift. The gate writes its own record through the `EvidenceLog`
attached at build time.

## Extension points

| Interface | Replace it when | The rest of the system |
|---|---|---|
| `Forecaster` | a statistical model is not enough and you have a trained one | unchanged; the scaler only calls `forecast` |
| `EntityRecognizer` | you need unstructured entities such as personal names | unchanged; the pipeline never learns the recognizer's kind |
| `GateRule` | your promotion criteria are not expressible with the builder | unchanged; rules are composed, not subclassed |

Each is a narrow interface with a single meaningful method. That is deliberate. An
interface wide enough to be convenient is an interface nobody can implement.

## Why time is injected

Every clock-sensitive method takes an `Instant` rather than calling `Instant.now()`.
This is what makes the cooldown logic testable: a test can evaluate at exactly the second
a cooldown expires, which is the case most likely to be wrong and least likely to be
reached by accident.

## Concurrency

| Class | Contract |
|---|---|
| `PredictiveScaler` | synchronized; holds last scale-up and scale-down times. One instance per scaled workload. |
| `EvidenceLog` | synchronized; safe for concurrent appends. |
| `RedactionPipeline` | immutable, safe to share, provided its recognizers are. |
| `PromotionGate` | immutable once built. |
| `DriftDetector`, `AnomalyMonitor` | stateless. |
| forecasters | immutable. |

## Known limitations

**Holt-Winters needs two full seasons** before it will forecast at all, and it has no
automatic parameter fitting. A production deployment should fit alpha, beta and gamma by
minimizing error on a holdout window and refit periodically.

**PSI is univariate.** It compares one feature at a time, so a shift in the correlation
between two features that leaves both marginals unchanged is invisible to it.

**The pattern recognizer is English and United States centric** in its phone and
government identifier patterns. Other locales need their own rules, which is a reason to
keep `EntityRecognizer` as the seam.

**The evidence log is in memory.** It verifies its own chain, but a process restart loses
it. See the class documentation for what a durable implementation needs.
