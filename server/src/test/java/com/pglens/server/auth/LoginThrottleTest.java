package com.pglens.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

  private static final Instant T0 = Instant.parse("2026-09-26T10:00:00Z");
  private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15));

  @Test
  void blocksAUsernameAfterTheLimitUntilTheWindowPasses() {
    for (int i = 0; i < 3; i++) {
      assertThat(throttle.blockedFor("alice", T0)).isEmpty();
      throttle.recordFailure("alice", T0.plusSeconds(i));
    }

    assertThat(throttle.blockedFor("alice", T0.plusSeconds(60))).contains(Duration.ofMinutes(14));
    assertThat(throttle.blockedFor("ALICE", T0.plusSeconds(60))).isPresent(); // case-insensitive
    assertThat(throttle.blockedFor("bob", T0.plusSeconds(60))).isEmpty(); // per username
    assertThat(throttle.blockedFor("alice", T0.plus(Duration.ofMinutes(15)))).isEmpty();
  }

  @Test
  void aSuccessClearsTheCount() {
    throttle.recordFailure("alice", T0);
    throttle.recordFailure("alice", T0);
    throttle.recordSuccess("alice");
    throttle.recordFailure("alice", T0);

    assertThat(throttle.blockedFor("alice", T0)).isEmpty();
  }

  @Test
  void failuresOutsideTheWindowStartANewCount() {
    throttle.recordFailure("alice", T0);
    throttle.recordFailure("alice", T0);
    throttle.recordFailure("alice", T0.plus(Duration.ofMinutes(20)));

    assertThat(throttle.blockedFor("alice", T0.plus(Duration.ofMinutes(21)))).isEmpty();
  }
}
