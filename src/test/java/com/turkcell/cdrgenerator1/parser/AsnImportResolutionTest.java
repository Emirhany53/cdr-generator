package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.CdrStructureDto;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type reference that crosses a module boundary.
 *
 * <p>Round 14 is why this exists. {@code GSN50} declares
 * {@code information [2] GprsCdrExtensions} and imports that name from
 * {@code GPRS-Charging-Extensions}; nothing read the IMPORTS clause, so the
 * reference resolved to nothing, the field got no children, and the encoder
 * wrote a leaf. EMM answered {@code Invalid length 8 of field
 * GSN50.CallEventRecord.sgsnPDPRecord.recordExtensions.[0].information}.</p>
 */
class AsnImportResolutionTest {

    private static final String EXPORTING_MODULE = """
            Lib DEFINITIONS IMPLICIT TAGS ::=
            BEGIN
            EXPORTS Payload;
            Payload ::= SET {
                plain    [0] IA5String OPTIONAL,
                wrapped  [1] EXPLICIT Choices OPTIONAL,
                bare     [2] Choices OPTIONAL,
                nested   [3] Detail OPTIONAL
            }
            Choices ::= CHOICE { alpha [0] IA5String, beta [1] IA5String }
            Detail ::= SEQUENCE { note [0] IA5String OPTIONAL }
            END
            """;

    private static final String IMPORTING_MODULE = """
            App DEFINITIONS ::=
            BEGIN
            IMPORTS
            Payload
            FROM Lib { itu-t (0) identified-organization (4) etsi (0) };
            Record ::= SEQUENCE {
                id      [0] IA5String OPTIONAL,
                carried [2] Payload OPTIONAL
            }
            END
            """;

    private final AsnTypeRegistryBuilder registryBuilder = new AsnTypeRegistryBuilder();

    private StructureParserService parserOver(Map<String, String> modules) {
        CdrStructureReaderService reader = new CdrStructureReaderService(null, null) {
            @Override
            public List<CdrStructureDto> readAllStructures() {
                return modules.entrySet().stream()
                        .map(entry -> CdrStructureDto.builder()
                                .name(entry.getKey())
                                .contents(entry.getValue())
                                .build())
                        .toList();
            }
        };
        StructureParserService parser =
                new StructureParserService(reader, registryBuilder, new AsnFieldTreeResolver());
        parser.init();
        return parser;
    }

    @Test
    void everySymbolIsReadWithTheModuleItComesFrom() {
        Map<String, String> imports = registryBuilder.readImports("""
                App DEFINITIONS ::=
                BEGIN
                IMPORTS
                Alpha, Beta
                FROM First { itu-t (0) identified-organization (4) }
                Gamma
                FROM Second;
                END
                """);

        assertEquals(Map.of("Alpha", "First", "Beta", "First", "Gamma", "Second"), imports);
    }

    /**
     * The object identifier after a module reference sits exactly where the next
     * clause's symbol list starts. Reading its tokens as symbols would invent
     * imports - {@code itu-t}, {@code identified-organization} - that no module
     * declares, and each invented name would then be looked up in the corpus.
     */
    @Test
    void theObjectIdentifierAfterAModuleNameIsNotReadAsSymbols() {
        Map<String, String> imports = registryBuilder.readImports("""
                App DEFINITIONS ::=
                BEGIN
                IMPORTS
                Alpha
                FROM First { itu-t (0) identified-organization (4) etsi (0) ericsson (5) };
                END
                """);

        assertEquals(Map.of("Alpha", "First"), imports);
    }

    @Test
    void aModuleWithNoImportsClauseReadsAsNoImports() {
        assertTrue(registryBuilder.readImports("""
                App DEFINITIONS ::=
                BEGIN
                Record ::= SEQUENCE { id [0] IA5String OPTIONAL }
                END
                """).isEmpty());
    }

    @Test
    void anImportedTypeResolvesToItsRealStructureInsteadOfALeaf() {
        AsnStructure structure = parserOver(Map.of("Lib", EXPORTING_MODULE, "App", IMPORTING_MODULE))
                .getStructureByName("App");

        assertNotNull(structure);
        AsnField carried = structure.getFields().get(1);
        assertEquals("carried", carried.getFieldName());
        assertNotNull(carried.getChildren(),
                "an imported SET must arrive with its components, not as a childless leaf - "
                        + "a leaf here is what EMM refused in GSN50 as Invalid length 8");
        assertEquals(4, carried.getChildren().size());
    }

    /**
     * The types the export depends on come along even though the EXPORTS clause
     * names only one of them: {@code Detail} is never exported, and without it
     * {@code nested} would be the same childless leaf one level down.
     */
    @Test
    void theTypesTheImportDependsOnComeWithIt() {
        AsnStructure structure = parserOver(Map.of("Lib", EXPORTING_MODULE, "App", IMPORTING_MODULE))
                .getStructureByName("App");

        AsnField nested = structure.getFields().get(1).getChildren().get(3);
        assertEquals("nested", nested.getFieldName());
        assertNotNull(nested.getChildren(), "a type reached only through the imported one must come too");
        assertEquals("note", nested.getChildren().get(0).getFieldName());
    }

    /**
     * The imported subtree is read under ITS module's header, not the importing
     * one's.
     *
     * <p>The distinguishing case is a tag with no written keyword on a
     * CHOICE-typed field. Round 19 widened that rule from "mode-less header"
     * to "any header that does not say EXPLICIT TAGS", which is why the pair
     * here is {@code EXPLICIT TAGS} against a mode-less importer rather than
     * the {@code IMPLICIT TAGS} one it used to be - those two now agree, and a
     * pair that agrees cannot catch a leak. {@code ScopedLib} says
     * {@code EXPLICIT TAGS}, so {@code bare [2] Choices} keeps X.680 8.3's
     * wrapper; if the importer's silence reached in, it would not.</p>
     */
    @Test
    void theImportedSubtreeKeepsItsOwnModulesTaggingMode() {
        String scopedLib = """
                ScopedLib DEFINITIONS EXPLICIT TAGS ::=
                BEGIN
                EXPORTS Payload;
                Payload ::= SET {
                    plain    [0] IA5String OPTIONAL,
                    wrapped  [1] EXPLICIT Choices OPTIONAL,
                    bare     [2] Choices OPTIONAL
                }
                Choices ::= CHOICE { alpha [0] IA5String, beta [1] IA5String }
                END
                """;
        String scopedApp = """
                ScopedApp DEFINITIONS ::=
                BEGIN
                IMPORTS
                Payload
                FROM ScopedLib { itu-t (0) identified-organization (4) etsi (0) };
                Record ::= SEQUENCE {
                    id      [0] IA5String OPTIONAL,
                    carried [2] Payload OPTIONAL
                }
                END
                """;

        AsnStructure structure = parserOver(Map.of("ScopedLib", scopedLib, "ScopedApp", scopedApp))
                .getStructureByName("ScopedApp");
        List<AsnField> payload = structure.getFields().get(1).getChildren();

        AsnField wrapped = payload.get(1);
        assertEquals("wrapped", wrapped.getFieldName());
        assertTrue(wrapped.isExplicit(), "a written EXPLICIT on a CHOICE keeps its wrapper");

        AsnField bare = payload.get(2);
        assertEquals("bare", bare.getFieldName());
        assertFalse(bare.isChoiceTagImplicit(),
                "ScopedLib declares EXPLICIT TAGS, so X.680 8.3 holds for its keyword-less tag "
                        + "on a CHOICE - the importing module naming no mode must not reach in "
                        + "and change how ScopedLib's own types are read");
    }

    /**
     * A local declaration is what the module means by that name, so an import
     * never overwrites one.
     */
    @Test
    void aLocallyDeclaredNameIsNotReplacedByAnImportedOne() {
        String shadowing = """
                App DEFINITIONS ::=
                BEGIN
                IMPORTS
                Payload
                FROM Lib;
                Record ::= SEQUENCE { carried [2] Payload OPTIONAL }
                Payload ::= SEQUENCE { mine [7] IA5String OPTIONAL }
                END
                """;

        AsnStructure structure = parserOver(Map.of("Lib", EXPORTING_MODULE, "App", shadowing))
                .getStructureByName("App");

        AsnField carried = structure.getFields().get(0);
        assertEquals(1, carried.getChildren().size());
        assertEquals("mine", carried.getChildren().get(0).getFieldName());
    }

    /**
     * An import the corpus cannot satisfy degrades to the previous behaviour -
     * a leaf - rather than failing the parse. 17 modules in the shipped data set
     * declare an IMPORTS clause and all 17 resolve, but inline content posted to
     * the API can name anything.
     */
    @Test
    void anUnresolvableImportLeavesTheFieldAsItWas() {
        AsnStructure structure = parserOver(Map.of("App", IMPORTING_MODULE)).getStructureByName("App");

        AsnField carried = structure.getFields().get(1);
        assertEquals("carried", carried.getFieldName());
        assertTrue(carried.getChildren() == null || carried.getChildren().isEmpty(),
                "with no Lib in the corpus the reference stays open, as it did before imports were read");
    }
}
