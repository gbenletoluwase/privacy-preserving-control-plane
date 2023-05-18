/**
 * Privacy-preserving redaction of sensitive spans in text.
 *
 * <p>{@link io.gbenle.controlplane.redaction.EntityRecognizer}s find candidate spans,
 * {@link io.gbenle.controlplane.redaction.RedactionPipeline} resolves overlaps between
 * them, and {@link io.gbenle.controlplane.redaction.RedactionPolicy} decides whether each
 * span is masked, pseudonymized with a keyed HMAC, hashed, dropped or kept.
 */
package io.gbenle.controlplane.redaction;
