package com.circularsync.interfaces;

import com.circularsync.exceptions.InvalidNoticeFormatException;

/**
 * Contract for any artefact that enters the CircularSync pipeline as raw text
 * and must be checked and decomposed into structured metadata before it can
 * be indexed.
 *
 * The two phases are deliberately separate: {@link #validateFormat()} is a
 * cheap structural gate that rejects malformed input early, and
 * {@link #parseMetadata()} performs the typed conversion of header values.
 * Callers must invoke them in that order.
 */
public interface Processable {

    /**
     * Verifies that the raw content has the structural shape required by the
     * implementing type (mandatory headers present, header block terminated,
     * non-empty body).
     *
     * @throws InvalidNoticeFormatException if the structure is not acceptable
     */
    void validateFormat() throws InvalidNoticeFormatException;

    /**
     * Converts the raw header values into typed fields (dates, numbers,
     * keyword sets). Must only be called after a successful
     * {@link #validateFormat()}.
     *
     * @throws InvalidNoticeFormatException if a value cannot be converted
     */
    void parseMetadata() throws InvalidNoticeFormatException;

    /** @return true once both phases have completed successfully. */
    boolean isProcessed();
}
