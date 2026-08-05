package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;

/**
 * A decoded node paired with the schema field it was matched to.
 *
 * @param node             the decoded TLV
 * @param field            the field it was matched to, or {@code null} when the
 *                         walker could not match it - a rule that needs schema
 *                         knowledge must then either skip or downgrade its
 *                         verdict rather than guess
 * @param collectionWrapper true when this node is the {@code SEQUENCE OF} /
 *                         {@code SET OF} container ITSELF rather than one of its
 *                         elements. The distinction matters because a container
 *                         legitimately repeats one tag across its elements,
 *                         while the elements themselves are fixed bodies whose
 *                         members must all differ. Both are described by the
 *                         same {@link AsnField}, so the flag is the only thing
 *                         telling them apart.
 * @param carriesFieldTag  true when this node is the one the field's own {@code [n]}
 *                         tag was written on. One field can produce SEVERAL nested
 *                         TLVs and only the outermost carries that tag: an EXPLICIT
 *                         field writes {@code [n] { <universal> }}, and a
 *                         {@code SEQUENCE OF} writes {@code [n] { <element> ... }}.
 *                         The inner layers are described by the SAME field but
 *                         carry a UNIVERSAL tag, so a rule comparing a node's tag
 *                         against {@code field.getTagNumber()} must sit this out -
 *                         otherwise every EXPLICIT field in the schema reports
 *                         "expected CONTEXT [n], found U-[22]" against its own
 *                         correctly-encoded content.
 */
public record NodeContext(TlvNode node, AsnField field, boolean collectionWrapper,
                          boolean carriesFieldTag) {

    /** A node that carries its field's own tag - the ordinary case. */
    public NodeContext(TlvNode node, AsnField field, boolean collectionWrapper) {
        this(node, field, collectionWrapper, true);
    }

    public boolean hasField() {
        return field != null;
    }
}
