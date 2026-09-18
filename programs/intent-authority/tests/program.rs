//! The program against a real bank (`solana-program-test`, native processor): every invariant
//! exercised through signed transactions, not through the pure rules. What is asserted is the
//! `Custom(n)` code the client will see on devnet.

use borsh::{to_vec, BorshDeserialize};
use intent_authority::error::AuthorityError;
use intent_authority::instruction::AuthorityInstruction;
use intent_authority::state::{
    Nonce, Policy, Receipt, NONCE_DISCRIMINATOR, POLICY_DISCRIMINATOR, RECEIPT_DISCRIMINATOR,
};
use solana_program_test::{processor, BanksClientError, ProgramTest, ProgramTestContext};
#[allow(deprecated)]
// system_instruction/system_program re-exports: one SDK crate instead of two
use solana_sdk::{
    instruction::{AccountMeta, Instruction, InstructionError},
    pubkey::Pubkey,
    signature::{Keypair, Signer},
    system_instruction, system_program,
    transaction::{Transaction, TransactionError},
};

const POLICY_HASH: [u8; 32] = [0xAB; 32];
const INTENT: [u8; 32] = [0x11; 32];
const VERSION: u32 = 1;

struct Harness {
    ctx: ProgramTestContext,
    program_id: Pubkey,
    authority: Keypair,
    agent: Keypair,
}

async fn harness() -> Harness {
    let program_id = Pubkey::new_unique();
    let pt = ProgramTest::new(
        "intent_authority",
        program_id,
        processor!(intent_authority::process_instruction),
    );
    let mut ctx = pt.start_with_context().await;
    let authority = Keypair::new();
    let agent = Keypair::new();
    fund(&mut ctx, &authority.pubkey(), 5_000_000_000).await;
    fund(&mut ctx, &agent.pubkey(), 5_000_000_000).await;
    Harness {
        ctx,
        program_id,
        authority,
        agent,
    }
}

async fn fund(ctx: &mut ProgramTestContext, to: &Pubkey, lamports: u64) {
    let payer = ctx.payer.insecure_clone();
    let bh = ctx.banks_client.get_latest_blockhash().await.unwrap();
    let tx = Transaction::new_signed_with_payer(
        &[system_instruction::transfer(&payer.pubkey(), to, lamports)],
        Some(&payer.pubkey()),
        &[&payer],
        bh,
    );
    ctx.banks_client.process_transaction(tx).await.unwrap();
}

fn register_ix(h: &Harness, agent: &Pubkey, version: u32, hash: [u8; 32]) -> Instruction {
    let (policy, _) = Policy::find_address(&h.program_id, agent, version);
    Instruction {
        program_id: h.program_id,
        accounts: vec![
            AccountMeta::new(h.authority.pubkey(), true),
            AccountMeta::new(policy, false),
            AccountMeta::new_readonly(system_program::ID, false),
        ],
        data: to_vec(&AuthorityInstruction::RegisterPolicy {
            agent: *agent,
            policy_version: version,
            policy_hash: hash,
        })
        .unwrap(),
    }
}

fn revoke_ix(h: &Harness, by: &Pubkey, agent: &Pubkey, version: u32) -> Instruction {
    let (policy, _) = Policy::find_address(&h.program_id, agent, version);
    Instruction {
        program_id: h.program_id,
        accounts: vec![
            AccountMeta::new_readonly(*by, true),
            AccountMeta::new(policy, false),
        ],
        data: to_vec(&AuthorityInstruction::RevokePolicy).unwrap(),
    }
}

struct Exec {
    intent: [u8; 32],
    version: u32,
    hash: [u8; 32],
    valid_until: u64,
    nonce: u64,
}

impl Default for Exec {
    fn default() -> Self {
        Exec {
            intent: INTENT,
            version: VERSION,
            hash: POLICY_HASH,
            valid_until: 1_000,
            nonce: 1,
        }
    }
}

fn execute_ix(
    h: &Harness,
    agent: &Pubkey,
    agent_signs: bool,
    payer: &Pubkey,
    policy_override: Option<Pubkey>,
    e: &Exec,
) -> Instruction {
    let (policy, _) = Policy::find_address(&h.program_id, agent, e.version);
    let (nonce, _) = Nonce::find_address(&h.program_id, agent, e.nonce);
    let (receipt, _) = Receipt::find_address(&h.program_id, &e.intent);
    Instruction {
        program_id: h.program_id,
        accounts: vec![
            AccountMeta::new_readonly(*agent, agent_signs),
            AccountMeta::new(*payer, true),
            AccountMeta::new_readonly(policy_override.unwrap_or(policy), false),
            AccountMeta::new(nonce, false),
            AccountMeta::new(receipt, false),
            AccountMeta::new_readonly(system_program::ID, false),
        ],
        data: to_vec(&AuthorityInstruction::Execute {
            intent_hash: e.intent,
            policy_version: e.version,
            policy_hash: e.hash,
            valid_until_slot: e.valid_until,
            nonce: e.nonce,
        })
        .unwrap(),
    }
}

async fn send(
    h: &mut Harness,
    ix: Instruction,
    signers: &[&Keypair],
) -> Result<(), BanksClientError> {
    let bh = h.ctx.banks_client.get_latest_blockhash().await.unwrap();
    let tx = Transaction::new_signed_with_payer(&[ix], Some(&signers[0].pubkey()), signers, bh);
    h.ctx.banks_client.process_transaction(tx).await
}

/// Registers the default policy and executes the default intent as the agent (agent pays).
async fn register_default(h: &mut Harness) {
    let ix = register_ix(h, &h.agent.pubkey(), VERSION, POLICY_HASH);
    let authority = h.authority.insecure_clone();
    send(h, ix, &[&authority]).await.expect("register");
}

async fn execute_as_agent(h: &mut Harness, e: &Exec) -> Result<(), BanksClientError> {
    let agent = h.agent.insecure_clone();
    let ix = execute_ix(h, &agent.pubkey(), true, &agent.pubkey(), None, e);
    send(h, ix, &[&agent]).await
}

fn custom(err: BanksClientError) -> AuthorityError {
    match err {
        BanksClientError::TransactionError(TransactionError::InstructionError(
            0,
            InstructionError::Custom(code),
        )) => match code {
            0 => AuthorityError::AgentSignatureMissing,
            1 => AuthorityError::PolicyAccountMismatch,
            2 => AuthorityError::PolicyNotRegistered,
            3 => AuthorityError::PolicyRevoked,
            4 => AuthorityError::PolicyHashMismatch,
            5 => AuthorityError::IntentExpired,
            6 => AuthorityError::NonceAlreadyUsed,
            7 => AuthorityError::IntentAlreadyExecuted,
            8 => AuthorityError::InvalidPda,
            9 => AuthorityError::NotPolicyAuthority,
            10 => AuthorityError::PolicyAlreadyRegistered,
            11 => AuthorityError::ZeroIntentHash,
            12 => AuthorityError::InvalidAccountData,
            other => panic!("unknown custom code {other}"),
        },
        other => panic!("expected a program refusal, got {other:?}"),
    }
}

async fn account<T: BorshDeserialize>(h: &mut Harness, key: &Pubkey) -> Option<T> {
    let acc = h.ctx.banks_client.get_account(*key).await.unwrap()?;
    assert_eq!(acc.owner, h.program_id, "account must be program-owned");
    Some(T::try_from_slice(&acc.data).unwrap())
}

#[tokio::test]
async fn register_then_execute_leaves_receipt_and_consumes_nonce() {
    let mut h = harness().await;
    register_default(&mut h).await;

    let (policy_addr, policy_bump) =
        Policy::find_address(&h.program_id, &h.agent.pubkey(), VERSION);
    let policy: Policy = account(&mut h, &policy_addr).await.expect("policy exists");
    assert_eq!(policy.discriminator, POLICY_DISCRIMINATOR);
    assert_eq!(policy.authority, h.authority.pubkey());
    assert_eq!(policy.agent, h.agent.pubkey());
    assert_eq!(policy.policy_version, VERSION);
    assert_eq!(policy.policy_hash, POLICY_HASH);
    assert!(policy.active);
    assert_eq!(policy.bump, policy_bump);

    execute_as_agent(&mut h, &Exec::default())
        .await
        .expect("execute");

    let (receipt_addr, receipt_bump) = Receipt::find_address(&h.program_id, &INTENT);
    let receipt: Receipt = account(&mut h, &receipt_addr)
        .await
        .expect("receipt exists");
    assert_eq!(receipt.discriminator, RECEIPT_DISCRIMINATOR);
    assert_eq!(receipt.intent_hash, INTENT);
    assert_eq!(receipt.agent, h.agent.pubkey());
    assert_eq!(receipt.policy_version, VERSION);
    assert_eq!(receipt.policy_hash, POLICY_HASH);
    assert_eq!(receipt.nonce, 1);
    assert_eq!(receipt.valid_until_slot, 1_000);
    assert!(receipt.executed_slot <= 1_000);
    assert_eq!(receipt.bump, receipt_bump);

    let (nonce_addr, nonce_bump) = Nonce::find_address(&h.program_id, &h.agent.pubkey(), 1);
    let nonce: Nonce = account(&mut h, &nonce_addr).await.expect("nonce consumed");
    assert_eq!(nonce.discriminator, NONCE_DISCRIMINATOR);
    assert_eq!(nonce.intent_hash, INTENT);
    assert_eq!(nonce.used_slot, receipt.executed_slot);
    assert_eq!(nonce.bump, nonce_bump);
}

#[tokio::test]
async fn i3_same_intent_twice_is_refused_even_with_a_fresh_nonce() {
    let mut h = harness().await;
    register_default(&mut h).await;
    execute_as_agent(&mut h, &Exec::default()).await.unwrap();
    let err = execute_as_agent(
        &mut h,
        &Exec {
            nonce: 2,
            ..Exec::default()
        },
    )
    .await
    .unwrap_err();
    assert_eq!(custom(err), AuthorityError::IntentAlreadyExecuted);
}

#[tokio::test]
async fn i3_nonce_reuse_with_another_intent_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    execute_as_agent(&mut h, &Exec::default()).await.unwrap();
    let err = execute_as_agent(
        &mut h,
        &Exec {
            intent: [0x22; 32],
            ..Exec::default()
        },
    )
    .await
    .unwrap_err();
    assert_eq!(custom(err), AuthorityError::NonceAlreadyUsed);
}

#[tokio::test]
async fn i2_intent_past_valid_until_slot_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    h.ctx.warp_to_slot(2_000).unwrap();
    let err = execute_as_agent(&mut h, &Exec::default())
        .await
        .unwrap_err();
    assert_eq!(custom(err), AuthorityError::IntentExpired);
    // Same intent with a window that covers the current slot still executes: the slot is the
    // only thing that changed.
    execute_as_agent(
        &mut h,
        &Exec {
            valid_until: 3_000,
            ..Exec::default()
        },
    )
    .await
    .expect("within window");
}

#[tokio::test]
async fn i4_policy_hash_other_than_committed_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let err = execute_as_agent(
        &mut h,
        &Exec {
            hash: [0xAC; 32],
            ..Exec::default()
        },
    )
    .await
    .unwrap_err();
    assert_eq!(custom(err), AuthorityError::PolicyHashMismatch);
}

#[tokio::test]
async fn i1_unregistered_policy_version_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let err = execute_as_agent(
        &mut h,
        &Exec {
            version: 2,
            ..Exec::default()
        },
    )
    .await
    .unwrap_err();
    assert_eq!(custom(err), AuthorityError::PolicyNotRegistered);
}

#[tokio::test]
async fn i1_agent_that_does_not_sign_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let payer = h.authority.insecure_clone();
    let agent = h.agent.pubkey();
    let ix = execute_ix(&h, &agent, false, &payer.pubkey(), None, &Exec::default());
    let err = send(&mut h, ix, &[&payer]).await.unwrap_err();
    assert_eq!(custom(err), AuthorityError::AgentSignatureMissing);
}

#[tokio::test]
async fn i1_another_agents_policy_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await; // policy of h.agent
    let intruder = Keypair::new();
    fund(&mut h.ctx, &intruder.pubkey(), 2_000_000_000).await;
    let (victims_policy, _) = Policy::find_address(&h.program_id, &h.agent.pubkey(), VERSION);
    let ix = execute_ix(
        &h,
        &intruder.pubkey(),
        true,
        &intruder.pubkey(),
        Some(victims_policy),
        &Exec::default(),
    );
    let err = send(&mut h, ix, &[&intruder]).await.unwrap_err();
    assert_eq!(custom(err), AuthorityError::PolicyAccountMismatch);
}

#[tokio::test]
async fn i1_revoked_policy_is_refused_and_only_authority_can_revoke() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let agent = h.agent.insecure_clone();
    let ix = revoke_ix(&h, &agent.pubkey(), &agent.pubkey(), VERSION);
    let err = send(&mut h, ix, &[&agent]).await.unwrap_err();
    assert_eq!(custom(err), AuthorityError::NotPolicyAuthority);

    let authority = h.authority.insecure_clone();
    let ix = revoke_ix(&h, &authority.pubkey(), &agent.pubkey(), VERSION);
    send(&mut h, ix, &[&authority]).await.expect("revoke");
    let err = execute_as_agent(&mut h, &Exec::default())
        .await
        .unwrap_err();
    assert_eq!(custom(err), AuthorityError::PolicyRevoked);
}

#[tokio::test]
async fn policies_are_immutable_a_second_register_of_the_same_version_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let authority = h.authority.insecure_clone();
    let ix = register_ix(&h, &h.agent.pubkey(), VERSION, [0xCD; 32]);
    let err = send(&mut h, ix, &[&authority]).await.unwrap_err();
    assert_eq!(custom(err), AuthorityError::PolicyAlreadyRegistered);
    // A new version is a new PDA and is accepted; the old one is untouched.
    let ix = register_ix(&h, &h.agent.pubkey(), 2, [0xCD; 32]);
    send(&mut h, ix, &[&authority]).await.expect("v2");
    let (v1, _) = Policy::find_address(&h.program_id, &h.agent.pubkey(), VERSION);
    let p1: Policy = account(&mut h, &v1).await.unwrap();
    assert_eq!(p1.policy_hash, POLICY_HASH);
}

#[tokio::test]
async fn prefunding_a_nonce_pda_does_not_burn_the_nonce() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let (nonce_addr, _) = Nonce::find_address(&h.program_id, &h.agent.pubkey(), 1);
    fund(&mut h.ctx, &nonce_addr, 10_000_000).await; // griefer sends lamports first
    execute_as_agent(&mut h, &Exec::default())
        .await
        .expect("still executes");
    let nonce: Nonce = account(&mut h, &nonce_addr).await.expect("nonce consumed");
    assert_eq!(nonce.intent_hash, INTENT);
}

#[tokio::test]
async fn wrong_nonce_or_receipt_account_is_a_malformed_transaction() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let agent = h.agent.insecure_clone();
    let mut ix = execute_ix(
        &h,
        &agent.pubkey(),
        true,
        &agent.pubkey(),
        None,
        &Exec::default(),
    );
    let (other_nonce, _) = Nonce::find_address(&h.program_id, &agent.pubkey(), 99);
    ix.accounts[3] = AccountMeta::new(other_nonce, false);
    let err = send(&mut h, ix, &[&agent]).await.unwrap_err();
    assert_eq!(custom(err), AuthorityError::InvalidPda);
}

#[tokio::test]
async fn zero_intent_hash_is_refused() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let err = execute_as_agent(
        &mut h,
        &Exec {
            intent: [0; 32],
            ..Exec::default()
        },
    )
    .await
    .unwrap_err();
    assert_eq!(custom(err), AuthorityError::ZeroIntentHash);
}

#[tokio::test]
async fn payer_may_differ_from_agent_but_agent_still_signs() {
    let mut h = harness().await;
    register_default(&mut h).await;
    let payer = h.authority.insecure_clone();
    let agent = h.agent.insecure_clone();
    let ix = execute_ix(
        &h,
        &agent.pubkey(),
        true,
        &payer.pubkey(),
        None,
        &Exec::default(),
    );
    send(&mut h, ix, &[&payer, &agent])
        .await
        .expect("payer + agent");
    let (receipt_addr, _) = Receipt::find_address(&h.program_id, &INTENT);
    let receipt: Receipt = account(&mut h, &receipt_addr).await.unwrap();
    assert_eq!(receipt.agent, agent.pubkey());
}
