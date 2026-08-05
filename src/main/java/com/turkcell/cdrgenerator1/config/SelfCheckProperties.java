package com.turkcell.cdrgenerator1.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * How hard the generator checks its own output before handing it over.
 *
 * <p>Kept out of {@link CdrConfigProperties} on purpose: that class describes
 * WHAT to generate, this one describes how much to trust the result. They
 * change for different reasons.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cdr.self-check")
public class SelfCheckProperties {

    public enum Mode {
        /** A finding of ERROR severity stops the file from being returned. */
        STRICT,
        /** Findings are logged, the file is returned anyway. */
        WARN,
        /** No verification at all. */
        OFF
    }

    private Mode mode = Mode.WARN;

    /**
     * Per-rule switches, keyed by {@code VerificationRule.name()}. A rule not
     * mentioned here stays on, so adding a rule does not require touching the
     * yml of every environment.
     */
    private Map<String, Boolean> rules = Map.of();

    public boolean isEnabled() {
        return mode != Mode.OFF;
    }

    public boolean isRuleEnabled(String ruleName) {
        return rules.getOrDefault(ruleName, Boolean.TRUE);
    }
}
