package com.straycat.statistra.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Boots the full application against real Postgres and Kafka containers.
 *
 * <p>The previous test suite could not pass anywhere: {@code contextLoads}
 * needed a Postgres already running on the developer's machine, so a clean
 * checkout and CI both failed. Containers make the dependencies part of the
 * test rather than a precondition of it.
 *
 * <p>Note {@link Import} rather than {@code @ContextConfiguration(classes=...)}:
 * the latter <em>replaces</em> the primary configuration, so Boot never finds
 * {@code StatistraApplication} and the context comes up with no web server.
 * Importing adds the containers alongside the usual component scan.
 *
 * <p>{@link ServiceConnection} wires each container's generated address into the
 * datasource and Kafka bootstrap properties, so no connection details are
 * hardcoded anywhere in the test tree.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.Containers.class)
public @interface IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));
        }
    }
}
