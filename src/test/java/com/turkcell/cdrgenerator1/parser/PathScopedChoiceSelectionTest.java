package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CHOICE can be selected per CALL SITE, not only per type.
 *
 * <h2>The problem</h2>
 *
 * <p>{@code AsnFieldTreeResolver.resolveChoiceAlternative} reads the wanted
 * alternative as {@code choiceSelections.get(choiceTypeName)}, so every site
 * sharing an ASN.1 type receives the same answer. The EMM-accepted MMTel
 * reference needs {@code called-Party-Address} to be {@code sIP-URI} while
 * {@code requested-Party-Address} is {@code tEL-URI} - in one record, both of
 * type {@code InvolvedParty}. A single global answer cannot express that, and
 * it is the reason one reference value stayed unreachable after KN-1 and
 * KN-4.</p>
 *
 * <h2>What must not change</h2>
 *
 * <p>Selection by type name still works exactly as before, and a caller who
 * passes no path key sees no difference at all. An ASN.1 type reference cannot
 * contain a dot, so the two key spaces cannot collide.</p>
 */
class PathScopedChoiceSelectionTest {

    private static final String MODULE = """
            TestParty DEFINITIONS IMPLICIT TAGS ::= BEGIN

            Record ::= SEQUENCE
            {
                sessionId       [0] IA5String,
                calledParty     [1] InvolvedParty OPTIONAL,
                requestedParty  [2] InvolvedParty OPTIONAL
            }

            InvolvedParty ::= CHOICE
            {
                sipUri  [0] IA5String,
                telUri  [1] IA5String
            }

            END
            """;

    private StructureParserService parser() {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDataStructurePath("src/main/resources/datastructure.json");
        StructureParserService service = new StructureParserService(
                new CdrStructureReaderService(config, new ObjectMapper()),
                new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        return service;
    }

    private AsnField child(AsnStructure structure, String fieldName) {
        return structure.getFields().stream()
                .filter(f -> fieldName.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field not resolved: " + fieldName));
    }

    private String chosenAlternative(AsnStructure structure, String fieldName) {
        List<AsnField> children = child(structure, fieldName).getChildren();
        assertThat(children).as("a CHOICE resolves to exactly one alternative").hasSize(1);
        return children.get(0).getFieldName();
    }

    // ---------------------------------------------------------------- existing behaviour

    @Test
    void withNoSelectionAtAllBothSitesTakeTheFirstAlternative() {
        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, Map.of());

        assertThat(chosenAlternative(structure, "calledParty")).isEqualTo("sipUri");
        assertThat(chosenAlternative(structure, "requestedParty")).isEqualTo("sipUri");
    }

    @Test
    void aTypeNameSelectionStillAppliesToEverySiteOfThatType() {
        AsnStructure structure = parser().parseFromContents(
                "TestParty", MODULE, Map.of("InvolvedParty", "telUri"));

        assertThat(chosenAlternative(structure, "calledParty"))
                .as("the global, type-keyed selection is untouched")
                .isEqualTo("telUri");
        assertThat(chosenAlternative(structure, "requestedParty")).isEqualTo("telUri");
    }

    // ---------------------------------------------------------------- path-scoped

    /**
     * The case KN-3 exists for: one type, two sites, two different alternatives
     * in the same resolved tree.
     */
    @Test
    void twoSitesOfOneTypeCanTakeDifferentAlternatives() {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put("InvolvedParty", "sipUri");          // global default
        selections.put("requestedParty", "telUri");         // this site only

        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, selections);

        assertThat(chosenAlternative(structure, "calledParty")).isEqualTo("sipUri");
        assertThat(chosenAlternative(structure, "requestedParty")).isEqualTo("telUri");
    }

    /**
     * A path key beats the type-name key at its own site and leaves every other
     * site on the global pick.
     */
    @Test
    void aPathKeyOverridesTheTypeKeyOnlyWhereItPoints() {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put("InvolvedParty", "telUri");
        selections.put("calledParty", "sipUri");

        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, selections);

        assertThat(chosenAlternative(structure, "calledParty")).isEqualTo("sipUri");
        assertThat(chosenAlternative(structure, "requestedParty")).isEqualTo("telUri");
    }

    /**
     * An override naming an alternative the CHOICE does not declare leaves the
     * tree on its global pick rather than emptying the field.
     */
    @Test
    void anUnknownAlternativeLeavesTheGlobalPickInPlace() {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put("InvolvedParty", "sipUri");
        selections.put("requestedParty", "noSuchAlternative");

        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, selections);

        assertThat(chosenAlternative(structure, "requestedParty"))
                .as("an unresolvable override must not leave the field without an alternative")
                .isEqualTo("sipUri");
    }

    /**
     * A path key that points at no field in this tree is inert - it must not
     * disturb the sites that resolved normally.
     */
    @Test
    void aPathKeyForAFieldThatDoesNotExistChangesNothing() {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put("InvolvedParty", "sipUri");
        selections.put("nowhere.at.all", "telUri");

        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, selections);

        assertThat(chosenAlternative(structure, "calledParty")).isEqualTo("sipUri");
        assertThat(chosenAlternative(structure, "requestedParty")).isEqualTo("sipUri");
    }

    /**
     * The replacement alternative must be a fully resolved field, not a husk:
     * it carries its own tag, so the encoder writes the branch the caller asked
     * for rather than an untagged blank.
     */
    @Test
    void theReplacementAlternativeKeepsItsOwnTag() {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put("InvolvedParty", "sipUri");
        selections.put("requestedParty", "telUri");

        AsnStructure structure = parser().parseFromContents("TestParty", MODULE, selections);
        AsnField telUri = child(structure, "requestedParty").getChildren().get(0);

        assertThat(telUri.getFieldName()).isEqualTo("telUri");
        assertThat(telUri.getTagNumber())
                .as("telUri is [1] in the CHOICE and must stay [1] after the swap")
                .isEqualTo(1);
    }
}
