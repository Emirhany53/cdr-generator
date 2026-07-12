package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class AsnFieldTreeResolver {

    // Groups: 1=field name, 2=tag class keyword (optional), 3=tag number (optional),
    // 4=EXPLICIT/IMPLICIT keyword (optional), 5=type expression.
    // Handles both "[5]" and class-qualified tags like "[APPLICATION 0]".
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s*([A-Za-z_][\\w-]*)\\s*(?:\\[\\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*)?(EXPLICIT\\s+|IMPLICIT\\s+)?(.+?)\\s*,?\\s*$"
    );
    private static final int MAX_DEPTH = 15;
    private static final String EXPLICIT_KEYWORD = "EXPLICIT";


    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName) {
        return resolveRootFields(registry, rootTypeName, Map.of());
    }

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                            Map<String, String> choiceSelections) {

        Map<String, List<AsnField>> resolutionCache = new HashMap<>();
        return resolveByTypeName(registry, rootTypeName, choiceSelections, new HashSet<>(), 0, resolutionCache);
    }

    private List<AsnField> resolveByTypeName(Map<String, AsnTypeDefinition> registry, String typeName,
                                             Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                             Map<String, List<AsnField>> cache) {
        if (typeName == null) {
            return List.of();
        }

        String cacheKey = buildCacheKey(typeName, choiceSelections);
        List<AsnField> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        AsnTypeDefinition definition = registry.get(typeName);
        if (definition == null) {
            return List.of();
        }
        if (visiting.contains(typeName) || depth > MAX_DEPTH) {
            log.warn("Circular or too-deep ASN.1 reference at '{}', stopping recursion", typeName);
            return List.of();
        }

        Set<String> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(typeName);

        List<AsnField> result = switch (definition.getKind()) {
            case ENUMERATED -> List.of();
            case ALIAS -> resolveAlias(registry, definition, choiceSelections, nextVisiting, depth, cache);
            case SEQUENCE, SET -> parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache);
            case CHOICE -> resolveChoiceAlternative(registry, typeName, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache);
        };

        cache.put(cacheKey, result);
        return result;
    }

    private String buildCacheKey(String typeName, Map<String, String> choiceSelections) {
        return choiceSelections.isEmpty() ? typeName : typeName + "::" + choiceSelections;
    }

    private List<AsnField> resolveAlias(Map<String, AsnTypeDefinition> registry, AsnTypeDefinition definition,
                                        Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                        Map<String, List<AsnField>> cache) {
        String target = stripConstraint(definition.getAliasTarget());
        String innerType = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        return resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1, cache);
    }

    private List<AsnField> resolveChoiceAlternative(Map<String, AsnTypeDefinition> registry, String choiceTypeName,
                                                    String rawBody, Map<String, String> choiceSelections,
                                                    Set<String> visiting, int depth,
                                                    Map<String, List<AsnField>> cache) {
        String preferredAlternative = choiceSelections.get(choiceTypeName);
        AsnField firstAlternative = null;

        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed);
            if (alternative == null) continue;

            if (firstAlternative == null) {
                firstAlternative = alternative;
            }
            if (alternative.getFieldName().equals(preferredAlternative)) {
                return resolveAlternative(registry, alternative, choiceSelections, visiting, depth, cache);
            }
        }

        if (firstAlternative != null) {
            if (preferredAlternative != null) {
                log.warn("Choice alternative '{}' not found in '{}', falling back to first alternative '{}'",
                        preferredAlternative, choiceTypeName, firstAlternative.getFieldName());
            }
            return resolveAlternative(registry, firstAlternative, choiceSelections, visiting, depth, cache);
        }
        return List.of();
    }

    /**
     * Resolves the chosen CHOICE alternative. When the alternative's type is a
     * structured type, its fields are returned as before. When it is a
     * primitive (e.g. {@code cdrFormatField [1] IMPLICIT OCTET STRING}), the
     * alternative itself is kept as a single leaf field - otherwise the whole
     * CHOICE would silently resolve to an empty structure.
     */
    private List<AsnField> resolveAlternative(Map<String, AsnTypeDefinition> registry, AsnField alternative,
                                              Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                              Map<String, List<AsnField>> cache) {
        List<AsnField> resolved = resolveByTypeName(registry, alternative.getFieldType(),
                choiceSelections, visiting, depth + 1, cache);
        return resolved.isEmpty() ? List.of(alternative) : resolved;
    }

    /**
     * Splits a SEQUENCE/SET/CHOICE body into individual field entries.
     * Fields are separated by newlines and/or commas, but commas inside
     * brackets or parentheses (e.g. INTEGER ( CODE("DEC"))) are NOT separators.
     * This lets the same parser handle both multi-line and single-line
     * (inline request) ASN.1 modules.
     */
    private List<String> splitFieldEntries(String rawBody) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < rawBody.length(); i++) {
            char c = rawBody.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if ((c == '\n' || c == ',') && depth == 0) {
                String entry = current.toString().trim();
                if (!entry.isEmpty()) {
                    entries.add(entry);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            entries.add(last);
        }
        return entries;
    }

    private List<AsnField> parseFieldLines(Map<String, AsnTypeDefinition> registry, String rawBody,
                                           Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                           Map<String, List<AsnField>> cache) {
        List<AsnField> fields = new ArrayList<>();
        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            AsnField parsed = parseFieldLine(trimmed);
            if (parsed == null) continue;

            String innerType = parsed.getFieldType();
            boolean repeated = parsed.isRepeated() || isAliasRepeated(registry, innerType);
            List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1, cache);

            fields.add(AsnField.builder()
                    .fieldName(parsed.getFieldName())
                    .fieldType(innerType)
                    .optional(parsed.isOptional())
                    .repeated(repeated)
                    .tagNumber(parsed.getTagNumber())
                    .tagClass(parsed.getTagClass())
                    .explicit(parsed.isExplicit())
                    .children(children.isEmpty() ? null : children)
                    .build());
        }
        return fields;
    }

    private boolean isAliasRepeated(Map<String, AsnTypeDefinition> registry, String typeName) {
        AsnTypeDefinition def = registry.get(typeName);
        return def != null && def.getKind() == AsnTypeKind.ALIAS
                && isRepeatedExpression(stripConstraint(def.getAliasTarget()));
    }

    private AsnField parseFieldLine(String line) {
        boolean optional = line.contains("OPTIONAL");
        Matcher matcher = FIELD_LINE.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String fieldName = matcher.group(1);
        BerTagClass tagClass = matcher.group(2) != null
                ? BerTagClass.valueOf(matcher.group(2))
                : BerTagClass.CONTEXT;
        Integer tagNumber = matcher.group(3) != null ? Integer.valueOf(matcher.group(3)) : null;
        boolean explicit = matcher.group(4) != null
                && matcher.group(4).trim().equalsIgnoreCase(EXPLICIT_KEYWORD);
        String typeExpr = stripConstraint(matcher.group(5)).replace("OPTIONAL", "").trim();

        boolean repeated = isRepeatedExpression(typeExpr);
        String fieldType = repeated ? extractRepeatedInnerType(typeExpr) : normalize(typeExpr);

        return AsnField.builder()
                .fieldName(fieldName)
                .fieldType(fieldType)
                .optional(optional)
                .repeated(repeated)
                .tagNumber(tagNumber)
                .tagClass(tagClass)
                .explicit(explicit)
                .build();
    }

    private boolean isRepeatedExpression(String typeExpr) {
        return typeExpr.startsWith("SEQUENCE OF") || typeExpr.startsWith("SET OF");
    }

    private String extractRepeatedInnerType(String typeExpr) {
        return typeExpr.replaceFirst("^(SEQUENCE|SET)\\s+OF\\s+", "").trim();
    }

    private String normalize(String typeExpr) {
        return typeExpr.replaceAll("\\s+", " ").trim();
    }

    private String stripConstraint(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = current.replaceAll("\\([^()]*\\)", "").trim();
        } while (!current.equals(previous));
        return current;
    }
}