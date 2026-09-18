//! Cross-implementation vectors shared with the Java client
//! (`src/test/resources/authority/vectors-v1.json`, asserted by `IntentAuthorityVectorsTest`).
//!
//! The Rust side is the reference: `Pubkey::find_program_address` and borsh come from the Solana
//! SDK, not from this repo. Java must reproduce the same addresses, bumps, instruction bytes and
//! account layouts byte for byte. Regenerate with
//! `cargo test --test vectors -- --ignored write_vectors` and commit the file; v1 is frozen once
//! the program is deployed.

use borsh::to_vec;
use intent_authority::instruction::AuthorityInstruction;
use intent_authority::state::{
    Nonce, Policy, Receipt, NONCE_DISCRIMINATOR, POLICY_DISCRIMINATOR, RECEIPT_DISCRIMINATOR,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use solana_program::pubkey::Pubkey;

const VECTORS_PATH: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../src/test/resources/authority/vectors-v1.json"
);

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
struct Pda {
    address: String,
    bump: u8,
}

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
struct CurveCase {
    pubkey: String,
    on_curve: bool,
}

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
struct Vectors {
    schema_version: u32,
    program_id: String,
    agent: String,
    authority: String,
    policy_version: u32,
    policy_hash_hex: String,
    intent_hash_hex: String,
    nonce: u64,
    valid_until_slot: u64,
    registered_slot: u64,
    executed_slot: u64,
    policy_pda: Pda,
    nonce_pda: Pda,
    receipt_pda: Pda,
    register_policy_ix_hex: String,
    revoke_policy_ix_hex: String,
    execute_ix_hex: String,
    policy_account_hex: String,
    nonce_account_hex: String,
    receipt_account_hex: String,
    curve_cases: Vec<CurveCase>,
}

fn key(label: &str) -> Pubkey {
    Pubkey::new_from_array(Sha256::digest(label.as_bytes()).into())
}

fn hash32(label: &str) -> [u8; 32] {
    Sha256::digest(label.as_bytes()).into()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn compute() -> Vectors {
    let program_id = key("kan-437-intent-authority-vectors-v1");
    let agent = key("kan-437-agent");
    let authority = key("kan-437-authority");
    let policy_version = 7u32;
    let policy_hash = hash32("kan-437-policy-hash");
    let intent_hash = hash32("kan-437-intent");
    let nonce = 932_001u64;
    let valid_until_slot = 421_872_118u64;
    let registered_slot = 1_000u64;
    let executed_slot = 421_872_000u64;

    let (policy_addr, policy_bump) = Policy::find_address(&program_id, &agent, policy_version);
    let (nonce_addr, nonce_bump) = Nonce::find_address(&program_id, &agent, nonce);
    let (receipt_addr, receipt_bump) = Receipt::find_address(&program_id, &intent_hash);

    let register = AuthorityInstruction::RegisterPolicy {
        agent,
        policy_version,
        policy_hash,
    };
    let revoke = AuthorityInstruction::RevokePolicy;
    let execute = AuthorityInstruction::Execute {
        intent_hash,
        policy_version,
        policy_hash,
        valid_until_slot,
        nonce,
    };

    let policy_acc = Policy {
        discriminator: POLICY_DISCRIMINATOR,
        authority,
        agent,
        policy_version,
        policy_hash,
        active: true,
        registered_slot,
        bump: policy_bump,
    };
    let nonce_acc = Nonce {
        discriminator: NONCE_DISCRIMINATOR,
        agent,
        nonce,
        intent_hash,
        used_slot: executed_slot,
        bump: nonce_bump,
    };
    let receipt_acc = Receipt {
        discriminator: RECEIPT_DISCRIMINATOR,
        intent_hash,
        agent,
        policy_version,
        policy_hash,
        nonce,
        valid_until_slot,
        executed_slot,
        bump: receipt_bump,
    };

    // On-curve oracle for the Java PDA derivation: the SDK's answer for 24 deterministic keys
    // plus the three PDAs (off-curve by construction) and the all-zero key.
    let mut curve_cases: Vec<CurveCase> = (0..24)
        .map(|i| key(&format!("kan-437-curve-{i}")))
        .chain([
            policy_addr,
            nonce_addr,
            receipt_addr,
            Pubkey::default(),
            agent,
            program_id,
        ])
        .map(|k| CurveCase {
            pubkey: k.to_string(),
            on_curve: k.is_on_curve(),
        })
        .collect();
    curve_cases.sort_by(|a, b| a.pubkey.cmp(&b.pubkey));

    Vectors {
        schema_version: 1,
        program_id: program_id.to_string(),
        agent: agent.to_string(),
        authority: authority.to_string(),
        policy_version,
        policy_hash_hex: hex(&policy_hash),
        intent_hash_hex: hex(&intent_hash),
        nonce,
        valid_until_slot,
        registered_slot,
        executed_slot,
        policy_pda: Pda {
            address: policy_addr.to_string(),
            bump: policy_bump,
        },
        nonce_pda: Pda {
            address: nonce_addr.to_string(),
            bump: nonce_bump,
        },
        receipt_pda: Pda {
            address: receipt_addr.to_string(),
            bump: receipt_bump,
        },
        register_policy_ix_hex: hex(&to_vec(&register).unwrap()),
        revoke_policy_ix_hex: hex(&to_vec(&revoke).unwrap()),
        execute_ix_hex: hex(&to_vec(&execute).unwrap()),
        policy_account_hex: hex(&to_vec(&policy_acc).unwrap()),
        nonce_account_hex: hex(&to_vec(&nonce_acc).unwrap()),
        receipt_account_hex: hex(&to_vec(&receipt_acc).unwrap()),
        curve_cases,
    }
}

#[test]
fn committed_vectors_match_the_sdk() {
    let committed: Vectors = serde_json::from_str(&std::fs::read_to_string(VECTORS_PATH).expect(
        "vectors-v1.json missing: run `cargo test --test vectors -- --ignored write_vectors`",
    ))
    .expect("vectors-v1.json is not the expected shape");
    assert_eq!(
        committed,
        compute(),
        "vectors-v1.json drifted from the SDK derivation"
    );
}

#[test]
fn both_curve_answers_are_represented() {
    let v = compute();
    assert!(
        v.curve_cases.iter().any(|c| c.on_curve),
        "need at least one on-curve key"
    );
    assert!(
        v.curve_cases.iter().any(|c| !c.on_curve),
        "need at least one off-curve key"
    );
    assert!(!Pubkey::new_from_array(bs58_decode(&v.policy_pda.address)).is_on_curve());
}

fn bs58_decode(s: &str) -> [u8; 32] {
    s.parse::<Pubkey>().unwrap().to_bytes()
}

#[test]
#[ignore]
fn write_vectors() {
    let json = serde_json::to_string_pretty(&compute()).unwrap();
    std::fs::write(VECTORS_PATH, format!("{json}\n")).unwrap();
    println!("wrote {VECTORS_PATH}");
}
