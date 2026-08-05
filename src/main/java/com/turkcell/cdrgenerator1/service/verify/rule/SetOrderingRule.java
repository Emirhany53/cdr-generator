package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.List;

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
                        FindingSeverity.ERROR,
                        name(),
                        context.currentPath(),
                        currChild.start(),
                        String.format("SET components must be encoded in ascending tag order. Found [%s] %d after [%s] %d",
                                currChild.tagClass().name(), currChild.tagNumber(),
                                prevChild.tagClass().name(), prevChild.tagNumber())
                );
            }
            prevChild = currChild;
        }
    }
}
