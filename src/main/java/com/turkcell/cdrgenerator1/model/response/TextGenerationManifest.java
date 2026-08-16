package com.turkcell.cdrgenerator1.model.response;

import com.turkcell.cdrgenerator1.service.TextGenerationResult;

import java.util.List;

/**
 * The {@code /generate} output plus the column map that makes it readable.
 *
 * <p>A {@code .txt} file has no header row and its width depends on how many
 * elements each {@code SEQUENCE OF} produced, so a consumer reading by position
 * cannot tell one generation from another. {@code columns} names the fields in
 * order: the i'th entry is the i'th value on every line of {@code text}.</p>
 */
public record TextGenerationManifest(String structureName,
                                     int recordCount,
                                     int columnCount,
                                     List<String> columns,
                                     String text) {

    public static TextGenerationManifest of(String structureName, int recordCount,
                                            TextGenerationResult rendered) {
        return new TextGenerationManifest(structureName, recordCount,
                rendered.columns().size(), rendered.columns(), rendered.text());
    }
}
