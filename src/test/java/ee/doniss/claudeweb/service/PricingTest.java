package ee.doniss.claudeweb.service;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingTest {

    private final Pricing pricing = new Pricing(new ClaudeProperties.Pricing());

    @Test
    void matchesFamilyBySubstringOfModelId() {
        // haiku: $1/MTok in, $5/MTok out
        double cost = pricing.costUsd("claude-haiku-4-5-20251001", 1_000_000, 1_000_000, 0, 0);
        assertEquals(6.0, cost, 1e-9);
    }

    @Test
    void cacheMultipliersApplyToInputRate() {
        // sonnet input $3/MTok: write = 1.25x -> $3.75/MTok, read = 0.1x -> $0.30/MTok
        double cost = pricing.costUsd("claude-sonnet-4-6", 0, 0, 1_000_000, 1_000_000);
        assertEquals(3.75 + 0.30, cost, 1e-9);
    }

    @Test
    void unknownModelCostsZero() {
        assertEquals(0.0, pricing.costUsd("gpt-oss-120b", 1_000_000, 1_000_000, 0, 0));
        assertEquals(0.0, pricing.costUsd(null, 1_000_000, 0, 0, 0));
    }

    @Test
    void perFamilyRatesAreDistinct() {
        double haiku = pricing.costUsd("claude-haiku-4-5", 1_000_000, 0, 0, 0);
        double opus = pricing.costUsd("claude-opus-4-8", 1_000_000, 0, 0, 0);
        double fable = pricing.costUsd("claude-fable-5", 1_000_000, 0, 0, 0);
        assertEquals(1.0, haiku, 1e-9);
        assertEquals(5.0, opus, 1e-9);
        assertEquals(10.0, fable, 1e-9);
    }
}
