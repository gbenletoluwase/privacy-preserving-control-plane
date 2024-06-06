package io.gbenle.controlplane.redaction;

import java.util.List;

/**
 * Finds sensitive spans in a block of text.
 *
 * <p>This interface is the extension point of the redaction package. The pattern-based
 * recognizer in this repository covers the structured identifiers that regular
 * expressions handle well. Unstructured identifiers, a person's name in free text above
 * all, need a sequence model, and such a model plugs in here by implementing this
 * interface: load the model once, call it per record, map its output spans onto
 * {@link Entity}. Nothing else in the pipeline changes, because the pipeline never
 * learns which kind of recognizer produced a span.
 */
public interface EntityRecognizer {

    /**
     * @param text the text to scan, never null
     * @return spans found, in any order, possibly overlapping; each span's offsets are UTF-16
     *         indices into {@code text}, and its text must equal that source substring
     */
    List<Entity> recognize(String text);

    /** A stable identifier recorded on every span this recognizer produces. */
    String name();
}
