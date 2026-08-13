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

/**
 * Invariant 2: in a module whose header names no tagging mode, a tag written on
 * a FIELD with no keyword of its own replaces the value's universal tag - it
 * does not wrap it.
 *
 * <h2>The measurement</h2>
 *
 * <p>{@code IMSCDRS} declares {@code IMSCDRS DEFINITIONS ::=}, no keyword. Its
 * {@code TokensCSCF} record was offered to the CSCFColl flow five times in the
 * standard-conforming EXPLICIT form, in four different framings, and refused
 * every time - "Invalid length N of field IMSCDRS.TokensCSCF", where N was the
 * whole file each time, so neither a size limit nor the length form. The same
 * record with its fields IMPLICIT ({@code 81 30 ..} rather than
 * {@code A1 32 16 30 ..}) was accepted on the first attempt, and EMM returned
 * its decode: all 34 fields, every value matching what had been generated.</p>
 *
 * <p>This is the widest of the three invariants - 550 modules carry a bare
 * {@code [n]} field tag under a keyword-less header - and the one whose rule
 * {@code 5906e76} applied to 712 modules on the strength of a single accepted
 * file. That is precisely why it is worth asserting on the bytes.</p>
 *
 * <h2>Shape</h2>
 *
 * <p>An EXPLICIT wrapper is a constructed node holding exactly one child, and
 * that child is the universal TLV the field's own type would have produced
 * untagged. For a primitive the implicit form is not constructed at all, so the
 * check cannot misfire there. For a body it can, which is what the two-member
 * floor below is for.</p>
 *
 * <h2>What it deliberately does not touch</h2>
 *
 * <ul>
 * <li>Modules that write {@code IMPLICIT TAGS} - 96 of them, six EMM-accepted,
 *     MMTel matched against a real capture besides.</li>
 * <li>A site with a written {@code EXPLICIT} or {@code IMPLICIT}: EMM honours a
 *     written keyword (round 11), so only {@link AsnDeclaredTagging#NONE} is in
 *     scope.</li>
 * <li>Tags written on a TYPE - {@link TypeTagImplicitRule} covers those, and the
 *     two were measured on different modules in different rounds.</li>
 * <li>A CHOICE. X.680 8.3 makes a tag on a CHOICE explicit, and the one case
 *     where EMM disagreed is already carried by
 *     {@link AsnField#isChoiceTagImplicit()}. Re-deciding it here would put two
 *     rules on one question.</li>
 * <li>A repeated field. Under the implicit reading its node holds the elements
 *     directly, so a collection that happens to carry exactly one SEQUENCE
 *     element is indistinguishable from a wrapper by shape alone. The
 *     {@code SEQUENCE OF <CHOICE>} rule belongs to invariant 1 anyway.</li>
 * <li>A body with fewer than two declared members, for the same reason
 *     {@link TypeTagImplicitRule} skips it.</li>
 * </ul>
 */
@Component
public class FieldTagImplicitRule implements VerificationRule {

    private static final int WRAPPER_CHILD_COUNT = 1;
    private static final int SMALLEST_UNAMBIGUOUS_BODY = 2;

    @Override
    public String name() {
        return "field-tag-implicit";
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
        Integer wrapped = universalTagTheValueWouldCarry(field);
        TlvNode only = node.children().get(0);
        if (Objects.isNull(wrapped)
                || only.tagClass() != BerTagClass.UNIVERSAL
                || only.tagNumber() != wrapped) {
            return;
        }
        context.report(
                FindingSeverity.ERROR,
                name(),
                context.currentPath(),
                node.start(),
                String.format("%s carries %s in a module that names no tagging mode and writes no keyword, so "
                                + "the tag replaces the value's universal tag - but %s wraps %s instead. EMM "
                                + "refused this shape on IMSCDRS.TokensCSCF five times.",
                        field.getFieldName(), node.tagLabel(), node.tagLabel(), only.tagLabel())
        );
    }

    private boolean inScope(AsnField field) {
        return field.isModuleNamesNoTaggingMode()
                && !field.isTagDeclaredOnType()
                && field.getDeclaredTagging() == AsnDeclaredTagging.NONE
                && Objects.nonNull(field.getTagNumber())
                && !field.isChoice()
                && !field.isRepeated()
                && bodyIsUnambiguous(field);
    }

    /** A body has to declare at least two members before its shape can convict it. */
    private boolean bodyIsUnambiguous(AsnField field) {
        List<AsnField> members = field.getChildren();
        return Objects.isNull(members) || members.isEmpty() || members.size() >= SMALLEST_UNAMBIGUOUS_BODY;
    }

    /**
     * The universal tag this field's value carries when it is not tagged - which
     * is what an EXPLICIT wrapper would be holding.
     */
    private Integer universalTagTheValueWouldCarry(AsnField field) {
        List<AsnField> members = field.getChildren();
        if (Objects.nonNull(members) && !members.isEmpty()) {
            return (field.isSet() ? BerUniversalTag.SET : BerUniversalTag.SEQUENCE).getTagNumber();
        }
        Integer override = field.getUniversalTagOverride();
        return Objects.nonNull(override)
                ? override
                : BerUniversalTag.forPrimitiveType(field.getFieldType()).getTagNumber();
    }
}
