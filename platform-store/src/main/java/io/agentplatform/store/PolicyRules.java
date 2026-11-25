package io.agentplatform.store;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentplatform.spi.PolicyEngine;
import java.util.Set;

final class PolicyRules {
    private static final Set<String> EFFECTS = Set.of("ALLOW","DENY","REQUIRE_APPROVAL");
    private static final Set<String> OPERATORS = Set.of("exists","eq","lt","lte","gt","gte");

    static void validate(JsonNode rules, boolean named) {
        if(rules==null || !rules.isArray() || rules.size()>64) invalid();
        for(JsonNode rule:rules) {
            allowed(rule,Set.of("effect","resource","when"));
            if(!EFFECTS.contains(rule.path("effect").asText())) invalid();
            if(named || rule.has("resource")) Database.requireText(rule.path("resource").textValue(),"policy resource",128);
            if(rule.has("when")) {
                var when=rule.get("when");
                allowed(when,Set.of("pointer","operator","value"));
                String op=when.path("operator").asText();
                String pointer=when.path("pointer").asText();
                if(!OPERATORS.contains(op) || !pointer.startsWith("/") || pointer.length()>256) invalid();
                JsonPointer.compile(pointer);
                if(op.equals("exists")) {
                    if(when.has("value")) invalid();
                } else if(!when.has("value") || !when.get("value").isValueNode()) invalid();
                else if(!op.equals("eq") && !when.get("value").isNumber()) invalid();
            }
        }
    }

    static PolicyEngine.Decision evaluate(JsonNode rules,String tool,JsonNode args,String role) {
        boolean allow=false,approval=false;
        for(var rule:rules) {
            if(rule.has("resource") && !tool.equals(rule.get("resource").asText())) continue;
            if(rule.has("when") && !matches(rule.get("when"),args)) continue;
            switch(rule.required("effect").asText()) {
                case "DENY" -> { return new PolicyEngine.Decision(PolicyEngine.Effect.DENY,null); }
                case "ALLOW" -> allow=true;
                case "REQUIRE_APPROVAL" -> approval=true;
                default -> throw new IllegalStateException("Invalid stored policy");
            }
        }
        if(approval) return new PolicyEngine.Decision(PolicyEngine.Effect.REQUIRE_APPROVAL,role);
        return new PolicyEngine.Decision(allow?PolicyEngine.Effect.ALLOW:PolicyEngine.Effect.DENY,null);
    }

    static PolicyEngine.Decision intersect(PolicyEngine.Decision a,PolicyEngine.Decision b) {
        if (a.effect()==PolicyEngine.Effect.DENY || b.effect()==PolicyEngine.Effect.DENY)
            return new PolicyEngine.Decision(PolicyEngine.Effect.DENY,null);
        if(a.effect()==PolicyEngine.Effect.REQUIRE_APPROVAL && b.effect()==PolicyEngine.Effect.REQUIRE_APPROVAL
                && !a.requiredRole().equals(b.requiredRole())) return new PolicyEngine.Decision(PolicyEngine.Effect.DENY,null);
        return a.effect()==PolicyEngine.Effect.REQUIRE_APPROVAL?a:b;
    }

    private static boolean matches(JsonNode when,JsonNode args) {
        JsonNode value=args.at(when.required("pointer").textValue());
        String op=when.required("operator").asText();
        if(op.equals("exists")) return !value.isMissingNode();
        JsonNode expected=when.required("value");
        if(op.equals("eq")) return value.isNumber() && expected.isNumber()
                ? value.decimalValue().compareTo(expected.decimalValue())==0 : value.equals(expected);
        if(!value.isNumber()) return false;
        int comparison=value.decimalValue().compareTo(expected.decimalValue());
        return switch(op) {
            case "lt" -> comparison<0;
            case "lte" -> comparison<=0;
            case "gt" -> comparison>0;
            case "gte" -> comparison>=0;
            default -> false;
        };
    }

    static void allowed(JsonNode node,Set<String> fields) {
        if(!node.isObject()) invalid();
        node.fieldNames().forEachRemaining(field->{if(!fields.contains(field)) invalid();});
    }
    private static void invalid() { throw new StoreException(400,"Invalid policy rule"); }
}
