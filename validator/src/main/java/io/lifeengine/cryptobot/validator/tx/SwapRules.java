package io.lifeengine.cryptobot.validator.tx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * KAN-600: what the validator requires of a Jupiter swap transaction before it would attest it. It reads every
 * top-level instruction of the decoded bytes and allows only what an exact-in swap for the owner needs:
 *
 * <ul>
 *   <li>one required signature, the owner's, still empty;
 *   <li>compute budget: unit limit and unit price only, with the priority fee capped;
 *   <li>associated-token-account creation paid by and for the owner;
 *   <li>a system transfer only from the owner to the owner's own wrapped-SOL account, never above the swap amount;
 *   <li>SPL token: sync-native and close of the owner's wrapped-SOL account back to the owner — nothing else (no
 *       transfer, no approve);
 *   <li>exactly one Jupiter v6 {@code route} or {@code shared_accounts_route}: the owner as authority, the owner's
 *       token accounts for the input and output mints, no platform fee, the input amount of the intent, slippage
 *       within the intent's maximum and a minimum received ({@code quoted_out × (10000 − slippage) / 10000}, the
 *       bound the Jupiter program enforces on chain) not below the intent's;
 *   <li>any other program or instruction is a violation.
 * </ul>
 *
 * <p>An account the rules need to identify that comes from a lookup table is a violation too: unresolved means
 * unknown, and unknown fails closed. What happens inside Jupiter's route (the pools it calls) is not visible at this
 * level; the on-chain minimum-received check is what bounds it.
 */
public final class SwapRules {

    public static final String JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
    static final byte[] ROUTE = discriminator("route");
    static final byte[] SHARED_ACCOUNTS_ROUTE = discriminator("shared_accounts_route");

    /** What the swap must be. {@code maxPriorityFeeLamports} bounds unit limit × unit price. */
    public record SwapIntent(
            String owner,
            String inputMint,
            String outputMint,
            long inAmount,
            long minOutAmount,
            int maxSlippageBps,
            long maxPriorityFeeLamports) {}

    public record Violation(String code, String detail) {}

    /** What was read from the Jupiter instruction (null when there was none to read). */
    public record Route(String variant, long inAmount, long quotedOutAmount, int slippageBps, int platformFeeBps, long minimumReceived) {}

    public record Result(List<Violation> violations, Route route, long priorityFeeLamports) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private SwapRules() {}

    public static Result check(CompiledTransaction tx, SwapIntent intent) {
        Checker c = new Checker(tx, intent);
        c.run();
        return new Result(List.copyOf(c.violations), c.route, c.priorityFee());
    }

    private static final class Checker {
        private final CompiledTransaction tx;
        private final SwapIntent intent;
        private final String sourceAccount;
        private final String destinationAccount;
        private final String wsolAccount;
        private final List<Violation> violations = new ArrayList<>();
        private Route route;
        private int jupiterInstructions;
        private long unitLimit = -1;
        private long microLamportsPerUnit;
        private long wrapped;

        Checker(CompiledTransaction tx, SwapIntent intent) {
            this.tx = tx;
            this.intent = intent;
            this.sourceAccount = TokenAccounts.associated(intent.owner(), intent.inputMint());
            this.destinationAccount = TokenAccounts.associated(intent.owner(), intent.outputMint());
            this.wsolAccount = TokenAccounts.associated(intent.owner(), TokenAccounts.WSOL_MINT);
        }

        void run() {
            if (tx.requiredSignatures() != 1) {
                fail("unexpected_signers", tx.requiredSignatures() + " required signatures");
            }
            if (!tx.feePayer().equals(intent.owner())) {
                fail("fee_payer_mismatch", tx.feePayer());
            }
            if (!tx.signaturesEmpty()) {
                fail("already_signed", "a signature slot is not empty");
            }
            List<CompiledTransaction.Instruction> ixs = tx.instructions();
            for (int i = 0; i < ixs.size(); i++) {
                CompiledTransaction.Instruction ix = ixs.get(i);
                String program = tx.staticKeys().get(ix.programIndex());
                switch (program) {
                    case TokenAccounts.COMPUTE_BUDGET_PROGRAM -> computeBudget(i, ix);
                    case TokenAccounts.ATA_PROGRAM -> createAta(i, ix);
                    case TokenAccounts.SYSTEM_PROGRAM -> systemTransfer(i, ix);
                    case TokenAccounts.TOKEN_PROGRAM -> token(i, ix);
                    case JUPITER_V6 -> jupiter(i, ix);
                    default -> fail("program_not_allowed", "instruction " + i + " calls " + program);
                }
            }
            if (jupiterInstructions != 1) {
                fail("jupiter_instruction_count", jupiterInstructions + " Jupiter instructions, expected exactly 1");
            }
            if (wrapped > intent.inAmount()) {
                fail("wrap_exceeds_amount", wrapped + " lamports wrapped for a swap of " + intent.inAmount());
            }
            if (priorityFee() > intent.maxPriorityFeeLamports()) {
                fail("priority_fee_too_high", priorityFee() + " lamports > " + intent.maxPriorityFeeLamports());
            }
        }

        long priorityFee() {
            long units = unitLimit < 0 ? 1_400_000L : unitLimit; // no limit set: assume the per-transaction maximum
            try {
                return Math.addExact(Math.multiplyExact(units, microLamportsPerUnit), 999_999) / 1_000_000;
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        private void computeBudget(int i, CompiledTransaction.Instruction ix) {
            byte[] d = ix.data();
            if (d.length == 5 && d[0] == 2) {
                unitLimit = Integer.toUnsignedLong(le(d, 1, 4).getInt());
            } else if (d.length == 9 && d[0] == 3) {
                microLamportsPerUnit = le(d, 1, 8).getLong();
                if (microLamportsPerUnit < 0) {
                    microLamportsPerUnit = Long.MAX_VALUE;
                }
            } else {
                fail("compute_budget_not_allowed", "instruction " + i + " (only unit limit and unit price)");
            }
        }

        private void createAta(int i, CompiledTransaction.Instruction ix) {
            byte[] d = ix.data();
            int[] a = ix.accounts();
            if (!(d.length == 0 || (d.length == 1 && (d[0] == 0 || d[0] == 1))) || a.length != 6) {
                fail("ata_instruction_not_allowed", "instruction " + i);
                return;
            }
            expect(i, "ata_payer", a[0], intent.owner());
            expect(i, "ata_wallet", a[2], intent.owner());
            expect(i, "ata_token_program", a[5], TokenAccounts.TOKEN_PROGRAM);
        }

        private void systemTransfer(int i, CompiledTransaction.Instruction ix) {
            byte[] d = ix.data();
            int[] a = ix.accounts();
            if (d.length != 12 || le(d, 0, 4).getInt() != 2 || a.length != 2) {
                fail("system_instruction_not_allowed", "instruction " + i + " (only a transfer that wraps SOL)");
                return;
            }
            long lamports = le(d, 4, 8).getLong();
            expect(i, "transfer_from", a[0], intent.owner());
            if (!TokenAccounts.WSOL_MINT.equals(intent.inputMint())) {
                fail("system_transfer_not_allowed", "instruction " + i + " moves SOL but the swap does not spend SOL");
            }
            expect(i, "transfer_to", a[1], wsolAccount);
            wrapped = lamports < 0 || wrapped + lamports < 0 ? Long.MAX_VALUE : wrapped + lamports;
        }

        private void token(int i, CompiledTransaction.Instruction ix) {
            byte[] d = ix.data();
            int[] a = ix.accounts();
            if (d.length == 1 && d[0] == 17 && a.length == 1) { // SyncNative
                expect(i, "sync_native_account", a[0], wsolAccount);
            } else if (d.length == 1 && d[0] == 9 && a.length == 3) { // CloseAccount
                expect(i, "close_account", a[0], wsolAccount);
                expect(i, "close_destination", a[1], intent.owner());
                expect(i, "close_authority", a[2], intent.owner());
            } else {
                fail("token_instruction_not_allowed", "instruction " + i + " (only sync-native and closing the owner's wrapped SOL)");
            }
        }

        private void jupiter(int i, CompiledTransaction.Instruction ix) {
            jupiterInstructions++;
            byte[] d = ix.data();
            int[] a = ix.accounts();
            byte[] disc = d.length >= 8 ? Arrays.copyOf(d, 8) : new byte[0];
            boolean plain = Arrays.equals(disc, ROUTE);
            boolean shared = Arrays.equals(disc, SHARED_ACCOUNTS_ROUTE);
            if (!(plain || shared) || d.length < 8 + 19 || a.length < (plain ? 9 : 13)) {
                fail("jupiter_instruction_not_allowed", "instruction " + i + " is not route / shared_accounts_route");
                return;
            }
            ByteBuffer tail = le(d, d.length - 19, 19);
            long in = tail.getLong();
            long quoted = tail.getLong();
            int slippage = Short.toUnsignedInt(tail.getShort());
            int fee = Byte.toUnsignedInt(tail.get());
            long min = in < 0 || quoted < 0 || slippage > 10_000 ? -1 : multiplyFloor(quoted, 10_000 - slippage);
            route = new Route(plain ? "route" : "shared_accounts_route", in, quoted, slippage, fee, min);

            if (plain) {
                expect(i, "route_token_program", a[0], TokenAccounts.TOKEN_PROGRAM);
                expect(i, "route_authority", a[1], intent.owner());
                expect(i, "route_source", a[2], sourceAccount);
                expect(i, "route_destination", a[3], destinationAccount);
                expectOneOf(i, "route_destination_override", a[4], JUPITER_V6, destinationAccount);
                expect(i, "route_platform_fee_account", a[6], JUPITER_V6);
            } else {
                expect(i, "route_token_program", a[0], TokenAccounts.TOKEN_PROGRAM);
                expect(i, "route_authority", a[2], intent.owner());
                expect(i, "route_source", a[3], sourceAccount);
                expect(i, "route_destination", a[6], destinationAccount);
                expect(i, "route_platform_fee_account", a[9], JUPITER_V6);
            }
            if (in != intent.inAmount()) {
                fail("in_amount_mismatch", in + " != " + intent.inAmount());
            }
            if (fee != 0) {
                fail("platform_fee", fee + " bps");
            }
            if (slippage > intent.maxSlippageBps()) {
                fail("slippage_too_high", slippage + " bps > " + intent.maxSlippageBps());
            }
            if (min < intent.minOutAmount()) {
                fail("minimum_received_too_low", min + " < " + intent.minOutAmount());
            }
        }

        private void expect(int i, String role, int index, String wanted) {
            Optional<String> key = tx.staticKey(index);
            if (key.isEmpty()) {
                fail("account_from_lookup_table", "instruction " + i + " " + role + " cannot be resolved offline");
            } else if (!key.get().equals(wanted)) {
                fail(role + "_mismatch", "instruction " + i + " " + role + "=" + key.get() + ", expected " + wanted);
            }
        }

        private void expectOneOf(int i, String role, int index, String a, String b) {
            Optional<String> key = tx.staticKey(index);
            if (key.isEmpty()) {
                fail("account_from_lookup_table", "instruction " + i + " " + role + " cannot be resolved offline");
            } else if (!key.get().equals(a) && !key.get().equals(b)) {
                fail(role + "_mismatch", "instruction " + i + " " + role + "=" + key.get());
            }
        }

        private void fail(String code, String detail) {
            violations.add(new Violation(code, detail));
        }
    }

    /** floor(quoted × keepBps / 10000) without overflow. */
    private static long multiplyFloor(long quoted, int keepBps) {
        return java.math.BigInteger.valueOf(quoted).multiply(java.math.BigInteger.valueOf(keepBps))
                .divide(java.math.BigInteger.valueOf(10_000)).longValueExact();
    }

    private static ByteBuffer le(byte[] d, int from, int len) {
        return ByteBuffer.wrap(d, from, len).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Anchor's instruction discriminator: the first 8 bytes of sha256("global:" + name). */
    static byte[] discriminator(String name) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(("global:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
