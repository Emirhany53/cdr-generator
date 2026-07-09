package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

@Component
public class FieldValueGenerator {

    private final Random random = new Random();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String generateValue(String fieldName, String fieldType) {

        String lowerName = fieldName.toLowerCase();

        if (lowerName.contains("time") || lowerName.contains("date")) {
            return generateTimestamp();
        }

        if (lowerName.contains("msisdn") || lowerName.contains("number") || lowerName.contains("imsi") || lowerName.contains("imei")) {
            return generatePhoneNumber();
        }

        if (lowerName.contains("duration") || lowerName.contains("volume") || lowerName.contains("count")) {
            return String.valueOf(random.nextInt(1000));
        }

        if (fieldType == null) return "UNKNOWN";

        String upperType = fieldType.toUpperCase();

        if (upperType.contains("INTEGER")) {
            return String.valueOf(random.nextInt(100));
        }
        else if (upperType.contains("BOOLEAN")) {
            return random.nextBoolean() ? "1" : "0";
        }
        else if (upperType.contains("IA5STRING") || upperType.contains("OCTET STRING") || upperType.contains("TBCD STRING")) {
            return generateRandomString(8);
        }

        return "VAL" + random.nextInt(999);
    }

    private String generatePhoneNumber() {
        long postfix = 1000000L + (long)(random.nextDouble() * 8999999L);
        return "90532" + postfix;
    }

    private String generateTimestamp() {
        return LocalDateTime.now().format(timeFormatter);
    }

    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}