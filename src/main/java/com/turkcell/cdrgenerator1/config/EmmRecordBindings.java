package com.turkcell.cdrgenerator1.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the consuming EMM flow decodes: which type inside a module is the
 * record, and which CHOICE alternative it expects.
 *
 * <p>This is EMM configuration, not schema. The ASN.1 text says a module
 * declares {@code Cdrs ::= CHOICE {tokenMTAS, tokenCSCF, mergedIMS}}; it does
 * not say the CSCFColl flow is bound to {@code TokensCSCF}. That fact is only
 * ever learned from EMM's own answer, so it lives beside the schemas in
 * {@code emm-record-bindings.yml} where it can be read and traced, rather than
 * inside the parser where a reader would take it for a rule.</p>
 *
 * <p>Loaded from the classpath because it ships with the schemas and every
 * caller needs the same copy: the API, the UI behind it and the validation
 * samples all resolve through {@code StructureParserService}, so binding it
 * there is what keeps the file EMM receives encoded as the type EMM asked
 * for.</p>
 */
@Slf4j
public final class EmmRecordBindings {

    public static final String RESOURCE = "emm-record-bindings.yml";

    private static final String MODULES_KEY = "modules";
    private static final String RECORD_TYPE_KEY = "recordType";
    private static final String CHOICE_ALTERNATIVES_KEY = "choiceAlternatives";

    private static final EmmRecordBindings SHIPPED = loadShipped();

    private final Map<String, String> recordTypes;
    private final Map<String, Map<String, String>> choiceAlternatives;

    private EmmRecordBindings(Map<String, String> recordTypes,
                              Map<String, Map<String, String>> choiceAlternatives) {
        this.recordTypes = Map.copyOf(recordTypes);
        this.choiceAlternatives = Map.copyOf(choiceAlternatives);
    }

    /** The bindings that ship with the schemas, parsed once. */
    public static EmmRecordBindings shipped() {
        return SHIPPED;
    }

    /** Parses the same format from anywhere - the seam the format's tests use. */
    public static EmmRecordBindings read(Reader source) {
        Map<String, String> recordTypes = new LinkedHashMap<>();
        Map<String, Map<String, String>> alternatives = new LinkedHashMap<>();

        Object document = new Yaml().load(source);
        if (!(document instanceof Map<?, ?> root)) {
            return new EmmRecordBindings(recordTypes, alternatives);
        }
        if (!(root.get(MODULES_KEY) instanceof Map<?, ?> modules)) {
            return new EmmRecordBindings(recordTypes, alternatives);
        }

        for (Map.Entry<?, ?> module : modules.entrySet()) {
            String moduleName = String.valueOf(module.getKey());
            if (!(module.getValue() instanceof Map<?, ?> binding)) {
                continue;
            }
            if (binding.get(RECORD_TYPE_KEY) instanceof String recordType && !recordType.isBlank()) {
                recordTypes.put(moduleName, recordType);
            }
            if (binding.get(CHOICE_ALTERNATIVES_KEY) instanceof Map<?, ?> selections) {
                Map<String, String> perChoice = new LinkedHashMap<>();
                for (Map.Entry<?, ?> selection : selections.entrySet()) {
                    perChoice.put(String.valueOf(selection.getKey()), String.valueOf(selection.getValue()));
                }
                if (!perChoice.isEmpty()) {
                    alternatives.put(moduleName, Map.copyOf(perChoice));
                }
            }
        }
        return new EmmRecordBindings(recordTypes, alternatives);
    }

    /** The type EMM decodes as this module's record, or null when it never said. */
    public String recordTypeFor(String moduleName) {
        return Objects.isNull(moduleName) ? null : recordTypes.get(moduleName);
    }

    /** CHOICE type -&gt; the alternative EMM expects, empty when it never said. */
    public Map<String, String> choiceAlternativesFor(String moduleName) {
        if (Objects.isNull(moduleName)) {
            return Map.of();
        }
        return choiceAlternatives.getOrDefault(moduleName, Map.of());
    }

    /** True when EMM has said nothing about any module - a missing or empty file. */
    public boolean isEmpty() {
        return recordTypes.isEmpty() && choiceAlternatives.isEmpty();
    }

    private static EmmRecordBindings loadShipped() {
        try (InputStream stream = EmmRecordBindings.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (Objects.isNull(stream)) {
                log.warn("{} not found on the classpath; every module falls back to the root heuristic",
                        RESOURCE);
                return new EmmRecordBindings(Map.of(), Map.of());
            }
            EmmRecordBindings bindings =
                    read(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            log.info("Loaded EMM record bindings: {} record type(s), {} module(s) with a CHOICE selection",
                    bindings.recordTypes.size(), bindings.choiceAlternatives.size());
            return bindings;
        } catch (Exception e) {
            // A malformed bindings file must not stop the service from starting;
            // without it every module simply falls back to the heuristic, which
            // is what happened before any of this was recorded.
            log.error("Could not read {}; falling back to the root heuristic for every module", RESOURCE, e);
            return new EmmRecordBindings(Map.of(), Map.of());
        }
    }
}
