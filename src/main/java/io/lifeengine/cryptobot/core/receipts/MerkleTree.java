package io.lifeengine.cryptobot.core.receipts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The Merkle tree of one anchoring batch (Endgame §11): the root goes into the memo transaction,
 * each receipt keeps the siblings it needs to reach that root, and a verifier proves
 * {@code receipt ∈ root ∈ tx} with nothing but hashes.
 *
 * <p>Fixed, so a verifier outside this codebase can reimplement it in twenty lines:
 *
 * <pre>
 *   leaves   = the receipt hashes of the batch, de-duplicated, sorted lexicographically
 *   leaf(h)  = SHA-256(0x00 ‖ bytes32(h))                      (domain-separated from nodes)
 *   node     = SHA-256(0x01 ‖ bytes32(left) ‖ bytes32(right))
 *   level+1  = pairs (2i, 2i+1) of the level; an odd last node is promoted unchanged
 *   root     = the single node of the top level; a batch of one has root = leaf(h)
 *   proof(h) = the sibling at every level, tagged "L:" (sibling is the left input) or "R:"
 * </pre>
 *
 * Sorting makes the root a function of the <em>set</em> of receipts, not of the order the batch
 * was read in, so "recompute the root from the receipts of the batch" (the test of an internal ticket) needs
 * no stored order. The 0x00/0x01 prefixes make a leaf unforgeable as an internal node (the
 * second-preimage weakness of naive trees). Promotion instead of duplication of the odd node
 * avoids the other classic ambiguity (a tree of {@code n} leaves equal to one of {@code n+1}).
 */
public final class MerkleTree {

    private static final byte LEAF_PREFIX = 0x00;
    private static final byte NODE_PREFIX = 0x01;
    public static final String LEFT = "L:";
    public static final String RIGHT = "R:";

    private final List<String> leaves;
    private final List<List<String>> levels;

    private MerkleTree(List<String> leaves, List<List<String>> levels) {
        this.leaves = leaves;
        this.levels = levels;
    }

    public static MerkleTree of(Collection<String> receiptHashes) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String h : receiptHashes) {
            sorted.add(Digests.requireHash("receiptHash", h));
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("A Merkle tree needs at least one receipt");
        }
        List<String> leaves = List.copyOf(sorted);
        List<List<String>> levels = new ArrayList<>();
        List<String> level = new ArrayList<>();
        for (String h : leaves) {
            level.add(leafHash(h));
        }
        levels.add(level);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                next.add(i + 1 < level.size() ? nodeHash(level.get(i), level.get(i + 1)) : level.get(i));
            }
            levels.add(next);
            level = next;
        }
        return new MerkleTree(leaves, levels);
    }

    /** The receipt hashes of the batch, in leaf order (sorted). */
    public List<String> leaves() {
        return leaves;
    }

    public int size() {
        return leaves.size();
    }

    public String root() {
        return levels.get(levels.size() - 1).get(0);
    }

    /** Siblings from the leaf up, each tagged with the side it sits on. Empty for a batch of one. */
    public List<String> proofFor(String receiptHash) {
        int index = leaves.indexOf(Digests.requireHash("receiptHash", receiptHash));
        if (index < 0) {
            throw new IllegalArgumentException("Receipt is not in this batch: " + receiptHash);
        }
        List<String> proof = new ArrayList<>();
        for (int l = 0; l < levels.size() - 1; l++) {
            List<String> level = levels.get(l);
            int sibling = index % 2 == 0 ? index + 1 : index - 1;
            if (sibling < level.size()) {
                proof.add((index % 2 == 0 ? RIGHT : LEFT) + level.get(sibling));
            }
            index /= 2;
        }
        return List.copyOf(proof);
    }

    public static String leafHash(String receiptHash) {
        byte[] h = Digests.bytes(receiptHash);
        byte[] m = new byte[1 + h.length];
        m[0] = LEAF_PREFIX;
        System.arraycopy(h, 0, m, 1, h.length);
        return Digests.render(Digests.digest(m));
    }

    public static String nodeHash(String left, String right) {
        byte[] l = Digests.bytes(left);
        byte[] r = Digests.bytes(right);
        byte[] m = new byte[1 + l.length + r.length];
        m[0] = NODE_PREFIX;
        System.arraycopy(l, 0, m, 1, l.length);
        System.arraycopy(r, 0, m, 1 + l.length, r.length);
        return Digests.render(Digests.digest(m));
    }

    /** Folds the proof over the leaf: what a verifier compares with the root in the memo. */
    public static String rootFromProof(String receiptHash, List<String> proof) {
        String current = leafHash(receiptHash);
        for (String step : proof) {
            if (step == null || step.length() < 3) {
                throw new IllegalArgumentException("Malformed proof step: " + step);
            }
            String side = step.substring(0, 2).toUpperCase(Locale.ROOT);
            String sibling = Digests.requireHash("sibling", step.substring(2));
            current = switch (side) {
                case LEFT -> nodeHash(sibling, current);
                case RIGHT -> nodeHash(current, sibling);
                default -> throw new IllegalArgumentException("Malformed proof step: " + step);
            };
        }
        return current;
    }

    public static boolean verify(String receiptHash, List<String> proof, String root) {
        try {
            return rootFromProof(receiptHash, proof).equals(Digests.requireHash("root", root));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
