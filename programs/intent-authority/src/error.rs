//! Every refusal of the program is one of these, in the order the checks run. The numbers are
//! stable (they are the `Custom(n)` code the client sees) — append, never renumber.

use solana_program::program_error::ProgramError;
use thiserror::Error;

#[derive(Debug, Error, Clone, Copy, PartialEq, Eq)]
pub enum AuthorityError {
    /// I1 — the agent account did not sign the transaction.
    #[error("agent must sign the execution")]
    AgentSignatureMissing = 0,
    /// I1 — the policy account is not the PDA of (agent, policy_version) under this program.
    #[error("policy account is not the PDA of (agent, policy_version)")]
    PolicyAccountMismatch = 1,
    /// I1 — the policy PDA exists but is not owned by this program or is not initialized.
    #[error("policy account is not initialized")]
    PolicyNotRegistered = 2,
    /// I1 — the policy was revoked by its authority.
    #[error("policy is revoked")]
    PolicyRevoked = 3,
    /// I4 — the intent claims a policy hash that is not the one committed on-chain.
    #[error("policy hash does not match the committed H_R")]
    PolicyHashMismatch = 4,
    /// I2 — the current slot is past `valid_until_slot`.
    #[error("intent expired: current slot is past valid_until_slot")]
    IntentExpired = 5,
    /// I3 — the nonce PDA of (agent, nonce) already exists: replay.
    #[error("nonce already used by this agent")]
    NonceAlreadyUsed = 6,
    /// I3 — the receipt PDA of intent_hash already exists: this exact intent already executed.
    #[error("intent already executed (receipt exists)")]
    IntentAlreadyExecuted = 7,
    /// A PDA passed by the client is not the address this program derives.
    #[error("account is not the expected program-derived address")]
    InvalidPda = 8,
    /// Only the registering authority may revoke a policy.
    #[error("only the registering authority may revoke")]
    NotPolicyAuthority = 9,
    /// A policy for (agent, policy_version) already exists: policies are immutable, bump the version.
    #[error("policy already registered for this agent and version")]
    PolicyAlreadyRegistered = 10,
    /// The intent hash is all zeros — never a real SHA-256 of a canonical intent.
    #[error("intent hash must not be zero")]
    ZeroIntentHash = 11,
    /// The account data does not deserialize into the expected state (wrong discriminator/length).
    #[error("account data is not the expected state")]
    InvalidAccountData = 12,
}

impl From<AuthorityError> for ProgramError {
    fn from(e: AuthorityError) -> Self {
        ProgramError::Custom(e as u32)
    }
}
