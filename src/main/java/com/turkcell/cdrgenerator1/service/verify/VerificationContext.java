package com.turkcell.cdrgenerator1.service.verify;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries the state a rule needs while the tree is being walked: where we
 * currently are, which record we are in, the underlying bytes, and the
 * findings collected so far.
 *
 * <p>Not thread-safe and not meant to be: one context belongs to one walk of
 * one file.</p>
 */
public class VerificationContext {

    /** Separator between path segments, matching EMM's own notation. */
    private static final String PATH_SEPARATOR = ".";

    private final String structureName;
    private final byte[] data;
    private final List<String> path = new ArrayList<>();
    private final List<BerFinding> findings = new ArrayList<>();
    private int recordIndex;

    public VerificationContext(String structureName, byte[] data) {
        this.structureName = structureName;
        this.data = data;
        this.path.add(structureName);
    }

    public byte[] data() {
        return data;
    }

    public int recordIndex() {
        return recordIndex;
    }

    public void startRecord(int index) {
        this.recordIndex = index;
    }

    /** Enters a named field, or a collection element written as {@code [n]}. */
    public void push(String segment) {
        path.add(segment);
    }

    public void pop() {
        if (!path.isEmpty()) {
            path.remove(path.size() - 1);
        }
    }

    public String currentPath() {
        return String.join(PATH_SEPARATOR, path);
    }

    /** The current path with one more segment appended, without entering it. */
    public String pathTo(String segment) {
        return currentPath() + PATH_SEPARATOR + segment;
    }

    public void report(FindingSeverity severity, String ruleName, String path,
                       int byteOffset, String message) {
        findings.add(new BerFinding(severity, ruleName, path, byteOffset, message));
    }

    public List<BerFinding> findings() {
        return List.copyOf(findings);
    }
}
