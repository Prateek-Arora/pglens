package com.pglens.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link SqlIdent} writes identifiers as {@code quote_ident} does (checked against PG by an IT).
 */
class SqlIdentTest {

  @Test
  void leavesPlainLowerCaseNamesBare() {
    assertThat(SqlIdent.quote("orders")).isEqualTo("orders");
    assertThat(SqlIdent.quote("_tmp1")).isEqualTo("_tmp1");
  }

  @Test
  void quotesMixedCaseReservedAndUnusualNames() {
    assertThat(SqlIdent.quote("UserAccounts")).isEqualTo("\"UserAccounts\"");
    assertThat(SqlIdent.quote("order")).isEqualTo("\"order\"");
    assertThat(SqlIdent.quote("user")).isEqualTo("\"user\"");
    assertThat(SqlIdent.quote("1st")).isEqualTo("\"1st\"");
    assertThat(SqlIdent.quote("a$b")).isEqualTo("\"a$b\"");
    assertThat(SqlIdent.quote("Émile")).isEqualTo("\"Émile\"");
    assertThat(SqlIdent.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
  }

  @Test
  void identifiesATableBySchemaUnlessItIsPublic() {
    assertThat(SqlIdent.table("public", "orders")).isEqualTo("orders");
    assertThat(SqlIdent.table(null, "Post")).isEqualTo("\"Post\"");
    assertThat(SqlIdent.table("app", "UserAccounts")).isEqualTo("app.\"UserAccounts\"");
    assertThat(SqlIdent.table("Billing", "order")).isEqualTo("\"Billing\".\"order\"");
  }

  @Test
  void splitsAndUnquotesADottedName() {
    assertThat(SqlIdent.parts("app.\"User.Accounts\"")).containsExactly("app", "User.Accounts");
    assertThat(SqlIdent.parts("\"a\"\"b\"")).containsExactly("a\"b");
    assertThat(SqlIdent.parts("Orders")).containsExactly("orders");
    assertThat(SqlIdent.parts("\"unterminated")).isEmpty();
    assertThat(SqlIdent.relationName("app.\"UserAccounts\"")).isEqualTo("UserAccounts");
    assertThat(SqlIdent.schemaName("orders")).isEqualTo("public");
  }

  @Test
  void recognisesSingleIdentifierTokensOnly() {
    assertThat(SqlIdent.isIdentifier("\"authorId\"")).isTrue();
    assertThat(SqlIdent.isIdentifier("customer_id")).isTrue();
    assertThat(SqlIdent.isIdentifier("lower(email)")).isFalse();
    assertThat(SqlIdent.isIdentifier("a + b")).isFalse();
  }
}
