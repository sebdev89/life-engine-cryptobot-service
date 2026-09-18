package io.lifeengine.cryptobot.domain.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tree of KAN-394 against vectors written by python {@code hashlib}, plus the properties a verifier relies on. */
class MerkleTreeTest {

    static JsonNode vectors() throws Exception {
        try (InputStream in = MerkleTreeTest.class.getResourceAsStream("/receipt/merkle-vectors-v1.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("golden vectors: leaves, root and every proof match hashlib for 1, 2, 3 and 5 receipts")
    void goldenVectors() throws Exception {
        JsonNode v = vectors();
        assertThat(v.path("vectors")).hasSize(4);
        for (JsonNode vec : v.path("vectors")) {
            List<String> receipts = new ArrayList<>();
            vec.path("receipts").forEach(n -> receipts.add(n.asText()));
            MerkleTree tree = MerkleTree.of(receipts);
            List<String> leaves = new ArrayList<>();
            vec.path("leaves").forEach(n -> leaves.add(n.asText()));
            assertThat(tree.leaves()).as(vec.path("name").asText()).isEqualTo(leaves);
            assertThat(tree.root()).as(vec.path("name").asText()).isEqualTo(vec.path("root").asText());
            Map<String, Object> proofs = new ObjectMapper().convertValue(vec.path("proofs"), Map.class);
            for (Map.Entry<String, Object> e : proofs.entrySet()) {
                @SuppressWarnings("unchecked")
                List<String> expected = (List<String>) e.getValue();
                assertThat(tree.proofFor(e.getKey())).as(vec.path("name").asText() + " proof of " + e.getKey()).isEqualTo(expected);
                assertThat(MerkleTree.verify(e.getKey(), expected, tree.root())).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the root is a function of the set: order and duplicates do not change it; a batch of one has root = leaf")
    void rootIsAFunctionOfTheSet() {
        List<String> a = List.of(Digests.sha256("x"), Digests.sha256("y"), Digests.sha256("z"));
        List<String> shuffled = new ArrayList<>(a);
        Collections.reverse(shuffled);
        assertThat(MerkleTree.of(shuffled).root()).isEqualTo(MerkleTree.of(a).root());
        assertThat(MerkleTree.of(List.of(a.get(0), a.get(0), a.get(1), a.get(2))).root()).isEqualTo(MerkleTree.of(a).root());
        assertThat(MerkleTree.of(List.of(a.get(0))).root()).isEqualTo(MerkleTree.leafHash(a.get(0)));
        assertThat(MerkleTree.of(List.of(a.get(0))).proofFor(a.get(0))).isEmpty();
        // Domain separation: a leaf is not the plain hash of the receipt hash, and a node is not a leaf.
        assertThat(MerkleTree.leafHash(a.get(0))).isNotEqualTo(a.get(0)).isNotEqualTo(Digests.sha256(Digests.bytes(a.get(0))));
        assertThat(MerkleTree.nodeHash(a.get(0), a.get(1))).isNotEqualTo(MerkleTree.nodeHash(a.get(1), a.get(0)));
    }

    @Test
    @DisplayName("every leaf of trees of 1..17 receipts proves inclusion; a tampered leaf, proof or root does not")
    void proofsForEverySize() {
        for (int n = 1; n <= 17; n++) {
            List<String> hashes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                hashes.add(Digests.sha256("r" + n + "-" + i));
            }
            MerkleTree tree = MerkleTree.of(hashes);
            for (String h : hashes) {
                List<String> proof = tree.proofFor(h);
                assertThat(MerkleTree.verify(h, proof, tree.root())).as("n=" + n).isTrue();
                assertThat(MerkleTree.verify(Digests.sha256("other"), proof, tree.root())).as("foreign leaf n=" + n).isFalse();
                assertThat(MerkleTree.verify(h, proof, Digests.sha256("other root"))).as("wrong root n=" + n).isFalse();
                if (!proof.isEmpty()) {
                    List<String> flipped = new ArrayList<>(proof);
                    flipped.set(0, (proof.get(0).startsWith("L:") ? "R:" : "L:") + proof.get(0).substring(2));
                    assertThat(MerkleTree.verify(h, flipped, tree.root())).as("flipped side n=" + n).isFalse();
                }
            }
            assertThat(tree.size()).isEqualTo(n);
        }
    }

    @Test
    @DisplayName("refuses an empty batch, a non-hash leaf, a foreign leaf in proofFor and a malformed proof step")
    void refusals() {
        assertThatThrownBy(() -> MerkleTree.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.of(List.of("not-a-hash"))).isInstanceOf(IllegalArgumentException.class);
        MerkleTree tree = MerkleTree.of(List.of(Digests.sha256("a"), Digests.sha256("b")));
        assertThatThrownBy(() -> tree.proofFor(Digests.sha256("c"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(MerkleTree.verify(Digests.sha256("a"), List.of("X:" + Digests.sha256("b")), tree.root())).isFalse();
        assertThat(MerkleTree.verify(Digests.sha256("a"), List.of("R:garbage"), tree.root())).isFalse();
    }
}
