package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Membership data is always read inside the authenticated organization's directory partition. */
public final class WorkspaceDirectory {
    private final Database db;

    WorkspaceDirectory(Database db) {
        this.db = db;
    }

    public JsonNode tenant(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.one(
                                "SELECT ?::text AS tenant, coalesce((SELECT display_name FROM"
                                    + " tenant_directory WHERE tenant=?), ?) AS display_name",
                                tenant,
                                tenant,
                                tenant));
    }

    public JsonNode nameTenant(String tenant, String actor, String title) {
        return db.scoped(
                tenant,
                () -> {
                    var result =
                            db.one(
                                    "INSERT INTO tenant_directory(tenant,display_name) VALUES (?,?)"
                                        + " ON CONFLICT (tenant) DO UPDATE SET"
                                        + " display_name=excluded.display_name RETURNING"
                                        + " tenant,display_name",
                                    tenant,
                                    title);
                    db.audit(tenant, actor, null, "tenant.configured", tenant);
                    return result;
                });
    }

    public List<JsonNode> list(String tenant, String actor, boolean admin) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT w.name, w.display_name FROM workspaces w
                                WHERE w.tenant=? AND (? OR EXISTS (SELECT 1 FROM workspace_memberships m
                                    WHERE m.tenant=w.tenant AND m.workspace=w.name AND m.subject=? AND jsonb_array_length(m.roles)>0))
                                ORDER BY w.name
                                """,
                                tenant,
                                admin,
                                actor));
    }

    public JsonNode create(String tenant, String actor, String name, String title) {
        return db.scoped(
                tenant,
                () -> {
                    db.jdbc.update(
                            "INSERT INTO tenant_directory VALUES (?,?) ON CONFLICT DO NOTHING",
                            tenant,
                            tenant);
                    var inserted =
                            db.first(
                                    "INSERT INTO workspaces VALUES (?,?,?) ON CONFLICT DO NOTHING"
                                        + " RETURNING name,display_name",
                                    tenant,
                                    name,
                                    title);
                    if (inserted.isEmpty())
                        throw new StoreException(409, "Workspace name already exists");
                    db.audit(tenant, actor, null, "workspace.created", name);
                    return inserted.get();
                });
    }

    public List<JsonNode> members(String tenant, String name) {
        return db.scoped(
                tenant,
                () -> {
                    ensureWorkspace(tenant, name);
                    return db.rows(
                            "SELECT subject,roles FROM workspace_memberships WHERE tenant=? AND"
                                + " workspace=? ORDER BY subject",
                            tenant,
                            name);
                });
    }

    public JsonNode assign(
            String tenant, String actor, String name, String subject, List<String> roles) {
        return db.scoped(
                tenant,
                () -> {
                    ensureWorkspace(tenant, name);
                    var result =
                            db.one(
                                    """
                                    INSERT INTO workspace_memberships VALUES (?,?,?,?::jsonb)
                                    ON CONFLICT (tenant,workspace,subject) DO UPDATE SET roles=excluded.roles RETURNING subject,roles
                                    """,
                                    tenant,
                                    name,
                                    subject,
                                    db.mapper.valueToTree(roles).toString());
                    db.audit(
                            tenant,
                            actor,
                            null,
                            roles.isEmpty()
                                    ? "workspace.membership.revoked"
                                    : "workspace.membership.assigned",
                            name + "/" + subject);
                    return result;
                });
    }

    public List<String> grants(String tenant, String name, String actor, boolean tenantAdmin) {
        return db.scoped(
                tenant,
                () -> {
                    if (tenantAdmin) {
                        ensureWorkspace(tenant, name);
                        return List.of("admin");
                    }
                    return db.jdbc.queryForList(
                            """
                            SELECT jsonb_array_elements_text(roles) FROM workspace_memberships
                            WHERE tenant=? AND workspace=? AND subject=?
                            """,
                            String.class,
                            tenant,
                            name,
                            actor);
                });
    }

    private void ensureWorkspace(String tenant, String name) {
        db.one("SELECT name FROM workspaces WHERE tenant=? AND name=?", tenant, name);
    }
}
