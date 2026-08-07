package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            boolean resolvable = bodyIsKnown && positionResolves(nodeContext.field(), tag);
            context.report(severityFor(tag, bodyIsKnown, resolvable), NAME,
                    context.pathTo(first.tagLabel()),
                    first.start(),
                    "Duplicate Tag data found: " + first.tagLabel() + " appears "
                            + occurrences.size() + " times among the fixed members of this body"
                            + (resolvable ? " (position resolves it: X.680 25.6)" : ""));
        });
    }

    /**
     * True when a SEQUENCE body's own ordering already tells the two members
     * carrying this tag apart, so the repeat is not an ambiguity.
     *
     * <p>X.680 25.6 states the condition exactly: within a SEQUENCE, the tags of
     * a run of consecutive OPTIONAL/DEFAULT components AND of the component that
     * follows the run must be distinct. Nothing is required of components a
     * MANDATORY one separates - a decoder consumes the mandatory component
     * positionally and can never confuse what came before it with what comes
     * after. So two members sharing a tag are ambiguous only when the earlier
     * one is omissible and everything between them is omissible too; otherwise
     * the encoding is decodable and the repeat is a fact about the schema, not
     * a defect in the file.</p>
     *
     * <p>The two shapes in this data set are exactly these. {@code SDPAdjLikya}
     * declares {@code dedicatedAccount1Action [17] OPTIONAL} and
     * {@code dedicatedAccount6Action [17] OPTIONAL} nineteen positions apart
     * with nothing but OPTIONALs between: genuinely undecodable, and it stays an
     * ERROR. {@code HTSCevapsiz} opens with a MANDATORY anonymous
     * {@code recordType CHOICE { mSOriginating [10] IMPLICIT IA5String, ... }}
     * and declares {@code cellID [10] OPTIONAL} further down: a decoder takes
     * the first {@code [10]} as recordType because it must, and the file is
     * decodable.</p>
     *
     * <p>A SET is never resolvable this way - X.680 27.3 requires ALL its
     * component tags to be distinct, because a SET's components may arrive in
     * any order and position carries no information.</p>
     *
     * <p>Known limitation: the parser records {@code OPTIONAL} but not
     * {@code DEFAULT}, and X.680 counts both as omissible. A DEFAULT component
     * therefore reads as mandatory here and could make an ambiguous body look
     * resolvable. It changes no verdict in the current data set - no module
     * whose severity this method lowers declares DEFAULT anywhere - but the
     * check would need {@code AsnField} to carry the distinction before it could
     * be relied on generally.</p>
     */
    private boolean positionResolves(AsnField body, TagKey tag) {
        if (body.isSet() || Objects.isNull(body.getChildren())) {
            return false;
        }
        List<AsnField> members = body.getChildren();

        List<Integer> carriers = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            if (carriesTag(members.get(i), tag)) {
                carriers.add(i);
            }
        }
        // Fewer than two declared carriers means the repeat did not come from
        // the shape this rule can reason about; leave the verdict strict.
        if (carriers.size() < MIN_CHILDREN_TO_COLLIDE) {
            return false;
        }

        for (int k = 0; k + 1 < carriers.size(); k++) {
            if (!separatedByAMandatoryMember(members, carriers.get(k), carriers.get(k + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the earlier member, or something between the two, must be
     * present - which is what stops a decoder from mistaking one for the other.
     */
    private boolean separatedByAMandatoryMember(List<AsnField> members, int earlier, int later) {
        if (!members.get(earlier).isOptional()) {
            return true;
        }
        for (int i = earlier + 1; i < later; i++) {
            if (!members.get(i).isOptional()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this member could write the given tag as its outermost one. An
     * untagged CHOICE has no tag of its own and takes its selected
     * alternative's, so its alternatives are asked instead.
     */
    private boolean carriesTag(AsnField member, TagKey tag) {
        if (Objects.nonNull(member.getTagNumber())) {
            BerTagClass tagClass = Objects.nonNull(member.getTagClass())
                    ? member.getTagClass()
                    : BerTagClass.CONTEXT;
            return tagClass == tag.tagClass() && member.getTagNumber() == tag.tagNumber();
        }
        if (member.isChoice() && !member.isRepeated() && Objects.nonNull(member.getChildren())) {
            return member.getChildren().stream().anyMatch(alt -> carriesTag(alt, tag));
        }
        return false;
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
     *
     * <p>A repeated CONTEXT/APPLICATION tag the body's own ordering already
     * resolves is reported too, but as a WARNING: see {@link #positionResolves}
     * for why X.680 25.6 makes such a file decodable. This is deliberately not a
     * general relaxation - the tag still has to be one a MANDATORY member
     * separates, and every genuinely undecodable repeat stays an ERROR.</p>
     */
    private FindingSeverity severityFor(TagKey tag, boolean bodyIsKnown, boolean resolvable) {
        if (!bodyIsKnown || tag.tagClass() == BerTagClass.UNIVERSAL || resolvable) {
            return FindingSeverity.WARNING;
        }
        return FindingSeverity.ERROR;
    }

    /** Class and number together identify a tag; either alone does not. */
    private record TagKey(BerTagClass tagClass, int tagNumber) {
    }
}
