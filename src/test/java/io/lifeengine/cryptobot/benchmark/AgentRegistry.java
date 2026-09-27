package io.lifeengine.cryptobot.benchmark;

import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may speak to the authority layer (paper §13 "compromised agent", §21 "key security"): the
 * agents the operator registered, each with its Ed25519 key, and which of them are currently
 * permitted. An agent that is not here has no key on file, so nothing it signs verifies; an agent
 * that is here but revoked verifies and is still refused ({@code AGENT_PERMITTED}).
 *
 * <p>Keys come from {@link SolanaKeypair#generate()} (SecureRandom): they are not part of the
 * reproducibility claim — the verdict hash covers the agent <em>id</em>, not its key — and the
 * benchmark shares one registry between its two runs so the same signatures are valid in both.
 */
public final class AgentRegistry {

    private final Map<String, SolanaKeypair> keys = new LinkedHashMap<>();
    private final Set<String> permitted = new LinkedHashSet<>();

    public static AgentRegistry of(List<String> permittedAgents, List<String> revokedAgents) {
        AgentRegistry r = new AgentRegistry();
        for (String a : permittedAgents) {
            r.keys.put(a, SolanaKeypair.generate());
            r.permitted.add(a);
        }
        for (String a : revokedAgents) {
            r.keys.put(a, SolanaKeypair.generate());
        }
        return r;
    }

    public List<String> permittedAgents() {
        return List.copyOf(permitted);
    }

    public List<String> revokedAgents() {
        return keys.keySet().stream().filter(a -> !permitted.contains(a)).toList();
    }

    /** The key on file for this agent, or {@code null} when nobody registered it. */
    public SolanaKeypair keyOf(String agentId) {
        return keys.get(agentId);
    }

    public boolean isPermitted(String agentId) {
        return permitted.contains(agentId);
    }

    public void revoke(String agentId) {
        permitted.remove(agentId);
    }

    public void permit(String agentId) {
        if (keys.containsKey(agentId)) {
            permitted.add(agentId);
        }
    }

    /** Signs the canonical bytes of an intent with the agent's own key; the attacker's key when {@code agentId} is not the signer. */
    public byte[] sign(String signerAgentId, byte[] canonicalBytes) {
        SolanaKeypair k = keys.get(signerAgentId);
        if (k == null) {
            throw new IllegalArgumentException("no key for " + signerAgentId);
        }
        return k.sign(canonicalBytes);
    }
}
