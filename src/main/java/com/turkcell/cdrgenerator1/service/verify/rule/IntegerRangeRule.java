package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.springframework.stereotype.Component;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

@Component
public class IntegerRangeRule implements VerificationRule {

    private final AsnSizeExtractor asnSizeExtractor;

    public IntegerRangeRule(AsnSizeExtractor asnSizeExtractor) {
        this.asnSizeExtractor = asnSizeExtractor;
    }

    @Override
    public String name() {
        return "integer-range";
    }

    @Override
    public void check(NodeContext nodeContext, VerificationContext context) {
        if (!nodeContext.hasField() || nodeContext.node().constructed()) {
            return;
        }
        if (nodeContext.collectionWrapper()) {
            return;
        }

        AsnField field = nodeContext.field();
        if (field.getFieldType() == null || field.getFieldType().isBlank()) {
            return;
        }

        Optional<AsnSizeExtractor.IntegerRange> rangeOpt = asnSizeExtractor.extractIntegerRange(field.getFieldType());
        if (rangeOpt.isEmpty()) {
            return;
        }

        AsnSizeExtractor.IntegerRange range = rangeOpt.get();
        TlvNode node = nodeContext.node();

        BigInteger value;
        int valueStart = node.valueStart();
        int valueEnd = node.valueEnd();
        if (valueStart == valueEnd) {
            value = BigInteger.ZERO;
        } else {
            byte[] data = context.data();
            value = new BigInteger(Arrays.copyOfRange(data, valueStart, valueEnd));
        }

        if (value.compareTo(range.min()) < 0 || value.compareTo(range.max()) > 0) {
            context.report(
                    FindingSeverity.ERROR,
                    name(),
                    context.currentPath(),
                    node.start(),
                    String.format("Value %s outside INTEGER range (%s..%s)", value, range.min(), range.max())
            );
        }
    }
}
