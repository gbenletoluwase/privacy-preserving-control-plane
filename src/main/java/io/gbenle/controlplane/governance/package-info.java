/**
 * Governed model promotion with a tamper-evident audit trail.
 *
 * <p>A {@link io.gbenle.controlplane.governance.PromotionGate} evaluates a
 * {@link io.gbenle.controlplane.governance.ModelCard} against a list of
 * {@link io.gbenle.controlplane.governance.GateRule}s and returns a
 * {@link io.gbenle.controlplane.governance.GateResult} that names every failed rule.
 * Each evaluation can be appended to an
 * {@link io.gbenle.controlplane.governance.EvidenceLog}, a hash chain in which a silent
 * edit to any earlier record becomes detectable.
 */
package io.gbenle.controlplane.governance;
