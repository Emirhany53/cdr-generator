package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.BerUniversalTag;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The two universal types whose contents length X.690 fixes to a single value.
 *
 * <p>Like {@code TagShapeRule.checkUniversalPrimitiveShape}, this needs no
 * schema: the tag alone says which type the bytes claim to be, and X.690 says
 * how long that type's contents are. It can therefore convict a file that the
 * field tree agrees with - which is the only kind of finding self-check can
 * contribute that the encoder could not have known.</p>
 *
 * <h2>Why this rule exists</h2>
 *
 * <p>EMM refused {@code LTE-R10} in round 2 with:</p>
 *
 * <pre>
 * Invalid length 3 of field "LTE-R10.CallEventRecord.sGWRecord.dynamicAddressFlag"
 * Boolean can only have a maximum length of 1 bytes.
 * </pre>
 *
 * <p>{@code dynamicAddressFlag [11] EXPLICIT DynamicAddressFlag} had been
 * encoded as {@code AB 03 01 01 FF} - the EXPLICIT wrapper's own TLV read as
 * BOOLEAN contents. Every rule then in place called the file correct, because
 * it IS what that schema line asks for; the disagreement was with X.690, not
 * with the field tree. The round cost about a working day and returned an error
 * a local check could have produced in milliseconds.</p>
 *
 * <p>{@code b0a26b9} fixed the encoder, and no file produced today violates
 * this. The rule is therefore a regression guard rather than a bug hunt - and
 * the class of regression it guards has already happened once: {@code 2832d40}
 * reintroduced the EXPLICIT wrapper on scalar primitives and was reverted. A
 * check on the bytes makes that silent reintroduction impossible.</p>
 *
 * <h2>Scope</h2>
 *
 * <p>Deliberately only BOOLEAN and NULL. These are the two types X.690 pins to
 * an exact length (8.2.1: "a single octet"; 8.8.2: contents "shall not be
 * present"). INTEGER, ENUMERATED and OBJECT IDENTIFIER have no fixed length, and
 * the string types are free to be any length the constraint allows - flagging
 * those would report correct files as broken. An independent decoder
 * ({@code asn1tools}) rejects exactly this pair on length, and OpenSSL's
 * {@code asn1parse} rejects neither, which is what made the gap visible.</p>
 */
@Component
public class PrimitiveLengthRule implements VerificationRule {

    /** Universal tag number to the contents length X.690 fixes for it. */
    private static final Map<Integer, Integer> FIXED_CONTENT_LENGTH = Map.of(
            BerUniversalTag.BOOLEAN.getTagNumber(), 1,
            BerUniversalTag.NULL.getTagNumber(), 0);

    @Override
    public String name() {
        return "primitive-length";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        TlvNode node = nodeContext.node();
        if (node.tagClass() != BerTagClass.UNIVERSAL || node.constructed()) {
            return;
        }
        Integer expected = FIXED_CONTENT_LENGTH.get(node.tagNumber());
        if (expected == null || node.valueLength() == expected) {
            return;
        }
        context.report(
                FindingSeverity.ERROR,
                name(),
                context.pathTo(node.tagLabel()),
                node.start(),
                String.format("X.690 fixes %s contents to %d octet(s), but this value is %d",
                        node.tagLabel(), expected, node.valueLength())
        );
    }
}
