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
 * Invariant 1, narrowed to the one sub-class its shape can convict: a collection
 * whose schema writes {@code EXPLICIT} but whose bytes must carry the elements
 * directly, with no universal SEQUENCE layer between the tag and them.
 *
 * <h2>The measurement</h2>
 *
 * <p>{@code GGSNTurkcellCdrR7} declares
 * {@code sgsnAddress [6] EXPLICIT SEQUENCE OF GSNAddress}, and
 * {@code GSNAddress ::= IPAddress ::= CHOICE}. Round 1 refused the file with
 * "sgsnAddress.[0] not set"; the same record with that layer removed was
 * accepted in rounds 2 and 3, more than once. {@code 1f71a2a} was written on
 * that refusal.</p>
 *
 * <p>Round 10's decode showed the same thing from the other side, on
 * {@code listOfTrafficVolumes [12]}: both elements resolved, each with its own
 * fields, so the collection had no wrapper and EMM did not want one.</p>
 *
 * <h2>Why the scope is 56 sites and not 224</h2>
 *
 * <p>The family's collections split in two by whether an element can be told
 * from a wrapper by shape alone. Where elements carry a tag of their own - a
 * CHOICE alternative's, or one their type declares - a universal SEQUENCE under
 * the collection tag can only be a wrapper, and the rule is exact. Where
 * elements ARE universal SEQUENCEs ({@code SEQUENCE OF InterOperatorIdentifiers}
 * and its 167 relatives), a one-element collection and a wrapper produce
 * identical bytes, and no amount of looking will separate them. Those are left
 * alone rather than guessed at.</p>
 *
 * <h2>What this deliberately does not cover</h2>
 *
 * <ul>
 * <li>A scalar CHOICE. {@code servedPDPAddress [9] EXPLICIT PDPAddress} KEEPS
 *     its wrapper - round 10's decode resolved it through two levels - and the
 *     encoder returns before the family gate for exactly that reason. Asserting
 *     the opposite here would convict 269 correct sites. The
 *     {@code isRepeated()} gate keeps them out.</li>
 * <li>A primitive. {@code TagShapeRule} and {@code PrimitiveLengthRule} already
 *     catch what the wrapper does to the bytes; a third rule on the same defect
 *     would double-report it.</li>
 * <li>A scalar SET. The MMTel reference capture measures those 54 layers
 *     already, and its evidence is a capture rather than an EMM answer - a
 *     weaker footing than the two rounds behind this rule.</li>
 * </ul>
 */
@Component
public class CollectionWrapperRule implements VerificationRule {

    private static final int WRAPPER_CHILD_COUNT = 1;

    @Override
    public String name() {
        return "collection-wrapper";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField() || !nodeContext.carriesFieldTag()) {
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
        if (only.tagClass() != BerTagClass.UNIVERSAL
                || only.tagNumber() != BerUniversalTag.SEQUENCE.getTagNumber()) {
            return;
        }
        context.report(
                FindingSeverity.ERROR,
                name(),
                context.currentPath(),
                node.start(),
                String.format("%s declares EXPLICIT over a collection whose elements carry their own tag, so %s "
                                + "holds the elements directly - but it holds one universal SEQUENCE instead. "
                                + "EMM refused this layer on GGSNTurkcellCdrR7.sgsnAddress.",
                        field.getFieldName(), node.tagLabel())
        );
    }

    /**
     * The collection sites whose wrapper the two GGSN rounds settled, minus the
     * ones whose elements are themselves universal SEQUENCEs.
     */
    private boolean inScope(AsnField field) {
        return field.getDeclaredTagging() == AsnDeclaredTagging.EXPLICIT
                && !field.isExplicit()
                && field.isRepeated()
                && Objects.nonNull(field.getTagNumber())
                && elementCarriesItsOwnTag(field);
    }

    /**
     * True when one element of this collection arrives under a tag of its own,
     * which is what makes a universal SEQUENCE under the collection tag
     * unambiguous.
     */
    private boolean elementCarriesItsOwnTag(AsnField field) {
        if (field.isChoice()) {
            return true;
        }
        if (Objects.nonNull(field.getElementTagCarrier())) {
            return true;
        }
        List<AsnField> members = field.getChildren();
        return Objects.isNull(members) || members.isEmpty();
    }
}
