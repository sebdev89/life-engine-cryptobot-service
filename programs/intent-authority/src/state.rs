//! On-chain state: three account kinds, each a PDA whose seeds ARE its identity.
//!
//! * `Policy`  — `["policy", agent, policy_version_le_u32]` — the on-chain commitment of `H_R`
//!   (paper §11): immutable once registered, revocable by its authority. A new policy is a new
//!   version, hence a new address.
//! * `Nonce`   — `["nonce", agent, nonce_le_u64]` — exists ⇔ the nonce was consumed. Anti-replay
//!   is account creation: the runtime refuses to create an account twice (paper §13, replay).
//! * `Receipt` — `["receipt", intent_hash]` — exists ⇔ this exact intent executed (paper §12).
//!   One execution per `H_I`, whatever the nonce.
//!
//! Layout is borsh with a one-byte discriminator in front; sizes are fixed so rent is known
//! before the transaction is built.

use borsh::{BorshDeserialize, BorshSerialize};
use solana_program::pubkey::Pubkey;

pub const POLICY_SEED: &[u8] = b"policy";
pub const NONCE_SEED: &[u8] = b"nonce";
pub const RECEIPT_SEED: &[u8] = b"receipt";

pub const POLICY_DISCRIMINATOR: u8 = 1;
pub const NONCE_DISCRIMINATOR: u8 = 2;
pub const RECEIPT_DISCRIMINATOR: u8 = 3;

/// `H_R` committed for one `(agent, policy_version)`.
#[derive(BorshSerialize, BorshDeserialize, Debug, Clone, PartialEq, Eq)]
pub struct Policy {
    pub discriminator: u8,
    /// Who registered it — the only key that can revoke it. Never the agent.
    pub authority: Pubkey,
    /// The Ed25519 key that must sign every execution under this policy (the `agent_id`).
    pub agent: Pubkey,
    pub policy_version: u32,
    //`SHA-256(canonical R_v)` — the `H_R` of an internal ticket, raw 32 bytes.
    pub policy_hash: [u8; 32],
    pub active: bool,
    pub registered_slot: u64,
    pub bump: u8,
}

impl Policy {
    /// 1 + 32 + 32 + 4 + 32 + 1 + 8 + 1
    pub const LEN: usize = 111;

    pub fn seeds(agent: &Pubkey, policy_version: u32) -> [Vec<u8>; 3] {
        [
            POLICY_SEED.to_vec(),
            agent.to_bytes().to_vec(),
            policy_version.to_le_bytes().to_vec(),
        ]
    }

    pub fn find_address(program_id: &Pubkey, agent: &Pubkey, policy_version: u32) -> (Pubkey, u8) {
        Pubkey::find_program_address(
            &[POLICY_SEED, agent.as_ref(), &policy_version.to_le_bytes()],
            program_id,
        )
    }
}

/// Marker: `(agent, nonce)` consumed. Carries the intent that consumed it for audit.
#[derive(BorshSerialize, BorshDeserialize, Debug, Clone, PartialEq, Eq)]
pub struct Nonce {
    pub discriminator: u8,
    pub agent: Pubkey,
    pub nonce: u64,
    pub intent_hash: [u8; 32],
    pub used_slot: u64,
    pub bump: u8,
}

impl Nonce {
    /// 1 + 32 + 8 + 32 + 8 + 1
    pub const LEN: usize = 82;

    pub fn find_address(program_id: &Pubkey, agent: &Pubkey, nonce: u64) -> (Pubkey, u8) {
        Pubkey::find_program_address(
            &[NONCE_SEED, agent.as_ref(), &nonce.to_le_bytes()],
            program_id,
        )
    }
}

/// The execution receipt of paper §12: this exact intent, under this exact policy, at this slot.
#[derive(BorshSerialize, BorshDeserialize, Debug, Clone, PartialEq, Eq)]
pub struct Receipt {
    pub discriminator: u8,
    //`H_I` — raw 32 bytes of `sha256:<hex>`.
    pub intent_hash: [u8; 32],
    pub agent: Pubkey,
    pub policy_version: u32,
    /// `H_R` as verified against the policy PDA at execution time.
    pub policy_hash: [u8; 32],
    pub nonce: u64,
    pub valid_until_slot: u64,
    pub executed_slot: u64,
    pub bump: u8,
}

impl Receipt {
    /// 1 + 32 + 32 + 4 + 32 + 8 + 8 + 8 + 1
    pub const LEN: usize = 126;

    pub fn find_address(program_id: &Pubkey, intent_hash: &[u8; 32]) -> (Pubkey, u8) {
        Pubkey::find_program_address(&[RECEIPT_SEED, intent_hash], program_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_sizes_match_borsh() {
        let p = Policy {
            discriminator: POLICY_DISCRIMINATOR,
            authority: Pubkey::new_unique(),
            agent: Pubkey::new_unique(),
            policy_version: 1,
            policy_hash: [7; 32],
            active: true,
            registered_slot: 9,
            bump: 254,
        };
        assert_eq!(borsh::to_vec(&p).unwrap().len(), Policy::LEN);
        let n = Nonce {
            discriminator: NONCE_DISCRIMINATOR,
            agent: Pubkey::new_unique(),
            nonce: 5,
            intent_hash: [1; 32],
            used_slot: 3,
            bump: 250,
        };
        assert_eq!(borsh::to_vec(&n).unwrap().len(), Nonce::LEN);
        let r = Receipt {
            discriminator: RECEIPT_DISCRIMINATOR,
            intent_hash: [1; 32],
            agent: Pubkey::new_unique(),
            policy_version: 1,
            policy_hash: [7; 32],
            nonce: 5,
            valid_until_slot: 100,
            executed_slot: 90,
            bump: 251,
        };
        assert_eq!(borsh::to_vec(&r).unwrap().len(), Receipt::LEN);
    }
}
