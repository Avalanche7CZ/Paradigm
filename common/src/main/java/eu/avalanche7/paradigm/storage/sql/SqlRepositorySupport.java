package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredLocation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

abstract class SqlRepositorySupport {
    protected final SqlExecutor sql;
    protected final StorageContext context;

    protected SqlRepositorySupport(SqlExecutor sql, StorageContext context) {
        this.sql = sql;
        this.context = context;
    }

    protected String serverId() {
        return context != null ? context.serverId() : "default";
    }

    protected void bindLocation(PreparedStatement ps, int start, StoredLocation location) throws SQLException {
        ps.setString(start, location.worldId());
        ps.setDouble(start + 1, location.x());
        ps.setDouble(start + 2, location.y());
        ps.setDouble(start + 3, location.z());
        ps.setFloat(start + 4, location.yaw());
        ps.setFloat(start + 5, location.pitch());
    }

    protected StoredLocation readLocation(ResultSet rs) throws SQLException {
        return new StoredLocation(
                rs.getString("world_id"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch")
        );
    }
}
