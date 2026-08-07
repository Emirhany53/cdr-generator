package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.BerUniversalTag;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TagShapeRule implements VerificationRule {

    /** X.690 8.20: RELATIVE-OID. Not modelled in BerUniversalTag. */
    private static final int RELATIVE_OID_TAG = 13;

    /**
     * Universal tags X.690 requires to be encoded primitive, always: BOOLEAN
     * (8.2.1), INTEGER (8.3.1), NULL (8.8.1), OBJECT IDENTIFIER (8.19.1), REAL
     * (8.5.1), ENUMERATED (8.4, encoded as an INTEGER) and RELATIVE-OID
     * (8.20.1). A constructed TLV carrying one of these is not readable as that
     * type by any conforming decoder.
     *
     * <p>Deliberately excludes the types BER lets a sender choose for: BIT
     * STRING (8.6.1) and OCTET STRING (8.7.1) may be constructed, and so may
     * every character string (8.21.3) and the time types built on them.
     * Including any of those would report correct files as broken.</p>
     */
    private static final Set<Integer> MUST_BE_PRIMITIVE = Set.of(
            BerUniversalTag.BOOLEAN.getTagNumber(),
            BerUniversalTag.INTEGER.getTagNumber(),
            BerUniversalTag.NULL.getTagNumber(),
            BerUniversalTag.OBJECT_IDENTIFIER.getTagNumber(),
            BerUniversalTag.REAL.getTagNumber(),
            BerUniversalTag.ENUMERATED.getTagNumber(),
            RELATIVE_OID_TAG);

    @Override
    public String name() {
        return "tag-shape";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        // Judged on the bytes alone, so it runs before the field checks below
        // and applies to every node - including ones no field claimed.
        checkUniversalPrimitiveShape(nodeContext.node(), context);

        if (!nodeContext.hasField() || nodeContext.collectionWrapper()) {
            return;
        }
        // This rule judges ONE thing: does the node carrying the field's [n] tag
        // have the right tag and shape. A node that does not carry that tag - the
        // universal TLV inside an EXPLICIT wrapper, or one element of a collection -
        // is described by the same field but was never supposed to bear its tag,
        // and comparing anyway reported every such node in the schema as wrong.
        if (!nodeContext.carriesFieldTag()) {
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

    /**
     * A universal tag whose type X.690 requires to be primitive, carrying the
     * constructed bit.
     *
     * <p>This needs no schema at all - the tag alone says which type the bytes
     * claim to be, and X.690 says that type is never constructed. It is the one
     * check here that can convict a file the field tree agrees with, which is
     * exactly what was missing: {@code identifier [UNIVERSAL 6] OCTET STRING}
     * in a module defaulting to EXPLICIT tags produced {@code 26 0A 04 08 ..} -
     * an OBJECT IDENTIFIER whose contents are an OCTET STRING TLV - and every
     * other rule called it correct, because it IS what that schema line asks
     * for.</p>
     */
    private void checkUniversalPrimitiveShape(TlvNode node, VerificationContext context) {
        if (node.tagClass() != BerTagClass.UNIVERSAL || !node.constructed()) {
            return;
        }
        if (!MUST_BE_PRIMITIVE.contains(node.tagNumber())) {
            return;
        }
        context.report(
                FindingSeverity.ERROR,
                name(),
                context.pathTo(node.tagLabel()),
                node.start(),
                String.format("X.690 requires %s to be encoded primitive, but this tag is constructed",
                        node.tagLabel())
        );
    }
}
