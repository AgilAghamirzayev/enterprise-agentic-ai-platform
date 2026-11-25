package io.agentplatform.store;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Offline lexical baseline used by the quickstart. Supply a learned embedding model for semantic
 * retrieval.
 */
public final class TokenHashVectorizer implements Vectorizer {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    @Override
    public String model() {
        return "token-hash-256-v1";
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[256];
        var tokens = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (tokens.find()) {
            int index = Math.floorMod(tokens.group().hashCode(), vector.length);
            vector[index]++;
        }
        return vector;
    }
}
