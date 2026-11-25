package io.agentplatform.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

final class Database {
    final JdbcTemplate jdbc;
    final ObjectMapper mapper;
    final io.micrometer.core.instrument.MeterRegistry metrics;
    private final TransactionTemplate transactions;

    Database(DataSource source, ObjectMapper mapper,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.metrics=metrics;
        jdbc = new JdbcTemplate(source);
        this.mapper = mapper;
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    <T> T scoped(String tenant, Supplier<T> action) {
        requireText(tenant, "tenant", 200);
        return transactions.execute(
                status -> {
                    String previous =
                            status.isNewTransaction()
                                    ? null
                                    : jdbc.queryForObject(
                                            "SELECT current_setting('agent.tenant',true)",
                                            String.class);
                    if (previous != null && !previous.isEmpty() && !previous.equals(tenant))
                        throw new StoreException(403, "A transaction cannot change tenant");
                    jdbc.queryForObject(
                            "SELECT set_config('agent.tenant', ?, true)", String.class, tenant);
                    T result = action.get();
                    if (!status.isNewTransaction() && (previous == null || previous.isEmpty()))
                        jdbc.queryForObject(
                                "SELECT set_config('agent.tenant', '', true)", String.class);
                    return result;
                });
    }

    void afterCommit(Runnable action){
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){action.run();}
            });
    }

    void audit(String tenant, String actor, String runId, String event, String resource) {
        audit(tenant,actor,runId,event,resource,null,null);
    }

    void audit(String tenant,String actor,String runId,String event,String resource,String policyVersion,String payloadHash) {
        String traceId=org.slf4j.MDC.get("traceId");
        if(traceId!=null && !traceId.matches("[0-9a-fA-F]{32}")) traceId=null;
        jdbc.update("""
            INSERT INTO audit_events(tenant,actor,run_id,event_type,resource,policy_version,payload_hash,trace_id)
            VALUES (?,?,?::uuid,?,?,?,?,?)
            """,tenant,actor,runId,event,resource,policyVersion,payloadHash,traceId);
    }

    List<JsonNode> rows(String sql, Object... params) {
        return jdbc.query(sql, this::mapRow, params);
    }

    Optional<JsonNode> first(String sql, Object... params) {
        return rows(sql, params).stream().findFirst();
    }

    JsonNode one(String sql, Object... params) {
        return first(sql, params).orElseThrow(() -> new StoreException(404, "Resource not found"));
    }

    private JsonNode mapRow(ResultSet rs, int rowNumber) throws SQLException {
        var meta = rs.getMetaData();
        ObjectNode obj = mapper.createObjectNode();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = camel(meta.getColumnLabel(i));
            Object value = rs.getObject(i);
            if (value instanceof PGobject pg
                    && ("jsonb".equals(pg.getType()) || "json".equals(pg.getType()))) {
                try {
                    obj.set(name, mapper.readTree(pg.getValue()));
                } catch (JsonProcessingException e) {
                    throw new SQLException("Invalid stored JSON", e);
                }
            } else if (value instanceof Timestamp t) {
                obj.put(name, t.toInstant().toString());
            } else {
                obj.set(name, mapper.valueToTree(value));
            }
        }
        return obj;
    }

    private static String camel(String column) {
        String[] words = column.split("_");
        StringBuilder result = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return result.toString();
    }

    static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf(0) >= 0)
            throw new StoreException(400, "Invalid " + name);
        return value;
    }

    static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new StoreException(400, "Invalid resource identifier");
        }
    }

    static String json(JsonNode value) {
        if (value == null) throw new StoreException(400, "Missing JSON value");
        String encoded = value.toString();
        if (encoded.length() > 1_000_000)
            throw new StoreException(413, "JSON value exceeds size limit");
        return encoded;
    }

    static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
