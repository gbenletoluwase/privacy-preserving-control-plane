/**
 * A control plane for cloud-native data pipelines.
 *
 * <p>{@link io.gbenle.controlplane.ControlPlane} wires four independently usable
 * mechanisms into one evaluation cycle:
 *
 * <ul>
 *   <li>{@link io.gbenle.controlplane.redaction} protects data at the point of processing;</li>
 *   <li>{@link io.gbenle.controlplane.predictive} provisions capacity ahead of load;</li>
 *   <li>{@link io.gbenle.controlplane.observability} notices when inputs drift;</li>
 *   <li>{@link io.gbenle.controlplane.governance} gates which model version may go live.</li>
 * </ul>
 *
 * <p>No runtime dependencies beyond the JDK. Every clock-sensitive method takes an
 * {@link java.time.Instant} so behaviour at boundaries can be tested exactly.
 */
package io.gbenle.controlplane;
