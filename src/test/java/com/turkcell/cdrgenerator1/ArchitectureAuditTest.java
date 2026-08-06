package com.turkcell.cdrgenerator1;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import com.turkcell.cdrgenerator1.model.RecordFieldKeys;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTaggingMode;
import com.turkcell.cdrgenerator1.parser.AsnTypeDefinition;
import com.turkcell.cdrgenerator1.parser.AsnTypeKind;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.SetOrderingRule;
import com.turkcell.cdrgenerator1.service.verify.rule.TagShapeRule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole-schema architecture audit: walks every module in the real
 * {@code datastructure.json}, counts each known structural defect class, and
 * writes one TSV row per module to {@code target/audit/audit.tsv}.
 *
 * <p>The row carries a SHA-256 of the module's encoded record, so two runs can
 * be diffed to answer the only question that matters when the parser changes:
 * <em>which modules' bytes moved, and did the reference-verified MMTel family
 * stay untouched?</em></p>
 *
 * <p>The record is NOT the generator's random one - it is built here from the
 * resolved field tree with a fixed value per leaf, so the hash reflects the
 * parser, the resolver and the encoder, and nothing else. Generation-time
 * behaviour (value sources, timestamps, repeat counts) is covered by
 * {@code AllModulesRoundTripTest} and the generator's own tests.</p>
 */
class ArchitectureAuditTest {

    private static final Path AUDIT_FILE = Path.of("target", "audit", "audit.tsv");
    private static final Path FINDINGS_FILE = Path.of("target", "audit", "findings.tsv");

    /** "X ::= [APPLICATION 1] SEQUENCE {" - a tag carried by a structured type. */
    private static final Pattern TYPE_LEVEL_TAG = Pattern.compile(
            "(?m)^[^-\\n]*?\\b([A-Za-z][\\w-]*)\\s*::=\\s*\\[\\s*(APPLICATION|UNIVERSAL|PRIVATE)?\\s*(\\d+)\\s*\\]"
                    + "\\s*(IMPLICIT|EXPLICIT)?\\s*(SEQUENCE|SET|CHOICE)\\s*\\{");

    /** A leading tag on an ALIAS target: "X ::= [APPLICATION 196] PlmnId". */
    private static final Pattern ALIAS_TAG = Pattern.compile(
            "^\\s*\\[\\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*(EXPLICIT\\s+|IMPLICIT\\s+)?");

    /** One field entry: "name [3] IMPLICIT Type OPTIONAL". */
    private static final Pattern FIELD_ENTRY = Pattern.compile(
            "^\\s*([A-Za-z][\\w-]*)\\s+(?:\\[\\s*(?:(APPLICATION|UNIVERSAL|PRIVATE)\\s+)?(\\d+)\\s*\\]\\s*"
                    + "(IMPLICIT|EXPLICIT)?\\s*)?(.+)$", Pattern.DOTALL);

    private static final Set<String> PRIMITIVE_KEYWORDS = Set.of(
            "BOOLEAN", "INTEGER", "BIT", "OCTET", "NULL", "OBJECT", "REAL", "ENUMERATED",
            "SEQUENCE", "SET", "CHOICE", "IA5String", "IA5STRING", "UTF8String", "PrintableString",
            "NumericString", "VisibleString", "GraphicString", "GeneralString", "TeletexString",
            "VideotexString", "BMPString", "UniversalString", "UTCTime", "GeneralizedTime", "ANY");

    private static final String HEADER = String.join("\t", "module", "mode", "root", "rootTagged",
            "declStructTagged", "declAliasTagged", "r1AltSites", "r1FieldSites", "r2FieldSites",
            "r2ChoiceOk", "r3SkipFields", "r3SkipRepeated", "r5ExplicitFields", "r6UnmatchedBodies",
            "r9UnknownTypes", "mmtelCore", "mmtelFamily", "dupUniversal", "dupOther", "topFields", "leaves",
            "bytes", "sha256", "vErrors", "vWarnings", "vRules");

    @Test
    void auditEveryModule() throws Exception {
        CdrConfigProperties cdrConfig = new CdrConfigProperties();
        cdrConfig.setDataStructurePath("src/main/resources/datastructure.json");
        cdrConfig.setDefaultRecordCount(1);
        cdrConfig.setMaxRecordCount(100);

        CdrStructureReaderService reader = new CdrStructureReaderService(cdrConfig, new ObjectMapper());
        AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();
        StructureParserService parser = new StructureParserService(
                reader, registryBuilder, new AsnFieldTreeResolver());
        parser.init();

        BerEncoderService encoder = new BerEncoderService(
                new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));
        SelfCheckProperties selfCheck = new SelfCheckProperties();
        selfCheck.setMode(SelfCheckProperties.Mode.WARN);
        // Only the STRUCTURAL rules run here. NamedNumberRule and IntegerRangeRule
        // judge the VALUE, and this harness writes a fixed synthetic value into
        // every leaf - they would report thousands of findings that say nothing
        // about the parser, the resolver or the encoder. AllModulesRoundTripTest
        // runs the full rule set against generated values.
        BerVerifier verifier = new BerVerifier(new TlvReader(), selfCheck, List.of(
                new DuplicateTagRule(), new SetOrderingRule(), new TagShapeRule()));

        Method selectRoot = StructureParserService.class.getDeclaredMethod(
                "selectRootTypeName", Map.class, Map.class, AsnTaggingMode.class);
        selectRoot.setAccessible(true);

        Map<String, AsnStructure> parsed = parser.getAllParsedStructures();
        List<String> rows = new ArrayList<>();
        rows.add(HEADER);
        List<String> findings = new ArrayList<>();
        findings.add(String.join("\t", "module", "severity", "rule", "path", "message"));

        for (CdrStructureDto dto : reader.readAllStructures()) {
            String contents = dto.getContents();
            if (contents == null || contents.isBlank()) {
                continue;
            }
            Map<String, AsnTypeDefinition> registry = registryBuilder.buildRegistry(contents);
            AsnTaggingMode mode = registryBuilder.detectTaggingMode(contents);

            Map<String, String> structTagged = new LinkedHashMap<>();
            Matcher tagged = TYPE_LEVEL_TAG.matcher(contents);
            while (tagged.find()) {
                structTagged.put(tagged.group(1),
                        (tagged.group(2) == null ? "CONTEXT" : tagged.group(2)) + " " + tagged.group(3));
            }
            Map<String, String> aliasTagged = new LinkedHashMap<>();
            for (Map.Entry<String, AsnTypeDefinition> entry : registry.entrySet()) {
                AsnTypeDefinition definition = entry.getValue();
                if (definition.getKind() != AsnTypeKind.ALIAS || definition.getAliasTarget() == null) {
                    continue;
                }
                Matcher aliasTag = ALIAS_TAG.matcher(definition.getAliasTarget());
                if (aliasTag.find()) {
                    aliasTagged.put(entry.getKey(), aliasTag.group(1) == null ? "CONTEXT" : aliasTag.group(1));
                }
            }

            int[] schema = scanSchema(registry, structTagged, aliasTagged, mode);

            String root = "-";
            try {
                root = (String) selectRoot.invoke(parser, registry, Map.of(), mode);
            } catch (ReflectiveOperationException ignored) {
                // keep "-"
            }
            AsnTypeDefinition involvedParty = registry.get("InvolvedParty");
            boolean mmtelFamily = involvedParty != null && involvedParty.getKind() == AsnTypeKind.CHOICE;
            // The modules whose bytes a real EMM-accepted reference capture covers.
            // These may never move; the wider InvolvedParty family (IMS/ATS/UAG)
            // shares the shape but has no capture of its own.
            boolean mmtelCore = dto.getName().startsWith("MMTelChargingDataTypes");

            AsnStructure structure = parsed.get(dto.getName());
            int topFields = 0;
            int leaves = 0;
            int skipFields = 0;
            int skipRepeated = 0;
            int explicitFields = 0;
            int unmatched = 0;
            int dupUniversal = 0;
            int dupOther = 0;
            int errors = -1;
            int warnings = -1;
            int length = -1;
            String sha = "-";
            String rules = "-";

            if (structure != null && structure.getFields() != null && !structure.getFields().isEmpty()) {
                topFields = structure.getFields().size();
                leaves = countLeaves(structure.getFields());
                int[] skip = new int[2];
                countSkippable(structure.getFields(), skip);
                skipFields = skip[0];
                skipRepeated = skip[1];
                explicitFields = countExplicit(structure.getFields());

                try {
                    Map<String, Object> record = fixedRecord(structure.getFields());
                    byte[] bytes = encoder.encodeRecord(structure, record);
                    length = bytes.length;
                    sha = sha256(bytes);

                    BerVerificationResult result = verifier.verify(structure, bytes);
                    errors = result.errors().size();
                    warnings = result.findings().size() - errors;
                    Map<String, Integer> byRule = new TreeMap<>();
                    for (BerFinding finding : result.findings()) {
                        byRule.merge(finding.ruleName(), 1, Integer::sum);
                        findings.add(String.join("\t", dto.getName(), finding.severity().name(),
                                finding.ruleName(), finding.path(), finding.message()));
                        if ("duplicate-tag".equals(finding.ruleName())) {
                            if (finding.path().contains(".U-[")) {
                                dupUniversal++;
                            } else {
                                dupOther++;
                            }
                        }
                        if ("walker".equals(finding.ruleName())
                                && finding.message().startsWith("No field of this body carries tag")) {
                            unmatched++;
                        }
                    }
                    rules = byRule.toString();
                } catch (Exception e) {
                    sha = "ENCODE_FAILED:" + e.getClass().getSimpleName();
                }
            }

            rows.add(String.join("\t", dto.getName(), mode.name(), root,
                    String.valueOf(structTagged.containsKey(root)),
                    String.valueOf(structTagged.size()), String.valueOf(aliasTagged.size()),
                    String.valueOf(schema[0]), String.valueOf(schema[1]), String.valueOf(schema[2]),
                    String.valueOf(schema[3]), String.valueOf(skipFields), String.valueOf(skipRepeated),
                    String.valueOf(explicitFields), String.valueOf(unmatched), String.valueOf(schema[4]),
                    String.valueOf(mmtelCore), String.valueOf(mmtelFamily), String.valueOf(dupUniversal), String.valueOf(dupOther),
                    String.valueOf(topFields), String.valueOf(leaves), String.valueOf(length), sha,
                    String.valueOf(errors), String.valueOf(warnings), rules));
        }

        Files.createDirectories(AUDIT_FILE.getParent());
        Files.write(AUDIT_FILE, rows, StandardCharsets.UTF_8);
        Files.write(FINDINGS_FILE, findings, StandardCharsets.UTF_8);
        System.out.println("AUDIT written to " + AUDIT_FILE.toAbsolutePath() + " (" + (rows.size() - 1) + " modules)");
    }

    /** {r1Alt, r1Field, r2Field, r2ChoiceOk, unknownTypeRefs} counted over the module's declarations. */
    private int[] scanSchema(Map<String, AsnTypeDefinition> registry, Map<String, String> structTagged,
                             Map<String, String> aliasTagged, AsnTaggingMode mode) {
        int r1Alt = 0;
        int r1Field = 0;
        int r2Field = 0;
        int r2ChoiceOk = 0;
        int unknown = 0;

        for (Map.Entry<String, AsnTypeDefinition> entry : registry.entrySet()) {
            AsnTypeDefinition definition = entry.getValue();
            AsnTypeKind kind = definition.getKind();
            if (kind != AsnTypeKind.SEQUENCE && kind != AsnTypeKind.SET && kind != AsnTypeKind.CHOICE) {
                continue;
            }
            boolean inChoice = kind == AsnTypeKind.CHOICE;
            for (String entryText : splitEntries(definition.getRawBody())) {
                Matcher field = FIELD_ENTRY.matcher(entryText.trim());
                if (!field.matches()) {
                    continue;
                }
                boolean ownTag = field.group(3) != null;
                String typeToken = firstTypeToken(field.group(5));
                if (typeToken == null) {
                    continue;
                }
                if (!ownTag) {
                    String resolved = followUntaggedAliases(registry, typeToken, aliasTagged, structTagged);
                    if (structTagged.containsKey(resolved)) {
                        if (inChoice) {
                            r1Alt++;
                        } else {
                            r1Field++;
                        }
                    } else if (aliasTagged.containsKey(resolved)
                            && !"UNIVERSAL".equals(aliasTagged.get(resolved))) {
                        if (inChoice) {
                            r2ChoiceOk++;
                        } else {
                            r2Field++;
                        }
                    }
                }
                if (!registry.containsKey(typeToken) && !PRIMITIVE_KEYWORDS.contains(typeToken)) {
                    unknown++;
                }
            }
        }
        return new int[]{r1Alt, r1Field, r2Field, r2ChoiceOk, unknown};
    }

    /**
     * One record with a fixed value per leaf, shaped exactly like the map
     * {@code CdrRecordBuilder} produces (same keys, lists for repeated fields).
     */
    private Map<String, Object> fixedRecord(List<AsnField> fields) {
        Map<String, Object> record = new LinkedHashMap<>();
        List<String> keys = RecordFieldKeys.forFields(fields);
        for (int index = 0; index < fields.size(); index++) {
            record.put(keys.get(index), fixedValue(fields.get(index)));
        }
        return record;
    }

    private Object fixedValue(AsnField field) {
        Object scalar = field.getChildren() == null || field.getChildren().isEmpty()
                ? fixedLeafValue(field)
                : fixedRecord(field.getChildren());
        return field.isRepeated() ? List.of(scalar, scalar) : scalar;
    }

    private String fixedLeafValue(AsnField field) {
        BerPrimitiveType type = BerPrimitiveType.fromTypeExpression(field.getFieldType());
        return switch (type) {
            case BOOLEAN -> "1";
            case INTEGER, REAL -> "7";
            case NULL -> "";
            case OBJECT_IDENTIFIER -> "1.2.826";
            default -> "42";
        };
    }

    private List<String> splitEntries(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char c = body.charAt(index);
            if (c == '{' || c == '(') {
                depth++;
            } else if (c == '}' || c == ')') {
                depth--;
            } else if ((c == ',' || c == '\n') && depth == 0) {
                out.add(body.substring(start, index));
                start = index + 1;
            }
        }
        out.add(body.substring(start));
        return out;
    }

    private String firstTypeToken(String typeExpression) {
        String text = typeExpression.trim().replaceAll("^(SEQUENCE|SET)\\s+OF\\s+", "");
        Matcher token = Pattern.compile("^([A-Za-z][\\w-]*)").matcher(text);
        if (!token.find()) {
            return null;
        }
        String name = token.group(1);
        return name.equals("OPTIONAL") || name.equals("DEFAULT") ? null : name;
    }

    private String followUntaggedAliases(Map<String, AsnTypeDefinition> registry, String typeToken,
                                         Map<String, String> aliasTagged, Map<String, String> structTagged) {
        String current = typeToken;
        Set<String> guard = new HashSet<>();
        while (guard.add(current)) {
            if (aliasTagged.containsKey(current) || structTagged.containsKey(current)) {
                return current;
            }
            AsnTypeDefinition definition = registry.get(current);
            if (definition == null || definition.getKind() != AsnTypeKind.ALIAS
                    || definition.getAliasTarget() == null) {
                return current;
            }
            String next = firstTypeToken(definition.getAliasTarget());
            if (next == null || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }

    private int countLeaves(List<AsnField> fields) {
        int count = 0;
        for (AsnField field : fields) {
            if (field.getChildren() == null || field.getChildren().isEmpty()) {
                count++;
            } else {
                count += countLeaves(field.getChildren());
            }
        }
        return count;
    }

    private int countExplicit(List<AsnField> fields) {
        int count = 0;
        for (AsnField field : fields) {
            if (field.isExplicit()) {
                count++;
            }
            if (field.getChildren() != null) {
                count += countExplicit(field.getChildren());
            }
        }
        return count;
    }

    private void countSkippable(List<AsnField> fields, int[] accumulator) {
        for (AsnField field : fields) {
            if (field.isChoice() && !field.isExplicit() && field.isOptional()) {
                accumulator[0]++;
                if (field.isRepeated()) {
                    accumulator[1]++;
                }
            }
            if (field.getChildren() != null) {
                countSkippable(field.getChildren(), accumulator);
            }
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
