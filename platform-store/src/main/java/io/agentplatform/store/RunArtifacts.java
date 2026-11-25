package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.*;
import java.util.*;

public final class RunArtifacts {
    private final Database db;

    RunArtifacts(Database db) {
        this.db = db;
    }

    public record Content(String mediaType, byte[] bytes) {}

    public JsonNode put(
            String tenant, String run, String actor, String name, String type, byte[] bytes) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")
                || bytes.length == 0
                || bytes.length > 524288)
            throw new StoreException(400, "Invalid artifact name or size");
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return db.scoped(
                tenant,
                () -> {
                    // Serializes concurrent uploads against the per-run count and byte limits.
                    db.one(
                            "SELECT id FROM runs WHERE tenant=? AND id=?::uuid FOR UPDATE",
                            tenant,
                            run);
                    var previous =
                            db.first(
                                    "SELECT digest,media_type,name FROM run_artifacts WHERE"
                                        + " tenant=? AND run_id=?::uuid AND name=?",
                                    tenant,
                                    run,
                                    name);
                    if (previous.isPresent()) {
                        if (!digest.equals(previous.get().path("digest").asText())
                                || !type.equals(previous.get().path("mediaType").asText()))
                            throw new StoreException(
                                    409, "Artifact name already has different content");
                        return previous.get();
                    }
                    var totals =
                            db.one(
                                    "SELECT count(*) AS"
                                        + " files,coalesce(sum(octet_length(content)),0) AS bytes"
                                        + " FROM run_artifacts WHERE tenant=? AND run_id=?::uuid",
                                    tenant,
                                    run);
                    if (totals.path("files").asInt() >= 32
                            || totals.path("bytes").asLong() + bytes.length > 8388608)
                        throw new StoreException(409, "Run artifact limit reached");
                    var row =
                            db.one(
                                    "INSERT INTO"
                                        + " run_artifacts(tenant,run_id,name,media_type,digest,content)"
                                        + " VALUES (?,?::uuid,?,?,?,?) RETURNING"
                                        + " digest,media_type,name",
                                    tenant,
                                    run,
                                    name,
                                    type,
                                    digest,
                                    bytes);
                    db.audit(tenant, actor, run, "artifact.created", name);
                    return row;
                });
    }

    public List<JsonNode> list(String tenant, String run) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT name,digest,media_type,octet_length(content) AS"
                                    + " size_bytes,created_at FROM run_artifacts WHERE tenant=? AND"
                                    + " run_id=?::uuid ORDER BY name",
                                tenant,
                                run));
    }

    public Content get(String tenant, String run, String actor, String name) {
        return db.scoped(
                tenant,
                () -> {
                    var content =
                            db
                                    .jdbc
                                    .query(
                                            "SELECT media_type,content FROM run_artifacts WHERE"
                                                + " tenant=? AND run_id=?::uuid AND name=?",
                                            (r, i) -> new Content(r.getString(1), r.getBytes(2)),
                                            tenant,
                                            run,
                                            name)
                                    .stream()
                                    .findFirst()
                                    .orElseThrow(
                                            () -> new StoreException(404, "Artifact not found"));
                    db.audit(tenant, actor, run, "artifact.downloaded", name);
                    return content;
                });
    }
}
