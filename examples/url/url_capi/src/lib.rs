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
#[diplomat::abi_rename = "url_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for Url) rather than bare fns.
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

    pub struct UrlInfo {
        pub port: u16,
        pub has_port: bool,
        pub path_len: u32,
    }

    #[diplomat::opaque]
    pub struct Url(url::Url);

    #[diplomat::opaque]
    pub struct UrlError(String);

    impl UrlError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Url {
        pub fn parse(input: &str) -> Result<Box<Url>, Box<UrlError>> {
            url::Url::parse(input)
                .map(|u| Box::new(Url(u)))
                .map_err(|e| Box::new(UrlError(e.to_string())))
        }

        pub fn scheme(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.scheme());
        }

        pub fn host(&self, write: &mut DiplomatWrite) -> Option<()> {
            let h = self.0.host_str()?;
            let _ = write.write_str(h);
            Some(())
        }

        pub fn path(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.path());
        }

        pub fn query(&self, write: &mut DiplomatWrite) -> Option<()> {
            let q = self.0.query()?;
            let _ = write.write_str(q);
            Some(())
        }

        pub fn port(&self) -> Option<u16> {
            self.0.port()
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.as_str());
        }

        pub fn info(&self) -> UrlInfo {
            UrlInfo {
                port: self.0.port().unwrap_or(0),
                has_port: self.0.port().is_some(),
                path_len: self.0.path().len() as u32,
            }
        }
    }
}
