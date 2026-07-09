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

    private static final Pattern DEFINITION_START = Pattern.compile("(?m)^\\s*([A-Za-z][\\w-]*)\\s*::=");
    private static final Pattern STRUCTURED_KIND = Pattern.compile("(SEQUENCE|SET|CHOICE|ENUMERATED)");

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