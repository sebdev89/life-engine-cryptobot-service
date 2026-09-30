//! Instruction set. Borsh enum: the first byte is the variant index (0, 1, 2), then the fields
//! in order, little-endian. The Java client (`adapters/solana/authority/IntentAuthorityProgram`)
//! encodes exactly this; `vectors-v1.json` pins the bytes on both sides.

use borsh::{BorshDeserialize, BorshSerialize};
use solana_program::pubkey::Pubkey;

#[derive(BorshSerialize, BorshDeserialize, Debug, Clone, PartialEq, Eq)]
pub enum AuthorityInstruction {
    /// Commit `H_R` for `(agent, policy_version)`. Fails if the PDA already exists.
    ///
    /// Accounts:
    /// 0. `[signer, writable]` authority — pays rent, is the only key that can revoke
    /// 1. `[writable]` policy PDA `["policy", agent, policy_version_le]`
    /// 2. `[]` system program
    RegisterPolicy {
        agent: Pubkey,
        policy_version: u32,
        policy_hash: [u8; 32],
    },

    /// Mark the policy inactive. Irreversible: register a new version instead.
    ///
    /// Accounts:
    /// 0. `[signer]` authority — must equal `Policy.authority`
    /// 1. `[writable]` policy PDA
    RevokePolicy,

    /// Validate I1–I4 and, only if all hold, create the nonce and receipt accounts.
    //The "execution" of this level is the receipt itself (no transfer, no swap — devnet).
    ///
    /// Accounts:
    /// 0. `[signer]` agent — the Ed25519 key of `agent_id`; its signature over the transaction
    ///    is the agent's authorization of this exact `intent_hash` (I1)
    /// 1. `[signer, writable]` payer — pays rent of the two new accounts (may be the agent)
    /// 2. `[]` policy PDA `["policy", agent, policy_version_le]` (I1, I4)
    /// 3. `[writable]` nonce PDA `["nonce", agent, nonce_le]` — must not exist (I3)
    /// 4. `[writable]` receipt PDA `["receipt", intent_hash]` — must not exist (I3)
    /// 5. `[]` system program
    Execute {
        intent_hash: [u8; 32],
        policy_version: u32,
        policy_hash: [u8; 32],
        valid_until_slot: u64,
        nonce: u64,
    },
}
