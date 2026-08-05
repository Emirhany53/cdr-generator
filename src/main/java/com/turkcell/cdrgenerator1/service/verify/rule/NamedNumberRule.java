package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class NamedNumberRule implements VerificationRule {

    private static final Pattern NAMED_NUMBER_PATTERN = Pattern.compile("([A-Za-z][\\w-]*)\\s*\\(\\s*(-?\\d+)\\s*\\)");

    @Override
    public String name() {
        return "named-number";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField()) {
            return;
        }

        TlvNode node = nodeContext.node();
        if (node.constructed()) {
            return;
        }

        if (nodeContext.collectionWrapper()) {
            return;
        }

        AsnField field = nodeContext.field();
        String fieldType = field.getFieldType();
        if (fieldType == null || fieldType.isBlank()) {
            return;
        }

        // Only a numeric type HAS named numbers, and they live only inside the
        // braces the resolver compacts the list into - INTEGER{native(0),...}.
        // Scanning the whole type expression instead matched the CONSTRAINT
        // syntax of ordinary string fields: IA5STRING (SIZE(30) CODE("LEFT"))
        // reads as the named number SIZE(30), so the field's text was decoded as
        // an integer and reported out of range. Thousands of perfectly good
        // fixed-width text fields across the schema were flagged that way.
        BerPrimitiveType primitiveType = BerPrimitiveType.fromTypeExpression(fieldType);
        if (primitiveType != BerPrimitiveType.INTEGER && primitiveType != BerPrimitiveType.ENUMERATED) {
            return;
        }
        String namedNumberBody = bracedBody(fieldType);
        if (namedNumberBody == null) {
            return;
        }

        Map<Long, String> namedNumbers = new LinkedHashMap<>();
        Matcher matcher = NAMED_NUMBER_PATTERN.matcher(namedNumberBody);
        while (matcher.find()) {
            String name = matcher.group(1);
            long value = Long.parseLong(matcher.group(2));
            namedNumbers.put(value, name);
        }

        if (namedNumbers.isEmpty()) {
            return;
        }

        byte[] data = context.data();
        int start = node.valueStart();
        int end = node.valueEnd();
        if (start >= end) {
            return;
        }

        long value = data[start] & 0xFF;
        if ((value & 0x80) != 0) {
            value -= 256;
        }
        for (int i = start + 1; i < end; i++) {
            value = (value << 8) | (data[i] & 0xFF);
        }

        if (!namedNumbers.containsKey(value)) {
            String allowedStr = namedNumbers.entrySet().stream()
                    .map(e -> e.getValue() + "(" + e.getKey() + ")")
                    .collect(Collectors.joining(", "));
            String message = String.format("Value %d not in named numbers {%s}", value, allowedStr);
            context.report(FindingSeverity.ERROR, name(), context.currentPath(), start, message);
        }
    }

    /** The text between the outermost braces, or {@code null} when there are none. */
    private String bracedBody(String fieldType) {
        int open = fieldType.indexOf('{');
        int close = fieldType.lastIndexOf('}');
        return (open >= 0 && close > open) ? fieldType.substring(open + 1, close) : null;
    }
}
