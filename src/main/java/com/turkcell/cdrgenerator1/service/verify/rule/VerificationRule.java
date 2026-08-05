package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.service.verify.VerificationContext;

/**
 * One independent check applied to every node of a decoded record.
 *
 * <p>Each rule does exactly one thing, so a new check is a new class rather
 * than an edit to an existing one. Implementations are Spring components and
 * are collected automatically; {@link #name()} is what the yml uses to switch
 * an individual rule off.</p>
 */
public interface VerificationRule {

    /** Stable identifier, also the key under {@code app.cdr.self-check.rules}. */
    String name();

    /**
     * Inspects one node. The walk itself is the verifier's job; a rule never
     * recurses on its own, so every node is offered to every rule exactly once.
     */
    void check(NodeContext nodeContext, VerificationContext context);
}
