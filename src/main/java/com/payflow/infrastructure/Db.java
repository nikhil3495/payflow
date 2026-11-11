package com.payflow.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Small JDBC mapping helpers shared by the repositories. */
final class Db {

  private Db() {}

  static OffsetDateTime ts(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }
}
