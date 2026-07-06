package eu.avalanche7.paradigm.storage.sql;

import eu.avalanche7.paradigm.storage.StorageException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SqlExecutor {
    private final SqlConnectionProvider connections;

    public SqlExecutor(SqlConnectionProvider connections) {
        this.connections = connections;
    }

    public int update(String sql, Binder binder) {
        try (Connection connection = connections.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("SQL update failed: " + e.getMessage(), e);
        }
    }

    public <T> T query(String sql, Binder binder, ResultMapper<T> mapper) {
        try (Connection connection = connections.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            try (ResultSet rs = statement.executeQuery()) {
                return mapper.map(rs);
            }
        } catch (SQLException e) {
            throw new StorageException("SQL query failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    public interface ResultMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
