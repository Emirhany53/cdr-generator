package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two members of the same fixed SET or SEQUENCE body carrying the same tag.
 *
 * <p>This is the one error EMM has actually sent, verbatim:</p>
 *
 * <pre>
 * Duplicate Tag data found for MMTelChargingDataTypes.MMTelServiceRecord
 *     .mMTelRecord.recordExtensions.enhancedPhoneFeatures1.[0]
 * </pre>
 *
 * <p>X.680 27.3 requires the components of a SET to carry distinct tags, and
 * 24.3 the same for a SEQUENCE, precisely so a decoder can tell them apart. A
 * record that breaks it is not decodable, whatever else is right about it.</p>
 *
 * <p>The one shape that legitimately repeats a tag is a collection: every
 * element of a {@code SEQUENCE OF Epf1Service} is a {@code 31}, and that is
 * correct. The container and its elements share one {@link
 * com.turkcell.cdrgenerator1.model.AsnField}, so only
 * {@link NodeContext#collectionWrapper()} distinguishes them - which is why
 * this rule reads that flag rather than {@code field.isRepeated()}.</p>
 */
@Component
public class DuplicateTagRule implements VerificationRule {

    private static final String NAME = "duplicate-tag";
    /** Fewer than two children can never collide. */
    private static final int MIN_CHILDREN_TO_COLLIDE = 2;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        TlvNode node = nodeContext.node();
        if (node.isPrimitive() || node.children().size() < MIN_CHILDREN_TO_COLLIDE) {
            return;
        }
        // A collection's elements are supposed to share a tag.
        if (nodeContext.collectionWrapper()) {
            return;
        }

        Map<TagKey, List<TlvNode>> byTag = new LinkedHashMap<>();
        for (TlvNode child : node.children()) {
            byTag.computeIfAbsent(new TagKey(child.tagClass(), child.tagNumber()),
                    key -> new ArrayList<>()).add(child);
        }

        // Without a matched field the walker cannot promise this body is fixed
        // rather than an unrecognised collection, so the verdict is downgraded
        // instead of guessed.
        boolean bodyIsKnown = nodeContext.hasField();

        byTag.forEach((tag, occurrences) -> {
            if (occurrences.size() < MIN_CHILDREN_TO_COLLIDE) {
                return;
            }
            TlvNode first = occurrences.get(0);
            context.report(severityFor(tag, bodyIsKnown), NAME,
                    context.pathTo(first.tagLabel()),
                    first.start(),
                    "Duplicate Tag data found: " + first.tagLabel() + " appears "
                            + occurrences.size() + " times among the fixed members of this body");
        });
    }

    /**
     * How much a repeated tag is worth complaining about.
     *
     * <p>A repeated CONTEXT/APPLICATION tag is the error EMM sends, and it is
     * FIXABLE: the schema gave two members the same {@code [n]}, and changing
     * one of them resolves it. That stays an ERROR, so STRICT mode refuses to
     * hand over a file EMM would reject anyway.</p>
     *
     * <p>A repeated UNIVERSAL tag says something different: the body declares
     * several UNTAGGED members of the same type, as {@code ALLOPTIONAL ::=
     * SEQUENCE { reportId IA5String OPTIONAL, reportVersion IA5String OPTIONAL,
     * ... }} does twelve times over. X.680 25.6 makes that ambiguous and it is
     * worth reporting - but there is no other tag the encoder could legally
     * write, so it is not a defect in anything this application controls.
     * Around 120 of the 808 modules, mostly DB lookup tables, are shaped that
     * way; grading it ERROR would mean STRICT mode could no longer generate a
     * file for any of them, which would remove working functionality to
     * restate a fact about the vendored .asn1 text.</p>
     */
    private FindingSeverity severityFor(TagKey tag, boolean bodyIsKnown) {
        if (!bodyIsKnown || tag.tagClass() == BerTagClass.UNIVERSAL) {
            return FindingSeverity.WARNING;
        }
        return FindingSeverity.ERROR;
    }

    /** Class and number together identify a tag; either alone does not. */
    private record TagKey(BerTagClass tagClass, int tagNumber) {
    }
}
