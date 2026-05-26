package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * Enables the R2DBC repository scan for the cryptobot observability package. Kept tiny and
 * package-scoped so the auto-configuration excludes in {@code application-test.yml} can
 * neutralise it cleanly.
 *
 * <p>{@code @Profile("!test")} keeps Spring Data R2DBC away from the {@code test} profile —
 * the test slice provides Mockito stubs for every repository through
 * {@code StubRepositoriesConfiguration} so no {@code DatabaseClient} is needed.
 */
@Profile("!test")
@Configuration
@EnableR2dbcRepositories(basePackages = "io.lifeengine.cryptobot.infrastructure.persistence.r2dbc")
class R2dbcConfiguration {}
