//! # intent-authority — CryptoBot's on-chain authority (paper level 4, KAN-437)
//!
//! Level 3 (KAN-435/436/440) proves off-chain that an intent `I` with hash `H_I` satisfied policy
//! `R_v` with hash `H_R`. This program makes four of those facts **unforgeable by the client**:
//!
//! | | checked on-chain against | refusal |
//! |---|---|---|
//! | I1 authorized agent | the transaction signature of `agent` + the policy PDA of `(agent, version)` | `AgentSignatureMissing`, `PolicyAccountMismatch`, `PolicyNotRegistered`, `PolicyRevoked` |
//! | I2 not expired | `Clock.slot <= valid_until_slot` | `IntentExpired` |
//! | I3 single execution | receipt PDA of `H_I` and nonce PDA of `(agent, nonce)` must not exist | `IntentAlreadyExecuted`, `NonceAlreadyUsed` |
//! | I4 policy binding | `policy.policy_hash == claimed H_R` | `PolicyHashMismatch` |
//!
//! The AI never touches this: it emitted an intent; the service canonicalized and hashed it; the
//! agent key signed the transaction that carries `H_I`. What the chain verifies is not the
//! reasoning but that *this* hash, under *this* committed policy, within *this* window, executes
//! *once* (paper §10, §27). The "execution" at this level is the receipt account itself — no
//! transfer, no swap, devnet only. Nothing here moves value.
//!
//! No `declare_id!`: the program derives every PDA from the `program_id` it is invoked with, so
//! the same binary runs under any deployed address (devnet today; anything else is a human gate).

pub mod error;
pub mod instruction;
pub mod rules;
pub mod state;

use borsh::{BorshDeserialize, BorshSerialize};
// `system_instruction` is deprecated in favour of the `solana-system-interface` crate; staying on
// the re-export keeps this program at one runtime dependency.
#[allow(deprecated)]
use solana_program::{
    account_info::{next_account_info, AccountInfo},
    clock::Clock,
    entrypoint::ProgramResult,
    msg,
    program::invoke_signed,
    program_error::ProgramError,
    pubkey::Pubkey,
    rent::Rent,
    system_instruction, system_program,
    sysvar::Sysvar,
};

use error::AuthorityError;
use instruction::AuthorityInstruction;
use rules::{check_execute, ExecuteFacts};
use state::{
    Nonce, Policy, Receipt, NONCE_DISCRIMINATOR, NONCE_SEED, POLICY_DISCRIMINATOR, POLICY_SEED,
    RECEIPT_DISCRIMINATOR, RECEIPT_SEED,
};

#[cfg(not(feature = "no-entrypoint"))]
solana_program::entrypoint!(process_instruction);

pub fn process_instruction(
    program_id: &Pubkey,
    accounts: &[AccountInfo],
    data: &[u8],
) -> ProgramResult {
    let ix = AuthorityInstruction::try_from_slice(data)
        .map_err(|_| ProgramError::InvalidInstructionData)?;
    match ix {
        AuthorityInstruction::RegisterPolicy {
            agent,
            policy_version,
            policy_hash,
        } => register_policy(program_id, accounts, agent, policy_version, policy_hash),
        AuthorityInstruction::RevokePolicy => revoke_policy(program_id, accounts),
        AuthorityInstruction::Execute {
            intent_hash,
            policy_version,
            policy_hash,
            valid_until_slot,
            nonce,
        } => execute(
            program_id,
            accounts,
            intent_hash,
            policy_version,
            policy_hash,
            valid_until_slot,
            nonce,
        ),
    }
}

fn register_policy(
    program_id: &Pubkey,
    accounts: &[AccountInfo],
    agent: Pubkey,
    policy_version: u32,
    policy_hash: [u8; 32],
) -> ProgramResult {
    let iter = &mut accounts.iter();
    let authority = next_account_info(iter)?;
    let policy_acc = next_account_info(iter)?;
    let system = next_account_info(iter)?;

    if !authority.is_signer {
        return Err(ProgramError::MissingRequiredSignature);
    }
    expect_system_program(system)?;
    let (expected, bump) = Policy::find_address(program_id, &agent, policy_version);
    if policy_acc.key != &expected {
        return Err(AuthorityError::InvalidPda.into());
    }
    if is_initialized(policy_acc, program_id) {
        return Err(AuthorityError::PolicyAlreadyRegistered.into());
    }
    let seeds: &[&[u8]] = &[
        POLICY_SEED,
        agent.as_ref(),
        &policy_version.to_le_bytes(),
        &[bump],
    ];
    create_pda(
        authority,
        policy_acc,
        system,
        program_id,
        Policy::LEN,
        seeds,
    )?;

    let policy = Policy {
        discriminator: POLICY_DISCRIMINATOR,
        authority: *authority.key,
        agent,
        policy_version,
        policy_hash,
        active: true,
        registered_slot: Clock::get()?.slot,
        bump,
    };
    policy.serialize(&mut &mut policy_acc.data.borrow_mut()[..])?;
    msg!(
        "intent-authority: policy registered agent={} version={}",
        agent,
        policy_version
    );
    Ok(())
}

fn revoke_policy(program_id: &Pubkey, accounts: &[AccountInfo]) -> ProgramResult {
    let iter = &mut accounts.iter();
    let authority = next_account_info(iter)?;
    let policy_acc = next_account_info(iter)?;

    if !authority.is_signer {
        return Err(ProgramError::MissingRequiredSignature);
    }
    let mut policy =
        read_policy(policy_acc, program_id).ok_or(AuthorityError::PolicyNotRegistered)?;
    let (expected, _) = Policy::find_address(program_id, &policy.agent, policy.policy_version);
    if policy_acc.key != &expected {
        return Err(AuthorityError::InvalidPda.into());
    }
    if &policy.authority != authority.key {
        return Err(AuthorityError::NotPolicyAuthority.into());
    }
    policy.active = false;
    policy.serialize(&mut &mut policy_acc.data.borrow_mut()[..])?;
    msg!(
        "intent-authority: policy revoked agent={} version={}",
        policy.agent,
        policy.policy_version
    );
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn execute(
    program_id: &Pubkey,
    accounts: &[AccountInfo],
    intent_hash: [u8; 32],
    policy_version: u32,
    policy_hash: [u8; 32],
    valid_until_slot: u64,
    nonce: u64,
) -> ProgramResult {
    let iter = &mut accounts.iter();
    let agent = next_account_info(iter)?;
    let payer = next_account_info(iter)?;
    let policy_acc = next_account_info(iter)?;
    let nonce_acc = next_account_info(iter)?;
    let receipt_acc = next_account_info(iter)?;
    let system = next_account_info(iter)?;

    if !payer.is_signer {
        return Err(ProgramError::MissingRequiredSignature);
    }
    expect_system_program(system)?;

    // Derive what the client *should* have passed; a wrong nonce/receipt account is a malformed
    // transaction, not a policy decision — refused before the rules run.
    let (expected_policy, _) = Policy::find_address(program_id, agent.key, policy_version);
    let (expected_nonce, nonce_bump) = Nonce::find_address(program_id, agent.key, nonce);
    let (expected_receipt, receipt_bump) = Receipt::find_address(program_id, &intent_hash);
    if nonce_acc.key != &expected_nonce || receipt_acc.key != &expected_receipt {
        return Err(AuthorityError::InvalidPda.into());
    }

    let policy = read_policy(policy_acc, program_id);
    let current_slot = Clock::get()?.slot;
    let facts = ExecuteFacts {
        agent: agent.key,
        agent_is_signer: agent.is_signer,
        policy_account: policy_acc.key,
        expected_policy_pda: &expected_policy,
        policy: policy.as_ref(),
        intent_hash: &intent_hash,
        claimed_policy_hash: &policy_hash,
        valid_until_slot,
        current_slot,
        nonce_exists: is_initialized(nonce_acc, program_id),
        receipt_exists: is_initialized(receipt_acc, program_id),
    };
    if let Err(e) = check_execute(&facts) {
        msg!("intent-authority: refused {:?}", e);
        return Err(e.into());
    }

    // All four invariants hold: consume the nonce and leave the receipt, atomically.
    let nonce_seeds: &[&[u8]] = &[
        NONCE_SEED,
        agent.key.as_ref(),
        &nonce.to_le_bytes(),
        &[nonce_bump],
    ];
    create_pda(
        payer,
        nonce_acc,
        system,
        program_id,
        Nonce::LEN,
        nonce_seeds,
    )?;
    Nonce {
        discriminator: NONCE_DISCRIMINATOR,
        agent: *agent.key,
        nonce,
        intent_hash,
        used_slot: current_slot,
        bump: nonce_bump,
    }
    .serialize(&mut &mut nonce_acc.data.borrow_mut()[..])?;

    let receipt_seeds: &[&[u8]] = &[RECEIPT_SEED, &intent_hash, &[receipt_bump]];
    create_pda(
        payer,
        receipt_acc,
        system,
        program_id,
        Receipt::LEN,
        receipt_seeds,
    )?;
    Receipt {
        discriminator: RECEIPT_DISCRIMINATOR,
        intent_hash,
        agent: *agent.key,
        policy_version,
        policy_hash,
        nonce,
        valid_until_slot,
        executed_slot: current_slot,
        bump: receipt_bump,
    }
    .serialize(&mut &mut receipt_acc.data.borrow_mut()[..])?;

    msg!(
        "intent-authority: executed agent={} version={} nonce={} slot={}",
        agent.key,
        policy_version,
        nonce,
        current_slot
    );
    Ok(())
}

/// "Exists" for the rules means *initialized by this program*: owned by it and with data. A
/// stranger sending lamports to a nonce PDA must not be able to burn that nonce (griefing), so
/// a system-owned, data-less account with a balance is still creatable — see `create_pda`.
fn is_initialized(acc: &AccountInfo, program_id: &Pubkey) -> bool {
    acc.owner == program_id && !acc.data_is_empty()
}

fn read_policy(acc: &AccountInfo, program_id: &Pubkey) -> Option<Policy> {
    if !is_initialized(acc, program_id) {
        return None;
    }
    let data = acc.data.borrow();
    if data.len() != Policy::LEN || data[0] != POLICY_DISCRIMINATOR {
        return None;
    }
    Policy::try_from_slice(&data).ok()
}

fn expect_system_program(acc: &AccountInfo) -> ProgramResult {
    if acc.key != &system_program::ID {
        return Err(ProgramError::IncorrectProgramId);
    }
    Ok(())
}

/// Creates a program-owned PDA of `space` bytes paid by `payer`. Handles the pre-funded case
/// (lamports already there, still system-owned) with transfer + allocate + assign, so the
/// address can always be claimed by the program and never by anyone else.
fn create_pda<'a>(
    payer: &AccountInfo<'a>,
    target: &AccountInfo<'a>,
    system: &AccountInfo<'a>,
    program_id: &Pubkey,
    space: usize,
    signer_seeds: &[&[u8]],
) -> ProgramResult {
    if target.owner == program_id && !target.data_is_empty() {
        return Err(ProgramError::AccountAlreadyInitialized);
    }
    if !target.data_is_empty() || target.owner != &system_program::ID {
        return Err(AuthorityError::InvalidAccountData.into());
    }
    let rent = Rent::get()?;
    let required = rent.minimum_balance(space);
    let current = target.lamports();
    if current == 0 {
        invoke_signed(
            &system_instruction::create_account(
                payer.key,
                target.key,
                required,
                space as u64,
                program_id,
            ),
            &[payer.clone(), target.clone(), system.clone()],
            &[signer_seeds],
        )
    } else {
        if current < required {
            invoke_signed(
                &system_instruction::transfer(payer.key, target.key, required - current),
                &[payer.clone(), target.clone(), system.clone()],
                &[],
            )?;
        }
        invoke_signed(
            &system_instruction::allocate(target.key, space as u64),
            &[target.clone(), system.clone()],
            &[signer_seeds],
        )?;
        invoke_signed(
            &system_instruction::assign(target.key, program_id),
            &[target.clone(), system.clone()],
            &[signer_seeds],
        )
    }
}
