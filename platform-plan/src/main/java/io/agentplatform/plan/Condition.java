package io.agentplatform.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** A single comparison of a top-level input field and a JSON scalar literal. */
public final class Condition {
    private static final Pattern COMPARISON =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]{0,63})\\s*(>=|<=|!=|==|>|<)\\s*(.+)");
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .USE_BIG_DECIMAL_FOR_FLOATS);
    private final String field;
    private final String operator;
    private final JsonNode value;

    private Condition(String field, String operator, JsonNode value) {
        this.field = field;
        this.operator = operator;
        this.value = value;
    }

    public static Condition compile(Plan.Node node) {
        if (node.expression() == null) {
            if (node.field() == null
                    || !node.field().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                    || node.equalsValue() == null
                    || !node.equalsValue().isValueNode())
                throw new IllegalArgumentException(
                        "CONDITION requires field and scalar equalsValue");
            return new Condition(node.field(), "==", node.equalsValue());
        }
        if (node.field() != null || node.equalsValue() != null || node.expression().length() > 512)
            throw new IllegalArgumentException("Invalid condition");
        var match = COMPARISON.matcher(node.expression().strip());
        if (!match.matches()) throw new IllegalArgumentException("Expected a field comparison");
        try {
            JsonNode literal = JSON.readTree(match.group(3));
            String op = match.group(2);
            if (literal == null
                    || !literal.isValueNode()
                    || (!op.equals("==") && !op.equals("!=") && !literal.isNumber()))
                throw new IllegalArgumentException("Invalid comparison literal");
            return new Condition(match.group(1), op, literal);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid comparison literal", e);
        }
    }

    public boolean test(JsonNode input) {
        JsonNode actual = input.get(field);
        if (actual == null) return false;
        if (actual.isNumber() && value.isNumber()) {
            int sign = new BigDecimal(actual.asText()).compareTo(new BigDecimal(value.asText()));
            return switch (operator) {
                case "==" -> sign == 0;
                case "!=" -> sign != 0;
                case ">" -> sign > 0;
                case "<" -> sign < 0;
                case ">=" -> sign >= 0;
                case "<=" -> sign <= 0;
                default -> throw new IllegalStateException("Invalid comparison");
            };
        }
        if (actual.getNodeType() != value.getNodeType()) return false;
        return operator.equals("==")
                ? actual.equals(value)
                : operator.equals("!=") && !actual.equals(value);
    }
}
