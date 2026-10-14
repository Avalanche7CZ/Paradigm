package eu.avalanche7.paradigm.storage.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.StorageTransactionException.Outcome;

public class SqlExecutor {
    private final SqlConnectionProvider connections;
    private final ReentrantLock operationLock;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public SqlExecutor(SqlConnectionProvider connections) {
        this.connections = connections;
        this.operationLock = connections.operationLock();
    }

    public String dialectName() {
        return connections.dialect().name();
    }

    public int update(String sql, Binder binder) {
        return withOperationLock(() -> updateOnConnection(sql, binder));
    }

    public <T> T query(String sql, Binder binder, ResultMapper<T> mapper) {
        return withOperationLock(() -> queryOnConnection(sql, binder, mapper));
    }

    public void transaction(Runnable work) {
        transaction(() -> {
            work.run();
            return null;
        });
    }

    public <T> T transaction(Supplier<T> work) {
        Connection active = transactionConnection.get();
        if (active != null) {
            return work.get();
        }
        return withOperationLock(() -> {
            boolean committed = false;
            try (Connection connection = connections.getConnection()) {
                connection.setAutoCommit(false);
                transactionConnection.set(connection);
                try {
                    T result;
                    try {
                        result = work.get();
                    } catch (RuntimeException | Error failure) {
                        if (!rollback(connection, failure))
                            throw new StorageTransactionException("SQL rollback could not be confirmed", failure, Outcome.UNKNOWN);
                        throw failure;
                    }
                    try {
                        connection.commit();
                    } catch (SQLException | RuntimeException failure) {
                        rollback(connection, failure);
                        throw new StorageTransactionException("SQL commit outcome is unknown", failure, Outcome.UNKNOWN);
                    }
                    committed = true;
                    return result;
                } finally {
                    transactionConnection.remove();
                }
            } catch (SQLException | RuntimeException failure) {
                if (committed) throw new StorageTransactionException("SQL committed, but connection cleanup failed", failure, Outcome.COMMITTED);
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new StorageException("SQL transaction could not start: " + failure.getMessage(), failure);
            }
        });
    }

    private int updateOnConnection(String sql, Binder binder) {
        Connection active = transactionConnection.get();
        if (active != null) {
            return executeUpdate(active, sql, binder);
        }
        try (Connection connection = connections.getConnection()) {
            return executeUpdate(connection, sql, binder);
        } catch (SQLException failure) {
            throw new StorageException("SQL update failed: " + failure.getMessage(), failure);
        }
    }

    private int executeUpdate(Connection connection, String sql, Binder binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new StorageException("SQL update failed: " + failure.getMessage(), failure);
        }
    }

    private <T> T queryOnConnection(String sql, Binder binder, ResultMapper<T> mapper) {
        Connection active = transactionConnection.get();
        if (active != null) {
            return executeQuery(active, sql, binder, mapper);
        }
        try (Connection connection = connections.getConnection()) {
            return executeQuery(connection, sql, binder, mapper);
        } catch (SQLException failure) {
            throw new StorageException("SQL query failed: " + failure.getMessage(), failure);
        }
    }

    private <T> T executeQuery(Connection connection, String sql, Binder binder, ResultMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapper.map(resultSet);
            }
        } catch (SQLException failure) {
            throw new StorageException("SQL query failed: " + failure.getMessage(), failure);
        }
    }

    private <T> T withOperationLock(Supplier<T> work) {
        if (operationLock == null || operationLock.isHeldByCurrentThread()) {
            return work.get();
        }
        operationLock.lock();
        try {
            return work.get();
        } finally {
            operationLock.unlock();
        }
    }

    private static boolean rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return false;
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
