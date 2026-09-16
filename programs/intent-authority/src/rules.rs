//! The authorization rules as a pure function over facts — no accounts, no syscalls — so the
//! same table is unit-tested here and its refusals are named, code by code, by the Java client
//! (`IntentAuthorityProgram.AuthorityError`). The processor's only job is to collect the facts
//! honestly and to refuse when it cannot (fail-closed, §17).
//!
//! Order matters and is part of the contract: the first failing check names the error.

use crate::error::AuthorityError;
use crate::state::Policy;
use solana_program::pubkey::Pubkey;

/// Everything the `Execute` decision depends on. Every field is a fact the processor established
/// from a signed transaction, a program-owned account or the `Clock` sysvar — never from the
/// instruction data alone (except the claims the intent makes, which are what gets checked).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecuteFacts<'a> {
    /// Account 0 of the instruction — who claims to be the agent.
    pub agent: &'a Pubkey,
    pub agent_is_signer: bool,
    /// Account 2 as passed by the client, and what this program derives for (agent, version).
    pub policy_account: &'a Pubkey,
    pub expected_policy_pda: &'a Pubkey,
    /// `None` when the account is not owned by the program or does not deserialize.
    pub policy: Option<&'a Policy>,
    /// Claims made by the intent.
    pub intent_hash: &'a [u8; 32],
    pub claimed_policy_hash: &'a [u8; 32],
    pub valid_until_slot: u64,
    /// `Clock::get().slot`.
    pub current_slot: u64,
    /// Whether the nonce / receipt PDAs already have lamports or data.
    pub nonce_exists: bool,
    pub receipt_exists: bool,
}

/// I1 (authorized agent) → I4 (policy binding) → I2 (not expired) → I3 (single execution).
pub fn check_execute(f: &ExecuteFacts<'_>) -> Result<(), AuthorityError> {
    if f.intent_hash.iter().all(|b| *b == 0) {
        return Err(AuthorityError::ZeroIntentHash);
    }
    // I1 — authorization: the agent signed, and the policy is *this* agent's.
    if !f.agent_is_signer {
        return Err(AuthorityError::AgentSignatureMissing);
    }
    if f.policy_account != f.expected_policy_pda {
        return Err(AuthorityError::PolicyAccountMismatch);
    }
    let policy = f.policy.ok_or(AuthorityError::PolicyNotRegistered)?;
    if &policy.agent != f.agent {
        // Cannot happen when the PDA matched (agent is a seed) — kept as belt and braces.
        return Err(AuthorityError::PolicyAccountMismatch);
    }
    if !policy.active {
        return Err(AuthorityError::PolicyRevoked);
    }
    // I4 — policy binding: the H_R the intent was authorized under is the committed one.
    if &policy.policy_hash != f.claimed_policy_hash {
        return Err(AuthorityError::PolicyHashMismatch);
    }
    // I2 — expiry by slot: an intent authorized off-chain for a window cannot land after it.
    if f.current_slot > f.valid_until_slot {
        return Err(AuthorityError::IntentExpired);
    }
    // I3 — one execution per intent, one use per nonce.
    if f.receipt_exists {
        return Err(AuthorityError::IntentAlreadyExecuted);
    }
    if f.nonce_exists {
        return Err(AuthorityError::NonceAlreadyUsed);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::POLICY_DISCRIMINATOR;

    fn policy(agent: Pubkey, hash: [u8; 32], active: bool) -> Policy {
        Policy {
            discriminator: POLICY_DISCRIMINATOR,
            authority: Pubkey::new_unique(),
            agent,
            policy_version: 1,
            policy_hash: hash,
            active,
            registered_slot: 1,
            bump: 255,
        }
    }

    struct Fixture {
        agent: Pubkey,
        pda: Pubkey,
        policy: Policy,
        intent: [u8; 32],
        hash: [u8; 32],
    }

    fn fixture() -> Fixture {
        let agent = Pubkey::new_unique();
        let hash = [0xAB; 32];
        Fixture {
            agent,
            pda: Pubkey::new_unique(),
            policy: policy(agent, hash, true),
            intent: [0x11; 32],
            hash,
        }
    }

    fn facts(fx: &Fixture) -> ExecuteFacts<'_> {
        ExecuteFacts {
            agent: &fx.agent,
            agent_is_signer: true,
            policy_account: &fx.pda,
            expected_policy_pda: &fx.pda,
            policy: Some(&fx.policy),
            intent_hash: &fx.intent,
            claimed_policy_hash: &fx.hash,
            valid_until_slot: 1_000,
            current_slot: 900,
            nonce_exists: false,
            receipt_exists: false,
        }
    }

    #[test]
    fn all_facts_hold_authorizes() {
        let fx = fixture();
        assert_eq!(check_execute(&facts(&fx)), Ok(()));
    }

    #[test]
    fn i1_agent_must_sign() {
        let fx = fixture();
        let mut f = facts(&fx);
        f.agent_is_signer = false;
        assert_eq!(
            check_execute(&f),
            Err(AuthorityError::AgentSignatureMissing)
        );
    }

    #[test]
    fn i1_policy_account_must_be_the_agents_pda() {
        let fx = fixture();
        let other = Pubkey::new_unique();
        let mut f = facts(&fx);
        f.policy_account = &other;
        assert_eq!(
            check_execute(&f),
            Err(AuthorityError::PolicyAccountMismatch)
        );
    }

    #[test]
    fn i1_unregistered_policy_is_refused() {
        let fx = fixture();
        let mut f = facts(&fx);
        f.policy = None;
        assert_eq!(check_execute(&f), Err(AuthorityError::PolicyNotRegistered));
    }

    #[test]
    fn i1_policy_of_another_agent_is_refused() {
        let mut fx = fixture();
        fx.policy.agent = Pubkey::new_unique();
        assert_eq!(
            check_execute(&facts(&fx)),
            Err(AuthorityError::PolicyAccountMismatch)
        );
    }

    #[test]
    fn i1_revoked_policy_is_refused() {
        let mut fx = fixture();
        fx.policy.active = false;
        assert_eq!(
            check_execute(&facts(&fx)),
            Err(AuthorityError::PolicyRevoked)
        );
    }

    #[test]
    fn i4_policy_hash_must_match_commitment() {
        let fx = fixture();
        let wrong = [0xAC; 32];
        let mut f = facts(&fx);
        f.claimed_policy_hash = &wrong;
        assert_eq!(check_execute(&f), Err(AuthorityError::PolicyHashMismatch));
    }

    #[test]
    fn i2_expired_at_slot_after_valid_until() {
        let fx = fixture();
        let mut f = facts(&fx);
        f.valid_until_slot = 899;
        assert_eq!(check_execute(&f), Err(AuthorityError::IntentExpired));
        f.valid_until_slot = 900; // inclusive: the last valid slot still executes
        assert_eq!(check_execute(&f), Ok(()));
    }

    #[test]
    fn i3_receipt_exists_means_already_executed() {
        let fx = fixture();
        let mut f = facts(&fx);
        f.receipt_exists = true;
        assert_eq!(
            check_execute(&f),
            Err(AuthorityError::IntentAlreadyExecuted)
        );
    }

    #[test]
    fn i3_nonce_exists_means_replay() {
        let fx = fixture();
        let mut f = facts(&fx);
        f.nonce_exists = true;
        assert_eq!(check_execute(&f), Err(AuthorityError::NonceAlreadyUsed));
    }

    #[test]
    fn zero_intent_hash_is_never_valid() {
        let fx = fixture();
        let zero = [0u8; 32];
        let mut f = facts(&fx);
        f.intent_hash = &zero;
        assert_eq!(check_execute(&f), Err(AuthorityError::ZeroIntentHash));
    }

    #[test]
    fn first_failing_check_names_the_error_in_i1_i4_i2_i3_order() {
        // Everything wrong at once: I1 wins, then I4, then I2, then I3.
        let mut fx = fixture();
        fx.policy.active = false;
        let wrong = [0xAC; 32];
        let mut f = facts(&fx);
        f.claimed_policy_hash = &wrong;
        f.valid_until_slot = 1;
        f.nonce_exists = true;
        f.receipt_exists = true;
        assert_eq!(check_execute(&f), Err(AuthorityError::PolicyRevoked));
        fx.policy.active = true;
        let mut f = facts(&fx);
        f.claimed_policy_hash = &wrong;
        f.valid_until_slot = 1;
        f.nonce_exists = true;
        f.receipt_exists = true;
        assert_eq!(check_execute(&f), Err(AuthorityError::PolicyHashMismatch));
        f.claimed_policy_hash = &fx.hash;
        assert_eq!(check_execute(&f), Err(AuthorityError::IntentExpired));
        f.valid_until_slot = 1_000;
        assert_eq!(
            check_execute(&f),
            Err(AuthorityError::IntentAlreadyExecuted)
        );
        f.receipt_exists = false;
        assert_eq!(check_execute(&f), Err(AuthorityError::NonceAlreadyUsed));
    }
}
