package com.pglens.explain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pglens.explain.KnowledgeCards.Card;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KnowledgeCardsTest {

  @ParameterizedTest
  @ValueSource(strings = {"R1", "R3", "R4", "R7", "estimate", "not-validated"})
  void everyEngineRuleAndStatusHasACardWithDocsLinks(String id) {
    Card card = KnowledgeCards.load(id).orElseThrow();

    assertThat(card.title()).isNotBlank();
    assertThat(card.body()).isNotBlank().doesNotContain("Docs:");
    assertThat(card.docs()).isNotEmpty().allMatch(u -> u.startsWith("https://"));
  }

  @Test
  void cardsFollowTheFactsRulesThenTheStatus() {
    assertThat(KnowledgeCards.forFacts(EvalCases.byId("04-").facts()))
        .extracting(Card::id)
        .containsExactly("R1", "R4", "estimate");
    assertThat(KnowledgeCards.forFacts(EvalCases.byId("08-").facts()))
        .extracting(Card::id)
        .containsExactly("R7", "not-validated");
  }

  @Test
  void anUnknownIdHasNoCard() {
    assertThat(KnowledgeCards.load("R99")).isEmpty();
  }
}
