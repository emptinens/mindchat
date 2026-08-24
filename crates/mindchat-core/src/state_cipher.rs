//! AES-256-GCM encryption for state files at rest (ROADMAP 0.1.9 T1).
//!
//! On-disk layout of an encrypted state file:
//! `MINDCHAT\x01` magic || 12-byte random nonce || ciphertext || 16-byte tag.
//! The magic header doubles as the AES-GCM additional authenticated data, so
//! a file cannot be rebranded, truncated, or spliced between formats without
//! failing to open.
//!
//! Honesty note: key bytes live in caller memory on a best-effort basis only.
//! This crate deliberately adds no zeroizer this release; the caller owns the
//! key lifecycle and should hold it in non-exportable hardware-backed storage
//! where the platform offers one.

use crate::persistence::PersistenceError;
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use ring::rand::{SecureRandom, SystemRandom};

/// Magic header prefixing every encrypted state file; also used as AAD.
const MAGIC: &[u8; 9] = b"MINDCHAT\x01";

const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

/// Smallest plausible encrypted blob: magic + nonce + tag with empty content.
const MIN_ENCRYPTED_LEN: usize = MAGIC.len() + NONCE_LEN + TAG_LEN;

/// Whether `bytes` carries the encrypted-state-file magic header.
pub(crate) fn is_encrypted_blob(bytes: &[u8]) -> bool {
    bytes.starts_with(MAGIC)
}

/// Encrypts serialized state bytes into the on-disk blob layout.
///
/// The nonce comes from the OS secure RNG and differs on every call.
///
/// # Errors
///
/// Returns [`PersistenceError::Encryption`] if the secure RNG or the AEAD
/// backend fails (both are effectively impossible on supported platforms,
/// but are refused instead of silently degrading).
pub fn encrypt_state(plaintext: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, PersistenceError> {
    let mut nonce_bytes = [0u8; NONCE_LEN];
    SystemRandom::new()
        .fill(&mut nonce_bytes)
        .map_err(|_| PersistenceError::Encryption("secure RNG unavailable".to_owned()))?;
    let unbound = UnboundKey::new(&AES_256_GCM, key)
        .map_err(|_| PersistenceError::Encryption("AES-256-GCM key setup failed".to_owned()))?;
    let sealing = LessSafeKey::new(unbound);
    let mut ciphertext = plaintext.to_vec();
    let tag = sealing
        .seal_in_place_separate_tag(
            Nonce::assume_unique_for_key(nonce_bytes),
            Aad::from(MAGIC),
            &mut ciphertext,
        )
        .map_err(|_| PersistenceError::Encryption("AES-GCM seal failed".to_owned()))?;
    let mut blob = Vec::with_capacity(MIN_ENCRYPTED_LEN + ciphertext.len());
    blob.extend_from_slice(MAGIC);
    blob.extend_from_slice(&nonce_bytes);
    blob.extend_from_slice(&ciphertext);
    blob.extend_from_slice(tag.as_ref());
    Ok(blob)
}

/// Decrypts a state-file blob produced by [`encrypt_state`].
///
/// # Errors
///
/// Returns [`PersistenceError::NotEncrypted`] when the blob is too short to
/// be an encrypted state file or does not carry the magic header (callers use
/// this to detect legacy plaintext files), and
/// [`PersistenceError::Decryption`] when the blob claims encryption but fails
/// authentication under this key (wrong key or tampered content).
pub fn decrypt_state(blob: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, PersistenceError> {
    if blob.len() < MIN_ENCRYPTED_LEN || !is_encrypted_blob(blob) {
        return Err(PersistenceError::NotEncrypted);
    }
    let unbound = UnboundKey::new(&AES_256_GCM, key)
        .map_err(|_| PersistenceError::Encryption("AES-256-GCM key setup failed".to_owned()))?;
    let opening = LessSafeKey::new(unbound);
    // The length checks above prove both slice ranges are in bounds.
    let nonce_bytes: [u8; NONCE_LEN] = blob[MAGIC.len()..MAGIC.len() + NONCE_LEN]
        .try_into()
        .map_err(|_| PersistenceError::Decryption)?;
    let mut sealed = blob[MAGIC.len() + NONCE_LEN..].to_vec();
    let plaintext = opening
        .open_in_place(Nonce::assume_unique_for_key(nonce_bytes), Aad::from(MAGIC), &mut sealed)
        .map_err(|_| PersistenceError::Decryption)?;
    Ok(plaintext.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_key(byte: u8) -> [u8; 32] {
        [byte; 32]
    }

    #[test]
    fn round_trips_arbitrary_plaintext() {
        let plaintext = b"{\"schema_version\":1,\"snapshot\":{}}".to_vec();
        let blob = encrypt_state(&plaintext, &test_key(7)).expect("encrypt succeeds");
        let decrypted = decrypt_state(&blob, &test_key(7)).expect("decrypt succeeds");
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn wrong_key_fails_authentication() {
        let blob = encrypt_state(b"secret", &test_key(1)).expect("encrypt succeeds");
        assert!(matches!(decrypt_state(&blob, &test_key(2)), Err(PersistenceError::Decryption)));
    }

    #[test]
    fn garbage_without_magic_is_not_encrypted_error() {
        assert!(matches!(
            decrypt_state(b"this is not json at all", &test_key(1)),
            Err(PersistenceError::NotEncrypted)
        ));
    }

    #[test]
    fn truncated_magic_is_not_encrypted_error() {
        assert!(matches!(
            decrypt_state(b"MINDCHAT", &test_key(1)),
            Err(PersistenceError::NotEncrypted)
        ));
    }

    #[test]
    fn valid_magic_with_truncated_body_is_not_encrypted_error() {
        // Correct magic but far shorter than nonce + tag.
        let mut blob = MAGIC.to_vec();
        blob.extend_from_slice(&[0u8; 5]);
        assert!(matches!(decrypt_state(&blob, &test_key(1)), Err(PersistenceError::NotEncrypted)));
    }

    #[test]
    fn tampered_ciphertext_fails_authentication() {
        let mut blob = encrypt_state(b"secret", &test_key(1)).expect("encrypt succeeds");
        let last = blob.len() - 1;
        blob[last] ^= 0x01;
        assert!(matches!(decrypt_state(&blob, &test_key(1)), Err(PersistenceError::Decryption)));
    }

    #[test]
    fn nonces_differ_between_saves() {
        let first = encrypt_state(b"same input", &test_key(3)).expect("first encrypt");
        let second = encrypt_state(b"same input", &test_key(3)).expect("second encrypt");
        assert_ne!(&first[MAGIC.len()..MAGIC.len() + NONCE_LEN], &second[MAGIC.len()..]);
        assert_ne!(first, second);
    }

    #[test]
    fn encrypted_output_is_not_parseable_json() {
        let blob = encrypt_state(b"{\"looks\":\"like json\"}", &test_key(4)).expect("encrypt");
        assert!(serde_json::from_slice::<serde_json::Value>(&blob).is_err());
    }
}
