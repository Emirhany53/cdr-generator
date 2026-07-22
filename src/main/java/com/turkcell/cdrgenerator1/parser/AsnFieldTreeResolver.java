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
    private static final Pattern ALIAS_TAG = Pattern.compile(
            "^\\s*\\[\\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*(EXPLICIT\\s+|IMPLICIT\\s+)?");
    private static final int MAX_DEPTH = 15;
    private static final String EXPLICIT_KEYWORD = "EXPLICIT";
    private static final String IMPLICIT_KEYWORD = "IMPLICIT";

    /** Root resolution result: the root type's kind plus its resolved fields. */
    public record ResolvedRoot(AsnTypeKind kind, List<AsnField> fields) {
    }

    // ---------------------------------------------------------------------
    // Legacy field-list API (implicit-default tagging). Kept for callers and
    // tests that only need a SEQUENCE/SET field list without root-kind info.
    // ---------------------------------------------------------------------

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName) {
        return resolveRootFields(registry, rootTypeName, Map.of());
    }

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                            Map<String, String> choiceSelections) {
        return resolveRootFields(registry, rootTypeName, choiceSelections, AsnTaggingMode.IMPLICIT);
    }

    public List<AsnField> resolveRootFields(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                            Map<String, String> choiceSelections, AsnTaggingMode taggingMode) {
        Map<String, List<AsnField>> resolutionCache = new HashMap<>();
        return resolveByTypeName(registry, rootTypeName, choiceSelections, new HashSet<>(), 0,
                resolutionCache, taggingMode);
    }

    // ---------------------------------------------------------------------
    // Root API that preserves the root kind. For a CHOICE root it returns the
    // selected alternative as a single field (tag preserved), so the encoder
    // can emit it directly instead of wrapping it in an artificial SEQUENCE.
    // ---------------------------------------------------------------------

    public ResolvedRoot resolveRoot(Map<String, AsnTypeDefinition> registry, String rootTypeName,
                                    Map<String, String> choiceSelections, AsnTaggingMode taggingMode) {
        Map<String, String> selections = choiceSelections == null ? Map.of() : choiceSelections;

        // Follow alias chains down to the underlying structured type.
        String resolvedName = rootTypeName;
        AsnTypeDefinition current = registry.get(resolvedName);
        Set<String> aliasGuard = new HashSet<>();
        while (current != null && current.getKind() == AsnTypeKind.ALIAS && aliasGuard.add(resolvedName)) {
            String target = stripConstraint(current.getAliasTarget());
            target = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
            resolvedName = target;
            current = registry.get(target);
        }
        if (current == null) {
            return new ResolvedRoot(null, List.of());
        }

        Map<String, List<AsnField>> cache = new HashMap<>();
        if (current.getKind() == AsnTypeKind.CHOICE) {
            AsnField alternative = resolveChoiceRootAlternative(registry, resolvedName, current.getRawBody(),
                    selections, new HashSet<>(), 0, cache, taggingMode);
            return new ResolvedRoot(AsnTypeKind.CHOICE,
                    alternative == null ? List.of() : List.of(alternative));
        }

        List<AsnField> fields = resolveByTypeName(registry, resolvedName, selections, new HashSet<>(), 0,
                cache, taggingMode);
        return new ResolvedRoot(current.getKind(), fields);
    }

    /** Root-level CHOICE metadata: the CHOICE type's own name plus its alternative field names, in declaration order. */
    public record ChoiceAlternatives(String choiceTypeName, List<String> alternativeNames) {
    }

    /**
     * Follows the alias chain from {@code rootTypeName} the same way {@link #resolveRoot} does,
     * and if the resolved type is a CHOICE, returns its type name plus the list of alternative
     * field names (in declaration order) WITHOUT resolving their children. Returns {@code null}
     * when the root does not resolve to a CHOICE. Lets a caller (e.g. a UI) offer a "pick a
     * branch" control before any alternative-specific fields are generated.
     */
    public ChoiceAlternatives listRootChoiceAlternatives(Map<String, AsnTypeDefinition> registry, String rootTypeName) {
        String resolvedName = rootTypeName;
        AsnTypeDefinition current = registry.get(resolvedName);
        Set<String> aliasGuard = new HashSet<>();
        while (current != null && current.getKind() == AsnTypeKind.ALIAS && aliasGuard.add(resolvedName)) {
            String target = stripConstraint(current.getAliasTarget());
            target = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
            resolvedName = target;
            current = registry.get(target);
        }
        if (current == null || current.getKind() != AsnTypeKind.CHOICE) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (String line : splitFieldEntries(current.getRawBody())) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, AsnTaggingMode.IMPLICIT);
            if (alternative != null) {
                names.add(alternative.getFieldName());
            }
        }
        return new ChoiceAlternatives(resolvedName, names);
    }

    private List<AsnField> resolveByTypeName(Map<String, AsnTypeDefinition> registry, String typeName,
                                             Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                             Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
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
            case ALIAS -> resolveAlias(registry, definition, choiceSelections, nextVisiting, depth, cache, taggingMode);
            case SEQUENCE, SET -> parseFieldLines(registry, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, taggingMode);
            case CHOICE -> resolveChoiceAlternative(registry, typeName, definition.getRawBody(), choiceSelections, nextVisiting, depth, cache, taggingMode);
        };

        cache.put(cacheKey, result);
        return result;
    }

    private String buildCacheKey(String typeName, Map<String, String> choiceSelections) {
        return choiceSelections.isEmpty() ? typeName : typeName + "::" + choiceSelections;
    }

    private List<AsnField> resolveAlias(Map<String, AsnTypeDefinition> registry, AsnTypeDefinition definition,
                                        Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                        Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String target = stripConstraint(definition.getAliasTarget());
        String innerType = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        return resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1, cache, taggingMode);
    }

    /** Legacy CHOICE resolution used for nested CHOICE types: flattens to the chosen alternative's fields. */
    private List<AsnField> resolveChoiceAlternative(Map<String, AsnTypeDefinition> registry, String choiceTypeName,
                                                    String rawBody, Map<String, String> choiceSelections,
                                                    Set<String> visiting, int depth,
                                                    Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String preferredAlternative = choiceSelections.get(choiceTypeName);
        AsnField firstAlternative = null;

        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, taggingMode);
            if (alternative == null) continue;

            if (firstAlternative == null) {
                firstAlternative = alternative;
            }
            if (alternative.getFieldName().equals(preferredAlternative)) {
                return resolveAlternative(registry, alternative, choiceSelections, visiting, depth, cache, taggingMode);
            }
        }

        if (firstAlternative != null) {
            if (preferredAlternative != null) {
                log.warn("Choice alternative '{}' not found in '{}', falling back to first alternative '{}'",
                        preferredAlternative, choiceTypeName, firstAlternative.getFieldName());
            }
            return resolveAlternative(registry, firstAlternative, choiceSelections, visiting, depth, cache, taggingMode);
        }
        return List.of();
    }

    /**
     * Root-level CHOICE resolution: returns the selected alternative as a SINGLE
     * field with its own tag preserved and its children resolved. Unlike the
     * nested variant this does NOT flatten, so the encoder can emit the
     * alternative directly (e.g. {@code commandRecord [APPLICATION 0] ...}).
     */
    private AsnField resolveChoiceRootAlternative(Map<String, AsnTypeDefinition> registry, String choiceTypeName,
                                                  String rawBody, Map<String, String> choiceSelections,
                                                  Set<String> visiting, int depth,
                                                  Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String preferredAlternative = choiceSelections.get(choiceTypeName);
        Set<String> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(choiceTypeName);

        AsnField firstAlternative = null;
        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField alternative = parseFieldLine(trimmed, taggingMode);
            if (alternative == null) continue;

            if (firstAlternative == null) {
                firstAlternative = alternative;
            }
            if (alternative.getFieldName().equals(preferredAlternative)) {
                return attachChildren(registry, alternative, choiceSelections, nextVisiting, depth, cache, taggingMode);
            }
        }

        if (firstAlternative != null) {
            if (preferredAlternative != null) {
                log.warn("Choice alternative '{}' not found in '{}', falling back to first alternative '{}'",
                        preferredAlternative, choiceTypeName, firstAlternative.getFieldName());
            }
            return attachChildren(registry, firstAlternative, choiceSelections, nextVisiting, depth, cache, taggingMode);
        }
        return null;
    }

    /** Resolves a field's referenced type into children and finalises its leaf/base type. */
    private AsnField attachChildren(Map<String, AsnTypeDefinition> registry, AsnField field,
                                    Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                    Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        String innerType = field.getFieldType();
        boolean repeated = field.isRepeated() || isAliasRepeated(registry, innerType);
        List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1,
                cache, taggingMode);
        String fieldType = children.isEmpty() ? resolveLeafBaseType(registry, innerType) : innerType;

        Integer tagNumber = field.getTagNumber();
        BerTagClass tagClass = field.getTagClass();
        boolean explicit = field.isExplicit();

        if (tagNumber == null) {
            AsnTypeDefinition aliasDef = registry.get(innerType);
            if (aliasDef != null && aliasDef.getKind() == AsnTypeKind.ALIAS
                    && aliasDef.getAliasTarget() != null) {
                Matcher aliasTag = ALIAS_TAG.matcher(aliasDef.getAliasTarget());
                if (aliasTag.find()) {
                    tagNumber = Integer.valueOf(aliasTag.group(2));
                    tagClass = aliasTag.group(1) != null
                            ? BerTagClass.valueOf(aliasTag.group(1))
                            : BerTagClass.CONTEXT;
                    explicit = aliasTag.group(3) != null
                            ? aliasTag.group(3).trim().equalsIgnoreCase("EXPLICIT")
                            : taggingMode == AsnTaggingMode.EXPLICIT;
                }
            }
        }

        return AsnField.builder()
                .fieldName(field.getFieldName())
                .fieldType(fieldType)
                .optional(field.isOptional())
                .repeated(repeated)
                .tagNumber(tagNumber)
                .tagClass(tagClass)
                .explicit(explicit)
                .children(children.isEmpty() ? null : children)
                .build();
    }

    /**
     * Resolves the chosen CHOICE alternative (nested case). When the alternative
     * is structured its fields are returned; when it is primitive the alternative
     * itself is kept as a single leaf field so the CHOICE never resolves empty.
     */
    private List<AsnField> resolveAlternative(Map<String, AsnTypeDefinition> registry, AsnField alternative,
                                              Map<String, String> choiceSelections, Set<String> visiting, int depth,
                                              Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        List<AsnField> resolved = resolveByTypeName(registry, alternative.getFieldType(),
                choiceSelections, visiting, depth + 1, cache, taggingMode);
        return resolved.isEmpty() ? List.of(alternative) : resolved;
    }

    /**
     * Splits a SEQUENCE/SET/CHOICE body into individual field entries.
     * Fields are separated by newlines and/or commas, but commas inside
     * brackets or parentheses (e.g. INTEGER ( CODE("DEC"))) are NOT separators.
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
                                           Map<String, List<AsnField>> cache, AsnTaggingMode taggingMode) {
        // First pass: parse the raw field heads so AUTOMATIC tagging can decide
        // whether to auto-number (it applies only when NO field is manually tagged).
        List<AsnField> parsedLeaves = new ArrayList<>();
        for (String line : splitFieldEntries(rawBody)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            AsnField parsed = parseFieldLine(trimmed, taggingMode);
            if (parsed != null) {
                parsedLeaves.add(parsed);
            }
        }

        boolean autoAssignTags = taggingMode == AsnTaggingMode.AUTOMATIC
                && parsedLeaves.stream().allMatch(f -> f.getTagNumber() == null);

        // Second pass: assign automatic tags if applicable, then resolve children.
        List<AsnField> fields = new ArrayList<>();
        for (int index = 0; index < parsedLeaves.size(); index++) {
            AsnField parsed = parsedLeaves.get(index);
            if (autoAssignTags) {
                parsed.setTagNumber(index);
                parsed.setTagClass(BerTagClass.CONTEXT);
                parsed.setExplicit(false);
            }

            String innerType = parsed.getFieldType();
            boolean repeated = parsed.isRepeated() || isAliasRepeated(registry, innerType);
            List<AsnField> children = resolveByTypeName(registry, innerType, choiceSelections, visiting, depth + 1,
                    cache, taggingMode);

            String fieldType = children.isEmpty() ? resolveLeafBaseType(registry, innerType) : innerType;

            fields.add(AsnField.builder()
                    .fieldName(parsed.getFieldName())
                    .fieldType(fieldType)
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

    private String resolveLeafBaseType(Map<String, AsnTypeDefinition> registry, String typeName) {
        String current = stripAliasTag(typeName);
        Set<String> guard = new HashSet<>();
        while (current != null && guard.add(current)) {
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null) {
                return current;
            }
            if (definition.getKind() == AsnTypeKind.ENUMERATED) {
                return AsnTypeKind.ENUMERATED.name();
            }
            if (definition.getKind() != AsnTypeKind.ALIAS) {
                return current;
            }
            String target = stripConstraint(definition.getAliasTarget());
            // Alias hedefi "[APPLICATION 2] IA5String" gibi tag önekli olabilir.
            // Tag zaten attachChildren tarafından okundu; burada sadece temel
            // tipin kalması gerekir, yoksa BerUniversalTag tipi tanıyamaz.
            target = stripAliasTag(target);
            current = isRepeatedExpression(target) ? extractRepeatedInnerType(target) : target;
        }
        return current;
    }

    /** Removes a leading tag annotation such as "[APPLICATION 2]" or "[5] IMPLICIT". */
    private String stripAliasTag(String typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        return ALIAS_TAG.matcher(typeExpression).replaceFirst("").trim();
    }

    private boolean isAliasRepeated(Map<String, AsnTypeDefinition> registry, String typeName) {
        AsnTypeDefinition def = registry.get(typeName);
        return def != null && def.getKind() == AsnTypeKind.ALIAS
                && isRepeatedExpression(stripConstraint(def.getAliasTarget()));
    }

    /**
     * Parses a single field/alternative line into an AsnField.
     *
     * <p>The {@code explicit} flag is decided as follows: a per-field
     * {@code EXPLICIT}/{@code IMPLICIT} keyword always wins; otherwise the module
     * default {@code taggingMode} is applied (EXPLICIT default -&gt; explicit,
     * IMPLICIT/AUTOMATIC -&gt; implicit).</p>
     */
    private AsnField parseFieldLine(String line, AsnTaggingMode taggingMode) {
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

        String taggingKeyword = matcher.group(4) != null ? matcher.group(4).trim() : null;
        boolean explicit = resolveExplicit(taggingKeyword, taggingMode);

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

    private boolean resolveExplicit(String taggingKeyword, AsnTaggingMode taggingMode) {
        if (EXPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return true;
        }
        if (IMPLICIT_KEYWORD.equalsIgnoreCase(taggingKeyword)) {
            return false;
        }
        // No per-field keyword: fall back to the module default (X.680: EXPLICIT).
        return taggingMode == AsnTaggingMode.EXPLICIT;
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
