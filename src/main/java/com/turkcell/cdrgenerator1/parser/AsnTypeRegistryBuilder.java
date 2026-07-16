package com.turkcell.cdrgenerator1.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class AsnTypeRegistryBuilder {

    // Matches a type definition head "TypeName ::=". The name is captured wherever
    // it appears (not only at line start), so inline single-line modules parse too.
    // The ASN.1 module header "ModuleName DEFINITIONS ::=" is skipped explicitly.
    private static final Pattern DEFINITION_START = Pattern.compile("([A-Za-z][\\w-]*)\\s*::=");
    private static final Pattern STRUCTURED_KIND = Pattern.compile("(SEQUENCE|SET|CHOICE|ENUMERATED)");
    private static final String MODULE_HEADER_KEYWORD = "DEFINITIONS";
    // The module header may end with a tagging-mode keyword, e.g.
    // "ModuleName DEFINITIONS IMPLICIT TAGS ::=". In that case the token right
    // before ::= is "TAGS", not a real type name, so it must be skipped too.
    private static final String TAGS_KEYWORD = "TAGS";
    // Captures the module-level tagging mode from the header, when present.
    private static final Pattern TAGGING_MODE_PATTERN = Pattern.compile(
            MODULE_HEADER_KEYWORD + "\\s+(IMPLICIT|EXPLICIT|AUTOMATIC)\\s+" + TAGS_KEYWORD);

    public Map<String, AsnTypeDefinition> buildRegistry(String contents) {
        Map<String, AsnTypeDefinition> registry = new LinkedHashMap<>();
        if (contents == null || contents.isBlank()) {
            return registry;
        }

        String cleaned = stripLineComments(contents);

        List<String> names = new ArrayList<>();
        List<Integer> nameStarts = new ArrayList<>();
        List<Integer> bodyStarts = new ArrayList<>();

        Matcher starts = DEFINITION_START.matcher(cleaned);
        while (starts.find()) {
            // Skip the module header line "ModuleName DEFINITIONS ::=" - the token
            // right before ::= is the DEFINITIONS keyword, not a real type name.
            String precedingToken = cleaned.substring(0, starts.start(1)).trim();
            String candidateName = starts.group(1);
            boolean isModuleHeader = precedingToken.endsWith(MODULE_HEADER_KEYWORD)
                    || MODULE_HEADER_KEYWORD.equals(candidateName);
            // Handles "DEFINITIONS IMPLICIT TAGS ::=", "DEFINITIONS EXPLICIT TAGS ::=",
            // "DEFINITIONS AUTOMATIC TAGS ::=" - the name captured before ::= is "TAGS".
            boolean isTaggingMode = TAGS_KEYWORD.equals(candidateName)
                    && precedingToken.contains(MODULE_HEADER_KEYWORD);
            if (isModuleHeader || isTaggingMode) {
                continue;
            }
            names.add(starts.group(1));
            nameStarts.add(starts.start());
            bodyStarts.add(starts.end());
        }

        for (int i = 0; i < names.size(); i++) {
            int statementEnd = (i + 1 < names.size()) ? nameStarts.get(i + 1) : cleaned.length();
            String statement = cleaned.substring(bodyStarts.get(i), statementEnd);
            registry.putIfAbsent(names.get(i), parseStatement(names.get(i), statement));
        }

        log.debug("Parsed {} ASN.1 type definitions", registry.size());
        return registry;
    }

    /**
     * Reads the module-level tagging mode from the header. Returns
     * {@link AsnTaggingMode#EXPLICIT} when the header omits the keyword, which
     * is the ASN.1 standard default (X.680) - previously the code always assumed
     * IMPLICIT, mis-encoding the majority of modules that rely on the default.
     */
    public AsnTaggingMode detectTaggingMode(String contents) {
        if (contents == null || contents.isBlank()) {
            return AsnTaggingMode.EXPLICIT;
        }
        Matcher matcher = TAGGING_MODE_PATTERN.matcher(stripLineComments(contents));
        if (matcher.find()) {
            return AsnTaggingMode.valueOf(matcher.group(1));
        }
        return AsnTaggingMode.EXPLICIT;
    }

    private AsnTypeDefinition parseStatement(String typeName, String statement) {
        Matcher kindMatcher = STRUCTURED_KIND.matcher(statement);
        int braceIndex = statement.indexOf('{');

        if (kindMatcher.find() && braceIndex != -1 && kindMatcher.start() < braceIndex) {
            AsnTypeKind kind = AsnTypeKind.valueOf(kindMatcher.group(1));
            String body = extractBalancedBody(statement, braceIndex);
            return AsnTypeDefinition.builder()
                    .typeName(typeName)
                    .kind(kind)
                    .rawBody(body)
                    .build();
        }

        return AsnTypeDefinition.builder()
                .typeName(typeName)
                .kind(AsnTypeKind.ALIAS)
                .aliasTarget(statement.trim())
                .build();
    }

    private String extractBalancedBody(String statement, int braceIndex) {
        int depth = 0;
        int bodyStart = braceIndex + 1;
        for (int i = braceIndex; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return statement.substring(bodyStart, i);
                }
            }
        }
        return statement.substring(bodyStart);
    }

    private String stripLineComments(String contents) {
        StringBuilder sb = new StringBuilder(contents.length());
        for (String line : contents.split("\\r?\\n", -1)) {
            int commentIndex = line.indexOf("--");
            sb.append(commentIndex >= 0 ? line.substring(0, commentIndex) : line).append('\n');
        }
        return sb.toString();
    }
}
