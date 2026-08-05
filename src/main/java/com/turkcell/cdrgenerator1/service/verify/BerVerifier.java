package com.turkcell.cdrgenerator1.service.verify;

import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.exception.BerDecodingException;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.BerUniversalTag;
import com.turkcell.cdrgenerator1.service.verify.rule.NodeContext;
import com.turkcell.cdrgenerator1.service.verify.rule.VerificationRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Walks encoded bytes against the very field tree that produced them and
 * offers every node to every enabled {@link VerificationRule}.
 *
 * <p>The walk is the mirror image of {@code BerEncoderService.encodeField} and
 * {@code wrapInTlv}: for each shape the encoder can emit there is exactly one
 * branch here that knows how to take it apart again. That correspondence is
 * the whole design - if the encoder gains a shape, this gains a branch, and
 * the two stay checkable against each other.</p>
 *
 * <p>Deliberately NOT a second schema interpreter. It consumes the resolved
 * {@link AsnField} tree rather than re-reading the ASN.1 text, which is what
 * keeps it from becoming the Java twin of tools/mmtel_resolver_port.py. The
 * cost of that choice is stated plainly: encoder and verifier share the same
 * tree, so a tree that is itself wrong fools both. Catching THAT needs a
 * comparison against a real accepted capture, which is what
 * tools/compareBerStructure.py is for and why it should not be deleted.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BerVerifier {

    /** Reported when the walker itself cannot line the bytes up with the schema. */
    private static final String WALKER = "walker";
    /** An EXPLICIT tag wraps exactly one inner TLV. */
    private static final int EXPLICIT_WRAPPER_CHILD_COUNT = 1;
    /** A resolved CHOICE field carries exactly one child: the selected alternative. */
    private static final int CHOICE_ALTERNATIVE_COUNT = 1;

    private final TlvReader tlvReader;
    private final SelfCheckProperties properties;
    private final List<VerificationRule> rules;

    public BerVerificationResult verify(AsnStructure structure, byte[] data) {
        return verify(structure.getStructureName(), structure.getFields(),
                structure.isChoiceRoot(), structure.isSetRoot(), data);
    }

    public BerVerificationResult verify(String structureName, List<AsnField> fields,
                                        boolean choiceRoot, boolean setRoot, byte[] data) {
        VerificationContext context = new VerificationContext(structureName, data);
        if (!properties.isEnabled()) {
            return new BerVerificationResult(structureName, 0, List.of());
        }

        List<TlvNode> records;
        try {
            records = tlvReader.readAll(data);
        } catch (BerDecodingException e) {
            context.report(FindingSeverity.ERROR, WALKER, context.currentPath(), 0,
                    "Bytes are not readable as BER: " + e.getMessage());
            return new BerVerificationResult(structureName, 0, context.findings());
        }

        for (int index = 0; index < records.size(); index++) {
            context.startRecord(index);
            walkRecord(fields, choiceRoot, setRoot, structureName, records.get(index), context);
        }

        BerVerificationResult result =
                new BerVerificationResult(structureName, records.size(), context.findings());
        log.debug("Self-check: {}", result.summary());
        return result;
    }

    /**
     * Mirrors {@code BerEncoderService.encodeRecord}: a CHOICE root IS its
     * selected alternative and carries no wrapper of its own, while any other
     * root is one universal SEQUENCE (or SET) holding the top-level fields.
     */
    private void walkRecord(List<AsnField> fields, boolean choiceRoot, boolean setRoot,
                            String structureName, TlvNode record, VerificationContext context) {
        if (Objects.isNull(fields) || fields.isEmpty()) {
            context.report(FindingSeverity.ERROR, WALKER, context.currentPath(), record.start(),
                    "Structure resolves to no fields, so nothing can be checked");
            return;
        }
        if (choiceRoot) {
            walkField(fields.get(0), record, context);
            return;
        }
        // The record wrapper has no field of its own; a synthetic one lets the
        // rules judge the top-level body exactly like any other fixed body.
        AsnField rootField = AsnField.builder()
                .fieldName(structureName)
                .set(setRoot)
                .children(fields)
                .build();
        walkPlain(rootField, record, context);
    }

    private void walkField(AsnField field, TlvNode node, VerificationContext context) {
        context.push(pathSegment(field));
        try {
            if (field.isRepeated()) {
                walkRepeated(field, node, context);
            } else if (field.isChoice()) {
                walkChoice(field, node, context);
            } else {
                walkPlain(field, node, context);
            }
        } finally {
            context.pop();
        }
    }

    /**
     * A collection: the outer tag holds the elements. Which TLV actually holds
     * them depends on how the field is tagged, exactly as in {@code wrapInTlv} -
     * an EXPLICIT collection puts a universal container between the context tag
     * and the elements, an IMPLICIT one does not.
     */
    private void walkRepeated(AsnField field, TlvNode node, VerificationContext context) {
        TlvNode container = node;
        if (Objects.nonNull(field.getTagNumber()) && field.isExplicit()) {
            offer(node, field, false, context);
            container = onlyChild(node, field, context);
            if (Objects.isNull(container)) {
                return;
            }
        }
        offer(container, field, true, context);

        int index = 0;
        for (TlvNode element : container.children()) {
            context.push("[" + index + "]");
            try {
                walkElement(field, element, context);
            } finally {
                context.pop();
            }
            index++;
        }
    }

    /** One element of a collection: a fixed body again, never a container. */
    private void walkElement(AsnField field, TlvNode element, VerificationContext context) {
        if (field.isChoice()) {
            walkAlternative(field, element, context);
            return;
        }
        offer(element, field, false, context);
        if (hasChildren(field)) {
            matchChildren(field.getChildren(), element, context);
        }
    }

    /**
     * A scalar CHOICE. Its encoding IS the selected alternative's TLV; a tag on
     * it is always EXPLICIT (X.680 8.3), so when one is present the node is a
     * wrapper holding that single alternative.
     */
    private void walkChoice(AsnField field, TlvNode node, VerificationContext context) {
        if (Objects.isNull(field.getTagNumber())) {
            walkAlternative(field, node, context);
            return;
        }
        offer(node, field, false, context);
        TlvNode inner = onlyChild(node, field, context);
        if (Objects.nonNull(inner)) {
            walkAlternative(field, inner, context);
        }
    }

    /**
     * Descends into a CHOICE's selected alternative - but only when the bytes
     * actually carry the alternative the resolved tree holds.
     *
     * <p>A resolved CHOICE keeps ONE child: the alternative the generator
     * picked. That is right for checking a file this application just produced,
     * and wrong for checking any other file, which may legitimately carry a
     * different one. The EMM-accepted reference capture does exactly that -
     * {@code nodeAddress} resolves to {@code iPAddress [0]} but the real records
     * carry {@code domainName [1]} - and descending regardless made the walker
     * demand an EXPLICIT wrapper that was never supposed to be there, reporting
     * an error on 50 out of 50 records that EMM had accepted.</p>
     *
     * <p>So a mismatch is reported as "not verified" rather than "wrong". The
     * proper fix is for the resolver to expose every alternative, not just the
     * selected one; until it does, this subtree goes unchecked instead of being
     * checked against the wrong shape.</p>
     */
    private void walkAlternative(AsnField field, TlvNode node, VerificationContext context) {
        List<AsnField> alternatives = field.getChildren();
        if (Objects.isNull(alternatives) || alternatives.size() != CHOICE_ALTERNATIVE_COUNT) {
            offer(node, null, false, context);
            return;
        }
        AsnField alternative = alternatives.get(0);
        if (!carriesTagOf(alternative, node)) {
            offer(node, null, false, context);
            context.report(FindingSeverity.WARNING, WALKER, context.currentPath(), node.start(),
                    "CHOICE carries " + node.tagLabel() + " but the resolved tree holds alternative '"
                            + pathSegment(alternative) + "', so this subtree was not verified");
            return;
        }
        walkField(alternative, node, context);
    }

    /** True when the node's tag is the one this field would have been written with. */
    private boolean carriesTagOf(AsnField field, TlvNode node) {
        if (Objects.isNull(field.getTagNumber())) {
            return true;
        }
        BerTagClass tagClass = Objects.nonNull(field.getTagClass())
                ? field.getTagClass()
                : BerTagClass.CONTEXT;
        return node.hasTag(tagClass, field.getTagNumber());
    }

    /**
     * Everything else. An EXPLICIT tag adds one layer - the universal container
     * or leaf - between the context tag and the content; IMPLICIT and untagged
     * fields carry their content directly.
     */
    private void walkPlain(AsnField field, TlvNode node, VerificationContext context) {
        TlvNode body = node;
        if (Objects.nonNull(field.getTagNumber()) && field.isExplicit()) {
            offer(node, field, false, context);
            body = onlyChild(node, field, context);
            if (Objects.isNull(body)) {
                return;
            }
        }
        offer(body, field, false, context);
        if (hasChildren(field)) {
            matchChildren(field.getChildren(), body, context);
        }
    }

    /**
     * Pairs the children of a fixed body with the fields that could have
     * produced them, by tag. Matching by tag rather than by position is what
     * makes this survive a SET, whose components the encoder sorts into
     * ascending tag order regardless of declaration order.
     */
    private void matchChildren(List<AsnField> fields, TlvNode parent, VerificationContext context) {
        List<AsnField> remaining = new ArrayList<>(fields);
        for (TlvNode child : parent.children()) {
            AsnField matched = takeMatching(remaining, child);
            if (Objects.isNull(matched)) {
                context.push(child.tagLabel());
                try {
                    offer(child, null, false, context);
                    context.report(FindingSeverity.WARNING, WALKER, context.currentPath(),
                            child.start(), "No field of this body carries tag " + child.tagLabel()
                                    + ", so it was checked without schema knowledge");
                } finally {
                    context.pop();
                }
                continue;
            }
            walkField(matched, child, context);
        }
    }

    /**
     * Consumes the first field that could have produced this node. Removing the
     * match matters: a body declares each field once, so leaving it in would let
     * a second child with the same tag borrow the same field and look legitimate.
     */
    private AsnField takeMatching(List<AsnField> remaining, TlvNode child) {
        for (Iterator<AsnField> it = remaining.iterator(); it.hasNext(); ) {
            AsnField candidate = it.next();
            if (Objects.isNull(candidate.getTagNumber())) {
                continue;
            }
            BerTagClass tagClass = Objects.nonNull(candidate.getTagClass())
                    ? candidate.getTagClass()
                    : BerTagClass.CONTEXT;
            if (child.hasTag(tagClass, candidate.getTagNumber())) {
                it.remove();
                return candidate;
            }
        }
        // An untagged field carries the universal tag of its OWN type, which IS
        // predictable - it is exactly what wrapLeafInUniversalTlv/
        // containerUniversalTag compute on the encoder side. Matching on that
        // number, not merely "any untagged field, in order", is what this loop
        // used to do - and it broke the moment a body held two or more untagged
        // OPTIONAL siblings and one of the earlier ones was omitted: the first
        // remaining candidate was no longer the one that actually produced this
        // byte, so a later sibling's bytes got checked against the wrong field's
        // rules. 288 of the 808 modules' bodies carry two or more untagged
        // fields, so this was not a corner case.
        for (Iterator<AsnField> it = remaining.iterator(); it.hasNext(); ) {
            AsnField candidate = it.next();
            if (Objects.nonNull(candidate.getTagNumber())) {
                continue;
            }
            Integer expectedTag = untaggedUniversalTag(candidate);
            if (Objects.nonNull(expectedTag) && child.tagClass() == BerTagClass.UNIVERSAL
                    && child.tagNumber() == expectedTag) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    /**
     * The universal tag an untagged field's own bytes carry, mirroring
     * {@code BerEncoderService.wrapLeafInUniversalTlv} and
     * {@code containerUniversalTag} exactly so the two sides never disagree.
     *
     * <p>{@code null} for a CHOICE: an untagged CHOICE's wire tag is whichever
     * alternative was written, which cannot be predicted without looking at the
     * value. That case is intentionally left unmatched rather than guessed.</p>
     */
    private Integer untaggedUniversalTag(AsnField field) {
        if (field.isChoice()) {
            return null;
        }
        if (field.isRepeated()) {
            return BerUniversalTag.SEQUENCE.getTagNumber();
        }
        if (hasChildren(field)) {
            return (field.isSet() ? BerUniversalTag.SET : BerUniversalTag.SEQUENCE).getTagNumber();
        }
        Integer override = field.getUniversalTagOverride();
        return Objects.nonNull(override) ? override
                : BerUniversalTag.forPrimitiveType(field.getFieldType()).getTagNumber();
    }

    private TlvNode onlyChild(TlvNode node, AsnField field, VerificationContext context) {
        if (node.children().size() == EXPLICIT_WRAPPER_CHILD_COUNT) {
            return node.children().get(0);
        }
        context.report(FindingSeverity.ERROR, WALKER, context.currentPath(), node.start(),
                "EXPLICIT tag " + node.tagLabel() + " on '" + pathSegment(field)
                        + "' should wrap exactly one TLV but wraps " + node.children().size());
        return null;
    }

    private void offer(TlvNode node, AsnField field, boolean collectionWrapper,
                       VerificationContext context) {
        NodeContext nodeContext = new NodeContext(node, field, collectionWrapper);
        for (VerificationRule rule : rules) {
            if (properties.isRuleEnabled(rule.name())) {
                rule.check(nodeContext, context);
            }
        }
    }

    private boolean hasChildren(AsnField field) {
        return Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty();
    }

    private String pathSegment(AsnField field) {
        return Objects.nonNull(field) && Objects.nonNull(field.getFieldName())
                ? field.getFieldName()
                : "?";
    }
}
