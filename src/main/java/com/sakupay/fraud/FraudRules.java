package com.sakupay.fraud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a transfer must be held for review. Pure logic: the caller supplies the facts (read while the
 * sender's account is locked, so they cannot change underneath), which keeps every rule easy to test.
 */
@Component
public class FraudRules {

    private final FraudProperties properties;
    private final ZoneId zone;

    public FraudRules(FraudProperties properties, @Value("${sakupay.business-zone}") ZoneId zone) {
        this.properties = properties;
        this.zone = zone;
    }

    /**
     * @param amount            the transfer amount
     * @param now               when it is made
     * @param recentTransfers   transfers this account already started within the velocity window
     * @param knownPayee        whether this account has completed a transfer to the receiver before
     * @return the rules that fire; empty means the transfer may complete immediately
     */
    public List<FraudRule> evaluate(long amount, Instant now, long recentTransfers, boolean knownPayee) {
        List<FraudRule> hits = new ArrayList<>();
        if (amount >= properties.largeAmount()) {
            hits.add(FraudRule.LARGE_AMOUNT);
        }
        if (!knownPayee && amount >= properties.newPayeeAmount()) {
            hits.add(FraudRule.NEW_PAYEE_LARGE);
        }
        if (recentTransfers >= properties.velocityMaxTransfers()) {
            hits.add(FraudRule.HIGH_VELOCITY);
        }
        if (amount >= properties.nightAmount() && isNight(now)) {
            hits.add(FraudRule.NIGHT_TIME);
        }
        return hits;
    }

    public static FraudRule.Severity severity(List<FraudRule> hits) {
        return hits.stream().map(FraudRule::severity).max(Enum::compareTo).orElseThrow();
    }

    boolean isNight(Instant now) {
        LocalTime start = properties.nightStart();
        LocalTime end = properties.nightEnd();
        if (start.equals(end)) {
            return false;
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        // A window may wrap around midnight, e.g. 22:00-04:00
        return start.isBefore(end)
                ? !t.isBefore(start) && t.isBefore(end)
                : !t.isBefore(start) || t.isBefore(end);
    }
}
