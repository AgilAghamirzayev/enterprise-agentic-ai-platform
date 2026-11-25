package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

final class Definitions {
    private final Database db;

    Definitions(Database db) {
        this.db = db;
    }

    JsonNode save(
            String tenant,
            String actor,
            String kind,
            String name,
            String version,
            JsonNode spec,
            boolean publish) {
        Database.requireText(actor, "actor", 200);
        Database.requireText(kind, "kind", 64);
        Database.requireText(name, "name", 128);
        Database.requireText(version, "version", 64);
        if (spec == null || !spec.isObject())
            throw new StoreException(400, "A definition object is required");
        String json = Database.json(spec);
        return db.scoped(
                tenant,
                () -> {
                    String lockKey =
                            db.mapper
                                    .createArrayNode()
                                    .add(tenant)
                                    .add(kind)
                                    .add(name)
                                    .add(version)
                                    .toString();
                    db.jdbc.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", lockKey);
                    if (db.first(
                                    "SELECT 1 FROM definitions WHERE tenant=? AND kind=? AND name=?"
                                            + " AND version=?",
                                    tenant,
                                    kind,
                                    name,
                                    version)
                            .isPresent())
                        throw new StoreException(409, "Published version already exists");
                    if (publish
                            && db.first(
                                            "SELECT 1 FROM drafts WHERE tenant=? AND kind=? AND"
                                                    + " name=? AND version=? AND spec<>?::jsonb",
                                            tenant,
                                            kind,
                                            name,
                                            version,
                                            json)
                                    .isPresent())
                        throw new StoreException(409, "Draft changed before publication");
                    String sql =
                            publish
                                    ? """
                                    INSERT INTO definitions(tenant,kind,name,version,spec) VALUES (?,?,?,?,?::jsonb) RETURNING *
                                    """
                                    : """
                                    INSERT INTO drafts(tenant,kind,name,version,spec) VALUES (?,?,?,?,?::jsonb)
                                    ON CONFLICT(tenant,kind,name,version) DO UPDATE SET spec=excluded.spec RETURNING *
                                    """;
                    JsonNode result = db.one(sql, tenant, kind, name, version, json);
                    db.audit(
                            tenant,
                            actor,
                            null,
                            publish ? "definition.published" : "definition.drafted",
                            kind + "/" + name + "/" + version);
                    return result;
                });
    }

    JsonNode get(String tenant, String kind, String name, String version, boolean draft) {
        String sql =
                draft
                        ? """
                        SELECT d.* FROM drafts d WHERE tenant=? AND kind=? AND name=? AND version=?
                        AND NOT EXISTS (SELECT 1 FROM definitions f WHERE (f.tenant,f.kind,f.name,f.version)=(d.tenant,d.kind,d.name,d.version))
                        """
                        : "SELECT * FROM definitions WHERE tenant=? AND kind=? AND name=? AND"
                                + " version=?";
        return db.scoped(tenant, () -> db.one(sql, tenant, kind, name, version));
    }

    List<JsonNode> list(String tenant, String kind) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT * FROM definitions WHERE tenant=? AND kind=? ORDER BY"
                                        + " created_at DESC,name,version LIMIT 200",
                                tenant,
                                kind));
    }
}
