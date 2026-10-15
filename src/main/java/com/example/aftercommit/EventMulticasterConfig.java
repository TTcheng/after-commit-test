package com.example.aftercommit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;

/**
 * Activated by the {@code with-error-handler} profile.
 *
 * <p><b>Corrected by experiment</b> (the earlier version of this comment stated the opposite and was
 * disproven by {@code ErrorHandlerMulticasterTest} -- see README "重要事实更正"):
 *
 * <p>Spring Boot 3.x no longer ships {@code SimpleApplicationEventMulticasterAutoConfiguration}.
 * Verified against the exact jar on this classpath: {@code spring-boot-autoconfigure-3.2.5.jar}
 * contains no {@code *Multicaster*} class at all. So there is nothing for this bean to "override" --
 * when no {@link ApplicationEventMulticaster} bean exists, {@code AbstractApplicationContext}
 * simply instantiates its own {@link SimpleApplicationEventMulticaster} with no error handler.
 *
 * <p><b>The installed error handler is still never invoked</b>, in this profile or any other, for
 * {@code @TransactionalEventListener}:
 *
 * <ol>
 *   <li>{@code publishEvent} reaches each {@code TransactionalApplicationListener}, whose
 *       {@code onApplicationEvent} does NOT call the listener method -- it registers a
 *       {@code TransactionalApplicationListenerSynchronization}. Nothing is executed here.</li>
 *   <li>At commit, {@code TransactionSynchronizationUtils.invokeAfterCompletion} loops over the
 *       synchronizations and wraps <b>each individual {@code afterCompletion} call</b> in its own
 *       {@code try/catch (Throwable)}. Verified in bytecode: the exception table covers only the
 *       single invocation, and the handler logs then jumps back to {@code hasNext()}.</li>
 * </ol>
 *
 * <p>Consequently the listener exception never propagates back to the multicaster, so this
 * errorHandler is bypassed entirely. The caller still gets a normal return, every listener still
 * runs, and Spring logs the failure at ERROR.
 *
 * <p>Empirically the {@code [ErrorHandler] swallowed ...} line below <b>never appears</b> in the log
 * -- {@code ErrorHandlerMulticasterTest} now asserts that absence, so this config can no longer
 * silently stop being wired without failing the build.
 *
 * <p>This error handler only matters for non-transactional {@code @EventListener}, where all
 * listeners share one multicaster loop.
 */
@Configuration
@Profile("with-error-handler")
public class EventMulticasterConfig {

    private static final Logger log = LoggerFactory.getLogger(EventMulticasterConfig.class);

    @Bean
    public SimpleApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setErrorHandler(t -> {
            log.error("[ErrorHandler] swallowed listener exception", t);
        });
        return multicaster;
    }
}
