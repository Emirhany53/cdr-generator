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
                structure.isChoiceRoot(), structure.isSetRoot(), structure.getRootTagCarrier(), data);
    }

    public BerVerificationResult verify(String structureName, List<AsnField> fields,
                                        boolean choiceRoot, boolean setRoot, byte[] data) {
        return verify(structureName, fields, choiceRoot, setRoot, null, data);
    }

    public BerVerificationResult verify(String structureName, List<AsnField> fields,
                                        boolean choiceRoot, boolean setRoot,
                                        AsnField rootTagCarrier, byte[] data) {
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
            walkRecord(fields, choiceRoot, setRoot, rootTagCarrier, structureName,
                    records.get(index), context);
        }

        BerVerificationResult result =
                new BerVerificationResult(structureName, records.size(), context.findings());
        log.debug("Self-check: {}", result.summary());
        return result;
    }

    /**
     * Mirrors {@code BerEncoderService.encodeRecord}: a root type that tags
     * itself IS that tag's TLV, a CHOICE root IS its selected alternative and
     * carries no wrapper of its own, and any other root is one universal
     * SEQUENCE (or SET) holding the top-level fields.
     */
    private void walkRecord(List<AsnField> fields, boolean choiceRoot, boolean setRoot,
                            AsnField rootTagCarrier, String structureName, TlvNode record,
                            VerificationContext context) {
        if (Objects.isNull(fields) || fields.isEmpty()) {
            context.report(FindingSeverity.ERROR, WALKER, context.currentPath(), record.start(),
                    "Structure resolves to no fields, so nothing can be checked");
            return;
        }
        // A root type that tags itself: the record IS the carrier's TLV, so it
        // is walked as an ordinary tagged field - the same field the encoder
        // wrote it with, which is what keeps the two in step.
        if (Objects.nonNull(rootTagCarrier)) {
            walkField(rootTagCarrier, record, context);
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
        boolean containerCarriesTag = true;
        if (Objects.nonNull(field.getTagNumber()) && field.isExplicit()) {
            offer(node, field, false, true, context);
            container = onlyChild(node, field, context);
            if (Objects.isNull(container)) {
                return;
            }
            // The universal SEQUENCE the EXPLICIT wrapper holds: same field, but
            // the [n] tag is on the layer above, not on this one.
            containerCarriesTag = false;
        }
        offer(container, field, true, containerCarriesTag, context);

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

    /**
     * One element of a collection: a fixed body again, never a container.
     *
     * <p>The element carries its OWN universal tag (SEQUENCE/SET, or the leaf's
     * primitive tag) - {@code encodeRepeated} writes the collection's {@code [n]}
     * once, around all of them. So the element is offered as not carrying the
     * field's tag; judging it against {@code [n]} reported every element of every
     * collection in the schema as wrongly tagged.</p>
     */
    private void walkElement(AsnField field, TlvNode element, VerificationContext context) {
        if (field.isChoice()) {
            walkAlternative(field, element, context);
            return;
        }
        // An element whose TYPE declares a tag carries it, and the carrier is the
        // field the encoder wrote it with - so it is walked as an ordinary
        // tagged field, tag checks included. Without this the element's tag went
        // unchecked here just as it went unwritten there.
        if (Objects.nonNull(field.getElementTagCarrier())) {
            walkPlain(field.getElementTagCarrier(), element, context);
            return;
        }
        offer(element, field, false, false, context);
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
        // The encoder's counterpart: where the tag is IMPLICIT the node IS the
        // alternative, re-tagged, so there is no wrapper to unwrap. Demanding one
        // reported the bytes EMM accepted in round 13 as broken.
        if (field.isChoiceTagImplicit()) {
            offer(node, field, false, context);
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
        boolean bodyCarriesTag = true;
        if (Objects.nonNull(field.getTagNumber()) && field.isExplicit()) {
            offer(node, field, false, true, context);
            body = onlyChild(node, field, context);
            if (Objects.isNull(body)) {
                return;
            }
            // Inside an EXPLICIT tag sits the value's own UNIVERSAL TLV. It is
            // still this field's content - the value rules must see it - but the
            // [n] tag lives on the wrapper above, so tag checks stop here.
            bodyCarriesTag = false;
        }
        offer(body, field, false, bodyCarriesTag, context);
        if (hasChildren(field)) {
            matchChildren(field.getChildren(), body, context);
        }
    }

    /**
     * Pairs the children of a fixed body with the fields that could have
     * produced them, by tag. Matching by tag rather than by position is what
     * lets a body arrive with its OPTIONAL members omitted, or - in a SET, whose
     * component order X.690 8.11.2 leaves open - in an order other than the one
     * the fields are declared in.
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
        // One pass, in DECLARATION order, asking each field the question that
        // fits it - a tagged field about its own tag, an untagged one about the
        // universal tag its type produces (exactly what
        // wrapLeafInUniversalTlv/containerUniversalTag compute on the encoder
        // side) or, for a scalar CHOICE, about its selected alternative's tag.
        //
        // The order is the whole point. This used to sweep every TAGGED field
        // first and only then the untagged ones, which handed a node to a field
        // declared far later in the body whenever an earlier untagged CHOICE
        // could have produced the same tag. HTSCevapsiz and BDCevapsiz open with
        // an anonymous "recordType CHOICE { mSOriginating [10] IMPLICIT
        // IA5String, ... }" and declare "cellID [10] CellID" further down; the
        // record's first TLV, 8A .. written by recordType, was matched to cellID
        // instead, and the two fields disagree about everything - cellID is
        // EXPLICIT, so the checks that followed reported a correctly encoded
        // record as "EXPLICIT tag should be constructed" and "should wrap
        // exactly one TLV but wraps 0". In strict mode that refused the file.
        //
        // Declaration order is the right tiebreak because the encoder writes a
        // body in declaration order and this method consumes each field as it
        // matches: the first field that COULD have produced this node is the one
        // that DID.
        for (Iterator<AsnField> it = remaining.iterator(); it.hasNext(); ) {
            AsnField candidate = it.next();
            if (fieldWouldProduce(candidate, child)) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    /**
     * True when this UNTAGGED field is the one that would have written this
     * node's tag.
     *
     * <p>An untagged CHOICE takes the tag of its selected alternative -
     * {@code wrapInTlv} writes that alternative's TLV through with no wrapper of
     * its own. The resolved tree holds exactly that alternative, so the tag is
     * predictable after all; it was previously left unmatched, which took the
     * whole subtree out of the self-check. 81 modules had at least one body
     * checked "without schema knowledge" for this reason, and the TAP family's
     * {@code serviceCode ServiceCode} - an untagged CHOICE over
     * {@code [APPLICATION 218]} and friends - is the shape that shows it.</p>
     */
    private boolean untaggedFieldWouldProduce(AsnField field, TlvNode child) {
        // A repeated CHOICE is NOT this case: its elements sit inside the
        // collection's own universal wrapper, exactly as encodeRepeated writes
        // them, so the ordinary universal-tag comparison below applies.
        if (field.isChoice() && !field.isRepeated()) {
            List<AsnField> alternatives = field.getChildren();
            if (Objects.isNull(alternatives) || alternatives.size() != CHOICE_ALTERNATIVE_COUNT) {
                return false;
            }
            return fieldWouldProduce(alternatives.get(0), child);
        }
        Integer expectedTag = untaggedUniversalTag(field);
        return Objects.nonNull(expectedTag)
                && child.tagClass() == BerTagClass.UNIVERSAL
                && child.tagNumber() == expectedTag;
    }

    /** The same question for a field of any kind: its own tag first, else its type's. */
    private boolean fieldWouldProduce(AsnField field, TlvNode child) {
        if (Objects.nonNull(field.getTagNumber())) {
            BerTagClass tagClass = Objects.nonNull(field.getTagClass())
                    ? field.getTagClass()
                    : BerTagClass.CONTEXT;
            return child.hasTag(tagClass, field.getTagNumber());
        }
        return untaggedFieldWouldProduce(field, child);
    }

    /**
     * The universal tag an untagged field's own bytes carry, mirroring
     * {@code BerEncoderService.wrapLeafInUniversalTlv} and
     * {@code containerUniversalTag} exactly so the two sides never disagree.
     *
     * <p>A collection is asked first, because {@code containerUniversalTag}
     * writes SEQUENCE for every repeated field - including one whose elements
     * are CHOICEs, which is why the CHOICE question comes second here.</p>
     *
     * <p>{@code null} for a scalar CHOICE: that field carries no wrapper of its
     * own at all, so there is no universal tag to compare. Its tag comes from
     * the selected alternative and is handled by
     * {@link #untaggedFieldWouldProduce}.</p>
     */
    private Integer untaggedUniversalTag(AsnField field) {
        if (field.isChoice() && !field.isRepeated()) {
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

    /** Offers a node that DOES carry its field's own tag. */
    private void offer(TlvNode node, AsnField field, boolean collectionWrapper,
                       VerificationContext context) {
        offer(node, field, collectionWrapper, true, context);
    }

    private void offer(TlvNode node, AsnField field, boolean collectionWrapper,
                       boolean carriesFieldTag, VerificationContext context) {
        NodeContext nodeContext = new NodeContext(node, field, collectionWrapper, carriesFieldTag);
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
