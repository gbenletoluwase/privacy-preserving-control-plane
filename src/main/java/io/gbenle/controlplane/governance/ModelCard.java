package io.gbenle.controlplane.governance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The declared facts about one trained model version.
 *
 * <p>A promotion gate can only check what the model is willing to state about itself,
 * so the card is the contract. Keeping the dataset hash on the card is what makes a
 * later question answerable: when a model is found to behave badly in production, the
 * first thing anyone needs to know is which data produced it.
 *
 * @param modelId    stable identifier for the model family
 * @param version    version of this particular artifact
 * @param trainedAt  when training finished
 * @param metrics    evaluation metrics by name, for example {@code f1}
 * @param datasetHash content hash of the training set
 * @param owner      team or person accountable for this model
 */
public record ModelCard(
        String modelId,
        String version,
        Instant trainedAt,
        Map<String, Double> metrics,
        String datasetHash,
        String owner) {

    public ModelCard {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(trainedAt, "trainedAt");
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics"));
        datasetHash = datasetHash == null ? "" : datasetHash;
        owner = owner == null ? "" : owner;
    }

    public Optional<Double> metric(String name) {
        return Optional.ofNullable(metrics.get(name));
    }

    /** A short label suitable for logs, for example {@code redactor-ner:3.2.0}. */
    public String reference() {
        return modelId + ":" + version;
    }
}
