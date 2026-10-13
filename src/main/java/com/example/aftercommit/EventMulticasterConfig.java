package com.example.aftercommit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.SimpleApplicationEventMulticaster;

/**
 * Activated by the "with-error-handler" profile.
 *
 *   - WITHOUT this profile (default):
 *       Spring uses its default SimpleApplicationEventMulticaster with no errorHandler.
 *       ListenerB throws -> ListenerC is skipped, exception propagates to the caller.
 *
 *   - WITH    this profile:
 *       We install a SimpleApplicationEventMulticaster with an errorHandler.
 *       ListenerB throws -> the errorHandler swallows it, ListenerC still runs,
 *       no exception propagates to the caller.
 */
@Configuration
@Profile("with-error-handler")
public class EventMulticasterConfig {

    @Bean
    public SimpleApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(t ->
                System.err.println("[ErrorHandler] swallowed listener exception: " + t.getMessage()));
        return multicaster;
    }
}