package com.pglens.cli;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

/**
 * picocli factory backed by the Spring {@link ApplicationContext}: command objects are resolved as
 * Spring beans (so they can inject engine collaborators), falling back to picocli's default factory
 * for everything else. This replaces {@code picocli-spring-boot-starter}, whose latest release lags
 * the current Spring Boot line.
 */
@Component
class SpringPicocliFactory implements CommandLine.IFactory {

  private final ApplicationContext context;
  private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

  SpringPicocliFactory(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public <K> K create(Class<K> cls) throws Exception {
    try {
      return context.getBean(cls);
    } catch (RuntimeException notASpringBean) {
      return fallback.create(cls);
    }
  }
}
