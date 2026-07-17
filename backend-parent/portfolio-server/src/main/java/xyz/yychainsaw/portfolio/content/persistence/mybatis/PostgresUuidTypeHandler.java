package xyz.yychainsaw.portfolio.content.persistence.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

@Component
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            UUID parameter,
            JdbcType jdbcType) throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getObject(columnName, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getObject(columnIndex, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getObject(columnIndex, UUID.class);
    }
}
