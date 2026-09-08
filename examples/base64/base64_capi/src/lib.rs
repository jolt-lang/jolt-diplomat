#[cfg(feature = "mem-trace")]
use std::alloc::System;
#[cfg(feature = "mem-trace")]
use stats_alloc::{StatsAlloc, INSTRUMENTED_SYSTEM};

// Only swapped in for the mem-trace build (see rust-jolt-ef3.1) — the
// consumable/default build keeps the plain system allocator and carries
// no tracing surface at all. bind.clj must be run once per feature set
// (--out-suffix -traced for this one) since diplomat-tool/the backend
// parse lib.rs textually and don't evaluate #[cfg(feature = ...)] —
// see bind.clj's file header comment.
#[cfg(feature = "mem-trace")]
#[global_allocator]
static GLOBAL: &StatsAlloc<System> = &INSTRUMENTED_SYSTEM;

#[diplomat::bridge]
#[diplomat::abi_rename = "b64_{0}_mv1"]
mod ffi {
    use base64::Engine as _;
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for Codec/Hex) rather than bare fns.
    #[cfg(feature = "mem-trace")]
    #[diplomat::opaque]
    pub struct AllocStats;

    #[cfg(feature = "mem-trace")]
    impl AllocStats {
        pub fn bytes_allocated() -> u64 {
            super::GLOBAL.stats().bytes_allocated as u64
        }

        pub fn bytes_deallocated() -> u64 {
            super::GLOBAL.stats().bytes_deallocated as u64
        }
    }

    #[diplomat::opaque]
    pub struct Base64Error(String);

    impl Base64Error {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    #[diplomat::opaque]
    pub struct Codec(base64::engine::GeneralPurpose);

    impl Codec {
        pub fn standard() -> Box<Codec> {
            Box::new(Codec(base64::engine::general_purpose::STANDARD))
        }

        pub fn url_safe() -> Box<Codec> {
            Box::new(Codec(base64::engine::general_purpose::URL_SAFE))
        }

        pub fn encode(&self, input: &[u8], write: &mut DiplomatWrite) {
            let encoded = self.0.encode(input);
            let _ = write.write_str(&encoded);
        }

        pub fn decode(&self, input: &str, write: &mut DiplomatWrite) -> Result<(), Box<Base64Error>> {
            let bytes = self.0.decode(input)
                .map_err(|e| Box::new(Base64Error(e.to_string())))?;
            for b in bytes {
                let _ = write.write_char(b as char);
            }
            Ok(())
        }
    }

    #[diplomat::opaque]
    pub struct Hex(());

    impl Hex {
        pub fn new() -> Box<Hex> { Box::new(Hex(())) }

        pub fn encode(input: &[u8], write: &mut DiplomatWrite) {
            let _ = write.write_str(&hex::encode(input));
        }

        pub fn decode(input: &str, write: &mut DiplomatWrite) -> Result<(), Box<Base64Error>> {
            let bytes = hex::decode(input)
                .map_err(|e| Box::new(Base64Error(e.to_string())))?;
            for b in bytes {
                let _ = write.write_char(b as char);
            }
            Ok(())
        }
    }
}
