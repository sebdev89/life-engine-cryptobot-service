package io.lifeengine.cryptobot.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * (TAE phase 1, audit §20/§21) — the boundaries the phase 1 package move is supposed to
 * hold, enforced mechanically instead of by review:
 *
 * <ul>
 *   <li>{@code core} depends on nothing of this service's own code but {@code core} itself
 *       (JDK/Spring/reactor/etc. are untouched by these rules — they only forbid depending on our
 *       own {@code solana}, {@code trading} or the legacy orchestration layer).
 *   <li>{@code solana} (the Solana adapter) never depends on {@code trading} (the vertical).
 *   <li>{@code trading}'s pure domain (risk/strategy/portfolio/advisor) never depends on
 *       {@code solana.tx} (the transaction builder/signer's shape) nor on {@code integration.signer}.
 *   <li>{@code integration.signer.SignerClient} is not called from {@code core}, {@code solana} or
 *       {@code trading} today.
 * </ul>
 *
 * <p><b>What this does not yet check</b>: {@code ExecutionService}, {@code SimulationService},
 * {@code PolicyEngine} and {@code ProposalService} still live in {@code application.controlplane}
 * and still import {@code solana.rpc}/{@code solana.tx}/{@code integration.signer}/{@code
 * integration.validator} directly (audit gap G9) — they are not inside {@code core}. The chain-ports change
 * introduced {@code core.ports} ({@code ChainSimulationPort}/{@code ChainExecutionPort}/{@code
 * ChainObservationPort}/{@code AssetPort}/{@code AnchorPort}, implemented by {@code
 * SolanaChainAdapter}/{@code AnchorService}) but deliberately left these four callers where they
 * are: {@code PolicyEngine} and {@code ProposalService} depend directly on {@code trading.strategy}
 * (e.g. {@code RebalanceLeg}) the same way {@code ActionProposal} does — moving them into {@code
 * core} today would either fail this test for real or need the same kind of documented exception,
 * which is exactly the canonical-intent rewrite (gap G2) owns, not a mechanical ports PR.
 * Wiring {@code ExecutionService}/{@code SimulationService}/{@code AnchorService} onto the new
 * ports without relocating them is future work (proposal: {@code
 * cryptobot-execution-uses-chain-ports.md}); this test's rules do not need to change for that
 * either way — a class either belongs in {@code core} and complies, or it stays out.
 */
class ModuleBoundariesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.lifeengine.cryptobot");
    }

    /**
     * {@code ActionProposal} is the one documented exception: it still holds {@code
     * RebalanceIntent}/{@code RebalancePlan} (trading.strategy) and {@code RiskReport}
     * (trading.risk) as fields — gap G2 in the audit, "the canonical intent is not wired; the
     * aggregate carries the trading plan directly instead of a generic {@code ExecutionIntent}".
     * (phase 2, already adopted in the plan) is the story that replaces this with the
     * canonical intent; forcing that rewrite here would be exactly the "split with new logic" this
     * mechanical PR is not supposed to do. Every other class in {@code core} is held to the rule
     * with no exception.
     */
    @Test
    void coreDependsOnNothingOfOursButCore() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.lifeengine.cryptobot.core..")
                .and().haveNameNotMatching("io\\.lifeengine\\.cryptobot\\.core\\.execution\\.ActionProposal")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.lifeengine.cryptobot.solana..",
                        "io.lifeengine.cryptobot.trading..",
                        "io.lifeengine.cryptobot.application..",
                        "io.lifeengine.cryptobot.api..",
                        "io.lifeengine.cryptobot.infrastructure..",
                        "io.lifeengine.cryptobot.integration..",
                        "io.lifeengine.cryptobot.adapters..")
                .because("(audit §21): core is the trusted execution aggregate — it must not know"
                        + " about the Solana adapter, the trading vertical, or the legacy orchestration layer."
                        + " ExecutionService/SimulationService/PolicyEngine/ProposalService are not in core yet"
                        + " (internal ticket moves them, behind the chain ports) for exactly this reason.");
        rule.check(classes);
    }

    @Test
    void solanaAdapterDoesNotDependOnTrading() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.lifeengine.cryptobot.solana..")
                .should().dependOnClassesThat().resideInAnyPackage("io.lifeengine.cryptobot.trading..")
                .because("(audit §21): the Solana adapter is a chain-facing implementation detail;"
                        + " it must not know about the trading vertical it never talks to.");
        rule.check(classes);
    }

    @Test
    void tradingDomainDoesNotDependOnTheSigningPath() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "io.lifeengine.cryptobot.trading.risk..",
                        "io.lifeengine.cryptobot.trading.strategy..",
                        "io.lifeengine.cryptobot.trading.portfolio..",
                        "io.lifeengine.cryptobot.trading.advisor..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.lifeengine.cryptobot.solana.tx..",
                        "io.lifeengine.cryptobot.core.signing..",
                        "io.lifeengine.cryptobot.integration.signer..")
                .because("(audit §21): the trading vertical proposes a plan; it never touches the"
                        + " transaction bytes, the (future) signing core package, or the signer client.");
        rule.check(classes);
    }

    @Test
    void signerClientIsNotCalledFromCoreSolanaOrTradingYet() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "io.lifeengine.cryptobot.core..",
                        "io.lifeengine.cryptobot.solana..",
                        "io.lifeengine.cryptobot.trading..")
                .and().resideOutsideOfPackage("io.lifeengine.cryptobot.core.signing..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("io.lifeengine.cryptobot.integration.signer.SignerClient")
                .because("(audit §21): 'the signer client is reachable only from core.signing'."
                        + " Still nothing in core/solana/trading calls it (ExecutionService, the one caller,"
                        + " is still in application.controlplane, deliberately — core.ports was introduced"
                        + " without relocating it); moving it into core.signing without breaking this rule is"
                        + " future work, tracked with the chain-ports-callers proposal.");
        rule.check(classes);
    }
}
