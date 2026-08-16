package com.turkcell.cdrgenerator1.service;

import java.util.List;

/**
 * One Token-Separated generation: the text, and the column map that explains it.
 *
 * <p>A {@code .txt} file carries no header row, and the column set is not a
 * property of the structure alone - a {@code SEQUENCE OF} field contributes one
 * column group per element it happened to produce, so two files generated from
 * the same module can differ in width. A consumer reading by position cannot
 * tell which of the two it is holding. {@code columns} answers that: the i'th
 * entry names the i'th field on every line of {@code text}.</p>
 *
 * <p>The two always come from a single generation, so they can never disagree.
 * Anything that rendered the text and then recomputed the columns separately
 * would be the defect this type exists to make impossible.</p>
 */
public record TextGenerationResult(List<String> columns, String text) {

    public TextGenerationResult {
        columns = List.copyOf(columns);
    }
}
