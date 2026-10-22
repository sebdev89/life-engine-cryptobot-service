package io.lifeengine.cryptobot.signer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The signer's own velocity limit (KAN-602, audit G12): at most {@code signer.max-lamports-per-day} lamports signed
 * in any rolling 24 h window, decided here and nowhere else, independently of what the service or the validator
 * concluded. {@code max-lamports} caps one transfer; this caps how many of them add up, so a compromised service
 * that keeps proposing in-cap transfers to an allow-listed destination still cannot drain the wallet.
 *
 * <p>Reserved at signing time, after the policy and the attestation passed: a refused request never uses budget.
 * Persisted to {@code signer.spend-ledger-path} (one {@code epochMillis lamports} line per signature, rewritten
 * atomically), because the signer has no database. Without a path the window lives in memory and a restart resets
 * it, so with mainnet allowed a path is mandatory and the signer refuses to start without one (fail-closed). An
 * unreadable or corrupt ledger file is fail-closed too: the signer does not start rather than forget what it signed.
 */
@Component
public class DailySpendLedger {

    private static final Logger log = LoggerFactory.getLogger(DailySpendLedger.class);
    static final Duration WINDOW = Duration.ofHours(24);

    private record Spend(long epochMillis, long lamports) {}

    private final long maxPerDay;
    private final Path path;
    private final Clock clock;
    private final Deque<Spend> window = new ArrayDeque<>();

    @Autowired
    public DailySpendLedger(SignerProperties props) {
        this(props, Clock.systemUTC());
    }

    DailySpendLedger(SignerProperties props, Clock clock) {
        this.maxPerDay = props.maxLamportsPerDay();
        this.path = props.spendLedgerPath().isEmpty() ? null : Path.of(props.spendLedgerPath());
        this.clock = clock;
        if (props.allowMainnet() && path == null) {
            throw new IllegalStateException("signer.allow-mainnet=true requires signer.spend-ledger-path (SIGNER_SPEND_LEDGER_PATH):"
                    + " a daily cap that a restart resets is no cap for real funds");
        }
        load();
        log.info("signer_daily_cap max_lamports_per_day={} ledger={} spent_in_window={}", maxPerDay, path == null ? "memory" : path, spentInWindow());
    }

    /**
     * Reserves {@code lamports} if the rolling 24 h total stays within the daily cap, and records it.
     * @return false = over the daily cap; nothing is recorded and the caller must not sign
     */
    public synchronized boolean tryReserve(long lamports) {
        long now = clock.millis();
        prune(now);
        long spent = sum();
        if (lamports < 0 || spent + lamports > maxPerDay) {
            return false;
        }
        window.addLast(new Spend(now, lamports));
        persist();
        return true;
    }

    /** Lamports signed in the last 24 h. */
    public synchronized long spentInWindow() {
        prune(clock.millis());
        return sum();
    }

    public long maxPerDay() {
        return maxPerDay;
    }

    private long sum() {
        long total = 0;
        for (Spend s : window) {
            total += s.lamports();
        }
        return total;
    }

    private void prune(long now) {
        long oldest = now - WINDOW.toMillis();
        while (!window.isEmpty() && window.peekFirst().epochMillis() <= oldest) {
            window.removeFirst();
        }
    }

    private void load() {
        if (path == null || !Files.exists(path)) {
            return;
        }
        List<Spend> read = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) {
                    throw new IllegalStateException("corrupt spend ledger line: " + line);
                }
                long lamports = Long.parseLong(parts[1]);
                if (lamports < 0) {
                    throw new IllegalStateException("negative amount in spend ledger: " + line);
                }
                read.add(new Spend(Long.parseLong(parts[0]), lamports));
            }
        } catch (IOException | NumberFormatException ex) {
            throw new IllegalStateException("unreadable spend ledger " + path + ": " + ex.getMessage(), ex);
        }
        read.sort((a, b) -> Long.compare(a.epochMillis(), b.epochMillis()));
        window.addAll(read);
        prune(clock.millis());
    }

    private void persist() {
        if (path == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Spend s : window) {
            sb.append(s.epochMillis()).append(' ').append(s.lamports()).append('\n');
        }
        try {
            Path dir = path.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, ".spend-ledger", ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // the signature this reservation is for has not been produced yet: failing here keeps the cap honest
            window.removeLast();
            throw new IllegalStateException("cannot persist spend ledger " + path + ": " + ex.getMessage(), ex);
        }
    }
}
