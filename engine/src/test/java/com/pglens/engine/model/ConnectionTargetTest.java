package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConnectionTargetTest {

  @Test
  void parsesLibpqUriWithCredentials() {
    ConnectionTarget t =
        ConnectionTarget.parse("postgresql://pglens:secret@localhost:5433/pglens_demo");
    assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5433/pglens_demo");
    assertThat(t.user()).isEqualTo("pglens");
    assertThat(t.password()).isEqualTo("secret");
    assertThat(t.database()).isEqualTo("pglens_demo");
  }

  @Test
  void defaultsPortTo5432AndAllowsNoPassword() {
    ConnectionTarget t = ConnectionTarget.parse("postgres://reader@db.example.com/app");
    assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/app");
    assertThat(t.user()).isEqualTo("reader");
    assertThat(t.password()).isNull();
  }

  @Test
  void acceptsJdbcPrefixedUrl() {
    ConnectionTarget t = ConnectionTarget.parse("jdbc:postgresql://h:6000/d");
    assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://h:6000/d");
    assertThat(t.database()).isEqualTo("d");
  }

  @Test
  void carriesQueryParametersThrough() {
    ConnectionTarget t = ConnectionTarget.parse("postgresql://u:p@h:5432/d?sslmode=require");
    assertThat(t.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/d?sslmode=require");
  }

  @Test
  void rejectsNonPostgresScheme() {
    assertThatThrownBy(() -> ConnectionTarget.parse("mysql://h/d"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingDatabase() {
    assertThatThrownBy(() -> ConnectionTarget.parse("postgresql://u@h:5432/"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankInput() {
    assertThatThrownBy(() -> ConnectionTarget.parse("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
