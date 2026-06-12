package ee.doniss.claudeweb.service;

import ee.doniss.claudeweb.config.ClaudeProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Estimates the USD cost of one assistant line from its {@code usage} token counts. Plain class
 * (not a bean) so {@link ClaudeDataService} can build it from {@link ClaudeProperties.Pricing}
 * without changing its constructor contract for tests.
 */
public final class Pricing {

    private final double cacheWriteMultiplier;
    private final double cacheReadMultiplier;
    /** Lowercased family substring → rates; insertion order = match priority. */
    private final Map<String, ClaudeProperties.Pricing.ModelRate> models = new LinkedHashMap<>();

    public Pricing(ClaudeProperties.Pricing cfg) {
        this.cacheWriteMultiplier = cfg.getCacheWriteMultiplier();
        this.cacheReadMultiplier = cfg.getCacheReadMultiplier();
        cfg.getModels().forEach((k, v) -> models.put(k.toLowerCase(Locale.ROOT), v));
    }

    /** Estimated USD for one assistant line; {@code 0.0} when no family matches the model id. */
    public double costUsd(String modelId, long input, long output, long cacheWrite, long cacheRead) {
        ClaudeProperties.Pricing.ModelRate rate = rateFor(modelId);
        if (rate == null) {
            return 0.0;
        }
        double usd = input * rate.getInput()
                + output * rate.getOutput()
                + cacheWrite * rate.getInput() * cacheWriteMultiplier
                + cacheRead * rate.getInput() * cacheReadMultiplier;
        return usd / 1_000_000.0;
    }

    private ClaudeProperties.Pricing.ModelRate rateFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        String id = modelId.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ClaudeProperties.Pricing.ModelRate> e : models.entrySet()) {
            if (id.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
