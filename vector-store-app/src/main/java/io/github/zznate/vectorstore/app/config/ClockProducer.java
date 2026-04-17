package io.github.zznate.vectorstore.app.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

@ApplicationScoped
public class ClockProducer {

  @Produces
  @Singleton
  public Clock clock() {
    return Clock.systemUTC();
  }
}
