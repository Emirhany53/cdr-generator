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
        FindingSeverity severity = nodeContext.hasField()
                ? FindingSeverity.ERROR
                : FindingSeverity.WARNING;

        byTag.forEach((tag, occurrences) -> {
            if (occurrences.size() < MIN_CHILDREN_TO_COLLIDE) {
                return;
            }
            TlvNode first = occurrences.get(0);
            context.report(severity, NAME,
                    context.pathTo(first.tagLabel()),
                    first.start(),
                    "Duplicate Tag data found: " + first.tagLabel() + " appears "
                            + occurrences.size() + " times among the fixed members of this body");
        });
    }

    /** Class and number together identify a tag; either alone does not. */
    private record TagKey(BerTagClass tagClass, int tagNumber) {
    }
}
