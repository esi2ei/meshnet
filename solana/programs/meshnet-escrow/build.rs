use std::{env, fs, path::PathBuf};

const BASE58_ALPHABET: &str = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

fn decode_public_key(value: &str) -> Option<Vec<u8>> {
    if !(32..=44).contains(&value.len()) {
        return None;
    }

    let mut decoded = vec![0_u8; value.len()];
    for character in value.chars() {
        let digit = BASE58_ALPHABET.find(character)? as u32;
        let mut carry = digit;
        for byte in decoded.iter_mut().rev() {
            carry += u32::from(*byte) * 58;
            *byte = (carry & 0xff) as u8;
            carry >>= 8;
        }
        if carry != 0 {
            return None;
        }
    }

    let leading_zeroes = value.bytes().take_while(|byte| *byte == b'1').count();
    let first_nonzero = decoded.iter().position(|byte| *byte != 0).unwrap_or(decoded.len());
    let mut key = vec![0_u8; leading_zeroes];
    key.extend_from_slice(&decoded[first_nonzero..]);
    if key.len() == 32 && key.iter().any(|byte| *byte != 0) {
        Some(key)
    } else {
        None
    }
}

fn required_public_key(name: &str) -> String {
    println!("cargo:rerun-if-env-changed={name}");
    let value = env::var(name).unwrap_or_else(|_| {
        panic!("{name} is required; placeholder program and admin keys are intentionally unsupported.")
    });
    if decode_public_key(&value).is_none() {
        panic!("{name} must be a valid, non-default, 32-byte base58 Solana public key.");
    }
    value
}

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    let program_id = required_public_key("MESHNET_ESCROW_PROGRAM_ID");
    let initial_admin = required_public_key("MESHNET_ESCROW_INITIAL_ADMIN");
    if program_id == initial_admin {
        panic!("The escrow program ID and initialization administrator must be different keys.");
    }

    let generated = format!(
        "declare_id!(\"{program_id}\");\npub const INITIAL_ADMIN: Pubkey = pubkey!(\"{initial_admin}\");\n"
    );
    let output = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo did not set OUT_DIR"));
    fs::write(output.join("deployment_config.rs"), generated)
        .expect("Unable to write generated escrow deployment identity");
}
