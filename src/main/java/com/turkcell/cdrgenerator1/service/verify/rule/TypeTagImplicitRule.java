package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnDeclaredTagging;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.BerUniversalTag;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Invariant 3: in a module whose header names no tagging mode, a tag written on
 * a TYPE with no keyword of its own replaces that type's universal tag - it does
 * not wrap it.
 *
 * <h2>The measurement</h2>
 *
 * <p>Round 9 sent the two modules built to ask this and EMM refused both, each
 * naming the first field its reading could reach:</p>
 *
 * <pre>
 * FDRInput.NrFile.name was probably not set and is not optional
 * Audit_Record_Collection_St.LogEntry.collectionConfiguration
 *     was probably not set and is not optional
 * </pre>
 *
 * <p>{@code FDRInput} had gone out as {@code 61 37 30 35 62 14 ..}: EMM opens
 * {@code [APPLICATION 1]}, wants {@code name}'s {@code [APPLICATION 2]} as the
 * first content octet and finds {@code 0x30}, the universal SEQUENCE we had
 * wrapped in. Round 10 sent both re-encoded - {@code 61 2D 42 12 ..} and
 * {@code 75 40 16 08 ..} - and both passed. The rule is measured in both
 * directions, which is why it is the first one expressed here.</p>
 *
 * <h2>Why this is not what the walker already does</h2>
 *
 * <p>{@code BerVerifier} compares the bytes against the resolved field tree, and
 * the encoder writes from that same tree - so if the tree ever goes back to
 * wrapping, the bytes follow and nothing objects. This rule reads
 * {@link AsnField#getDeclaredTagging()} instead, which records what the SCHEMA
 * wrote rather than what the encoder decided, so it still fires when the two
 * agree with each other and disagree with EMM. It is also the only check that
 * says anything about a file this application did not produce.</p>
 *
 * <h2>What it deliberately does not touch</h2>
 *
 * <ul>
 * <li>A module that writes {@code IMPLICIT TAGS} - 96 of them, six EMM-accepted.
 *     {@link AsnField#isModuleNamesNoTaggingMode()} keeps the rule out.</li>
 * <li>A site with a written {@code EXPLICIT} or {@code IMPLICIT} keyword: EMM
 *     honours a written keyword (round 11), so only
 *     {@link AsnDeclaredTagging#NONE} is in scope.</li>
 * <li>A tag written on the FIELD rather than on its type. Field tags were
 *     measured separately, on {@code IMSCDRS}, and get their own invariant.</li>
 * <li>A body with fewer than two declared members. A single-member body can
 *     legitimately present one universal container child, and telling that apart
 *     from a wrapper needs more than the shape.</li>
 * </ul>
 */
@Component
public class TypeTagImplicitRule implements VerificationRule {

    /** The universal tags an EXPLICIT wrapper would be holding here (X.690 8.11). */
    private static final Set<Integer> CONTAINER_TAGS = Set.of(
            BerUniversalTag.SEQUENCE.getTagNumber(),
            BerUniversalTag.SET.getTagNumber());

    private static final int WRAPPER_CHILD_COUNT = 1;
    private static final int SMALLEST_UNAMBIGUOUS_BODY = 2;

    @Override
    public String name() {
        return "type-tag-implicit";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField() || nodeContext.collectionWrapper() || !nodeContext.carriesFieldTag()) {
            return;
        }
        AsnField field = nodeContext.field();
        if (!inScope(field)) {
            return;
        }
        TlvNode node = nodeContext.node();
        if (!node.constructed() || node.children().size() != WRAPPER_CHILD_COUNT) {
            return;
        }
        TlvNode only = node.children().get(0);
        if (only.tagClass() != BerTagClass.UNIVERSAL || !CONTAINER_TAGS.contains(only.tagNumber())) {
            return;
        }
        context.report(
                FindingSeverity.ERROR,
                name(),
                context.currentPath(),
                node.start(),
                String.format("%s carries a type-level %s in a module that names no tagging mode, so the tag "
                                + "replaces the universal tag - but %s holds %s as a wrapper. EMM refused this "
                                + "shape on FDRInput and Audit_Record_Collection_St.",
                        field.getFieldName(), node.tagLabel(), node.tagLabel(), only.tagLabel())
        );
    }

    private boolean inScope(AsnField field) {
        List<AsnField> members = field.getChildren();
        return field.isModuleNamesNoTaggingMode()
                && field.isTagDeclaredOnType()
                && field.getDeclaredTagging() == AsnDeclaredTagging.NONE
                && Objects.nonNull(field.getTagNumber())
                && Objects.nonNull(members)
                && members.size() >= SMALLEST_UNAMBIGUOUS_BODY;
    }
}
