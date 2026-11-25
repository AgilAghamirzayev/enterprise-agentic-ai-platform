package io.agentplatform.store;

import java.math.BigDecimal;

/** Provider prices in USD per million tokens. Both prices are required for cost accounting. */
public record ModelPricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    public ModelPricing {
        if ((inputPerMillion == null) != (outputPerMillion == null))
            throw new IllegalArgumentException("Configure both input and output token prices");
        check(inputPerMillion);
        check(outputPerMillion);
    }

    private static void check(BigDecimal price) {
        if (price != null
                && (price.signum() < 0
                        || price.scale() > 6
                        || price.precision() - price.scale() > 12))
            throw new IllegalArgumentException("Invalid USD price per million tokens");
    }

    BigDecimal estimate(int input, int output) {
        if (inputPerMillion == null) return null;
        return inputPerMillion
                .multiply(BigDecimal.valueOf(input))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(output)))
                .movePointLeft(6);
    }
}
