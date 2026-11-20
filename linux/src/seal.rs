//! Sealing something for one device, so only it can read it (the server in between can't).
//! The scheme is `docs/PROTOCOL.md`'s, the same as `shared/…/Seal.kt`: a one-time P-256 key,
//! ECDH, HKDF-SHA256 and AES-256-GCM with the device's id as the associated data.

use aes_gcm::aead::{Aead, Payload as AeadPayload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use anyhow::{Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use hkdf::Hkdf;
use p256::elliptic_curve::sec1::ToEncodedPoint;
use p256::{PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::Sha256;

const INFO: &[u8] = b"samo/v1/seal";

/// Something sealed for one device.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Sealed {
    /// The sender's one-time public key (uncompressed point, base64).
    pub epk: String,
    /// 12 bytes, base64.
    pub nonce: String,
    /// AES-256-GCM, tag at the end, base64.
    pub ct: String,
}

/// This device's key pair.
#[derive(Clone)]
pub struct DeviceKey {
    secret: SecretKey,
}

impl DeviceKey {
    pub fn generate() -> Self {
        Self { secret: SecretKey::random(&mut OsRng) }
    }

    /// From the stored form: the 32-byte number, base64.
    pub fn from_private_text(text: &str) -> Result<Self> {
        let bytes = B64.decode(text.trim()).map_err(|_| anyhow!("Not a private key"))?;
        let secret = SecretKey::from_slice(&bytes).map_err(|_| anyhow!("Not a private key"))?;
        Ok(Self { secret })
    }

    pub fn private_text(&self) -> String {
        B64.encode(self.secret.to_bytes())
    }

    /// As devices exchange it: the 65-byte uncompressed point, base64.
    pub fn public_text(&self) -> String {
        B64.encode(point(&self.secret.public_key()))
    }
}

fn point(key: &PublicKey) -> Vec<u8> {
    key.to_encoded_point(false).as_bytes().to_vec()
}

/// Reads a public key, refusing anything that isn't an uncompressed point on the curve: a key
/// that isn't could leak the private key it's used with.
pub fn public_key(text: &str) -> Result<PublicKey> {
    let bytes = B64.decode(text.trim()).map_err(|_| anyhow!("Not a public key"))?;
    if bytes.len() != 65 || bytes[0] != 4 {
        bail!("Not a public key");
    }
    PublicKey::from_sec1_bytes(&bytes).map_err(|_| anyhow!("Not a public key"))
}

fn derive(secret: &SecretKey, public: &PublicKey, epk: &[u8], recipient: &[u8]) -> [u8; 32] {
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
    let mut salt = Vec::with_capacity(130);
    salt.extend_from_slice(epk);
    salt.extend_from_slice(recipient);
    let mut key = [0u8; 32];
    Hkdf::<Sha256>::new(Some(&salt), shared.raw_secret_bytes())
        .expand(INFO, &mut key)
        .expect("32 bytes is a valid HKDF-SHA256 length");
    key
}

/// Seals `plain` for the device `device_id`, whose public key is `recipient`.
pub fn seal(plain: &[u8], recipient: &str, device_id: &str) -> Result<Sealed> {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    seal_with(plain, recipient, device_id, &SecretKey::random(&mut OsRng), nonce)
}

/// [`seal`] with the one-time key and nonce given (for test vectors).
pub fn seal_with(plain: &[u8], recipient: &str, device_id: &str, ephemeral: &SecretKey, nonce: [u8; 12]) -> Result<Sealed> {
    let recipient_key = public_key(recipient)?;
    let epk = point(&ephemeral.public_key());
    let key = derive(ephemeral, &recipient_key, &epk, &point(&recipient_key));
    let ct = Aes256Gcm::new_from_slice(&key)
        .expect("a 32-byte key")
        .encrypt(&Nonce::from(nonce), AeadPayload { msg: plain, aad: device_id.as_bytes() })
        .map_err(|_| anyhow!("Couldn't seal that"))?;
    Ok(Sealed { epk: B64.encode(epk), nonce: B64.encode(nonce), ct: B64.encode(ct) })
}

/// Opens what was sealed for this device. Fails if it wasn't, or was changed.
pub fn open(sealed: &Sealed, key: &DeviceKey, device_id: &str) -> Result<Vec<u8>> {
    let epk = public_key(&sealed.epk)?;
    let nonce = B64.decode(sealed.nonce.trim()).map_err(|_| anyhow!("Not a sealed message"))?;
    let Ok(nonce) = <[u8; 12]>::try_from(nonce.as_slice()) else {
        bail!("Not a sealed message");
    };
    let ct = B64.decode(sealed.ct.trim()).map_err(|_| anyhow!("Not a sealed message"))?;
    let aes = derive(&key.secret, &epk, &point(&epk), &point(&key.secret.public_key()));
    Aes256Gcm::new_from_slice(&aes)
        .expect("a 32-byte key")
        .decrypt(&Nonce::from(nonce), AeadPayload { msg: &ct, aad: device_id.as_bytes() })
        .map_err(|_| anyhow!("That wasn't sealed for this device"))
}

#[cfg(test)]
mod tests {
    use super::*;

    // The vector published in docs/PROTOCOL.md.
    const DEVICE: &str = "device-1";
    const PRIVATE: &str = "fPVfWTT+lmpkhYZJbYmgJjnb2Yk2z5N40jybPxGus/g=";
    const PUBLIC: &str = "BLjLkiLeA9LQO1HSvascpiMSjqDzL48RyznPjsAfHRkwvfHZWmiCDUIt1/tIu0HcMP5IJjCNMrVxG2v6lxnHJic=";
    const PLAIN: &str = r#"{"type":"clipboard","text":"hello from the aurora"}"#;

    fn vector() -> Sealed {
        Sealed {
            epk: "BCb8iyUOM3J9h1vLpRI9Fb3lmP+DrST3xeOkuCKM5hRqMMj7XwcscLCPmJQH1FOH5SM9QZNPDYbkJI6ZTey43pQ=".into(),
            nonce: "od4tYckzNodlv4sX".into(),
            ct: "wy4elJA1eGU8oFGPEuH4GkjWfSz0kD/j2Eq6oP97idy//cg4UoNeWX//frfkREIWSOpTXNp+tYXjJqaMcXGOB+hiqw==".into(),
        }
    }

    #[test]
    fn opens_the_published_vector() {
        let key = DeviceKey::from_private_text(PRIVATE).unwrap();
        assert_eq!(key.public_text(), PUBLIC, "the public half follows from the private key");
        assert_eq!(String::from_utf8(open(&vector(), &key, DEVICE).unwrap()).unwrap(), PLAIN);
    }

    #[test]
    fn round_trips() {
        let key = DeviceKey::generate();
        let again = DeviceKey::from_private_text(&key.private_text()).unwrap();
        assert_eq!(again.public_text(), key.public_text());
        let sealed = seal("héllo ✨".as_bytes(), &key.public_text(), "d").unwrap();
        assert_eq!(open(&sealed, &again, "d").unwrap(), "héllo ✨".as_bytes());
        // Each sealing uses a new one-time key and nonce.
        let second = seal("héllo ✨".as_bytes(), &key.public_text(), "d").unwrap();
        assert_ne!(sealed, second);
    }

    #[test]
    fn the_wrong_device_id_fails() {
        let key = DeviceKey::from_private_text(PRIVATE).unwrap();
        assert!(open(&vector(), &key, "device-2").is_err());
    }

    #[test]
    fn the_wrong_key_or_a_changed_message_fails() {
        assert!(open(&vector(), &DeviceKey::generate(), DEVICE).is_err());
        let key = DeviceKey::from_private_text(PRIVATE).unwrap();
        let mut changed = vector();
        let mut ct = B64.decode(&changed.ct).unwrap();
        ct[3] ^= 1;
        changed.ct = B64.encode(ct);
        assert!(open(&changed, &key, DEVICE).is_err());
        let mut short = vector();
        short.nonce = B64.encode([1u8; 8]);
        assert!(open(&short, &key, DEVICE).is_err());
    }

    #[test]
    fn a_public_key_off_the_curve_is_rejected() {
        // A real point with its Y nudged: no longer on P-256.
        let mut bytes = B64.decode(PUBLIC).unwrap();
        bytes[64] ^= 1;
        let off = B64.encode(&bytes);
        assert!(public_key(&off).is_err());
        assert!(seal(b"x", &off, "d").is_err());
        let key = DeviceKey::from_private_text(PRIVATE).unwrap();
        let mut sealed = vector();
        sealed.epk = off;
        assert!(open(&sealed, &key, DEVICE).is_err());
        // Not the uncompressed form, the wrong length, the point at infinity, not base64.
        let mut compressed = B64.decode(PUBLIC).unwrap()[..33].to_vec();
        compressed[0] = 2;
        assert!(public_key(&B64.encode(compressed)).is_err());
        assert!(public_key(&B64.encode([4u8; 64])).is_err());
        assert!(public_key(&B64.encode([0u8])).is_err());
        let mut zeros = vec![4u8];
        zeros.extend([0u8; 64]);
        assert!(public_key(&B64.encode(zeros)).is_err());
        assert!(public_key("not base64!").is_err());
        assert!(public_key(PUBLIC).is_ok());
    }

    #[test]
    fn sealing_with_a_given_key_and_nonce_is_repeatable() {
        let key = DeviceKey::generate();
        let one_time = SecretKey::random(&mut OsRng);
        let a = seal_with(b"same", &key.public_text(), "d", &one_time, [7; 12]).unwrap();
        let b = seal_with(b"same", &key.public_text(), "d", &one_time, [7; 12]).unwrap();
        assert_eq!(a, b);
        assert_eq!(open(&a, &key, "d").unwrap(), b"same");
    }
}
