package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

@Component
public class TagShapeRule implements VerificationRule {

    @Override
    public String name() {
        return "tag-shape";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField() || nodeContext.collectionWrapper()) {
            return;
        }

        AsnField field = nodeContext.field();
        TlvNode node = nodeContext.node();

        if (field.getTagNumber() != null) {
            BerTagClass expectedClass = field.getTagClass() != null ? field.getTagClass() : BerTagClass.CONTEXT;
            
            if (node.tagClass() != expectedClass || node.tagNumber() != field.getTagNumber()) {
                context.report(
                        FindingSeverity.ERROR,
                        name(),
                        context.currentPath(),
                        node.start(),
                        String.format("Expected tag %s [%d] but found %s", expectedClass, field.getTagNumber(), node.tagLabel())
                );
            }

            if (field.isExplicit()) {
                if (!node.constructed()) {
                    context.report(
                            FindingSeverity.ERROR,
                            name(),
                            context.currentPath(),
                            node.start(),
                            "EXPLICIT tag should be constructed"
                    );
                }
            } else {
                boolean hasChildren = field.getChildren() != null && !field.getChildren().isEmpty();
                if (hasChildren || field.isSet() || field.isChoice()) {
                    if (!node.constructed()) {
                        context.report(
                                FindingSeverity.WARNING,
                                name(),
                                context.currentPath(),
                                node.start(),
                                String.format("%s is a container but its tag is primitive", field.getFieldName())
                        );
                    }
                }
            }
        }
    }
}
