package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DER wants a SET's components in ascending tag order (X.690 11.6). BER does
 * not: X.690 8.11.2 says the order of the components of a set value is not
 * significant, so a BER decoder must accept them in any order.
 *
 * <p>This generator emits BER, and it writes a body in the order its schema
 * declares - which is what the vendored schemas expect, and what a real capture
 * from those systems looks like. So an unordered SET is graded WARNING: worth
 * knowing when a file is meant to travel as DER, never a reason to refuse the
 * file. Same reasoning as the duplicate UNIVERSAL tag, which is a WARNING
 * because the encoder has no other tag it could legally write.</p>
 *
 * <p>Grading it ERROR made 90 findings in the CME20R MSC family fail the
 * self-check for two shapes the encoder cannot avoid: a schema that declares
 * {@code [121]} before {@code [90]}, and (until the parser learns to read a
 * declaration wrapped between its name and its tag) phantom untagged members
 * carrying a universal tag among context-tagged ones.</p>
 */
@Component
public class SetOrderingRule implements VerificationRule {

    @Override
    public String name() {
        return "set-ordering";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField()) {
            return;
        }

        AsnField field = nodeContext.field();
        if (!field.isSet()) {
            return;
        }

        if (nodeContext.collectionWrapper()) {
            return;
        }

        TlvNode node = nodeContext.node();
        if (node.isPrimitive() || node.children() == null || node.children().size() < 2) {
            return;
        }

        List<TlvNode> children = node.children();
        TlvNode prevChild = children.get(0);

        for (int i = 1; i < children.size(); i++) {
            TlvNode currChild = children.get(i);
            
            int prevClassOrdinal = prevChild.tagClass().ordinal();
            int prevTagNumber = prevChild.tagNumber();
            
            int currClassOrdinal = currChild.tagClass().ordinal();
            int currTagNumber = currChild.tagNumber();

            // Compare as tuple (classBits/ordinal, tagNumber)
            if (currClassOrdinal < prevClassOrdinal || (currClassOrdinal == prevClassOrdinal && currTagNumber <= prevTagNumber)) {
                context.report(
                        FindingSeverity.WARNING,
                        name(),
                        context.currentPath(),
                        currChild.start(),
                        String.format("SET components are not in ascending tag order, which DER requires "
                                        + "(X.690 11.6) and BER does not (8.11.2). Found [%s] %d after [%s] %d",
                                currChild.tagClass().name(), currChild.tagNumber(),
                                prevChild.tagClass().name(), prevChild.tagNumber())
                );
            }
            prevChild = currChild;
        }
    }
}
