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
#[diplomat::abi_rename = "sv_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for Version/VersionReq) rather than bare fns.
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
    pub struct Version(semver::Version);

    #[diplomat::opaque]
    pub struct VersionReq(semver::VersionReq);

    #[diplomat::opaque]
    pub struct ParseError(String);

    impl ParseError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Version {
        pub fn parse(text: &str) -> Result<Box<Version>, Box<ParseError>> {
            semver::Version::parse(text)
                .map(|v| Box::new(Version(v)))
                .map_err(|e| Box::new(ParseError(e.to_string())))
        }

        pub fn major(&self) -> u64 { self.0.major }
        pub fn minor(&self) -> u64 { self.0.minor }
        pub fn patch(&self) -> u64 { self.0.patch }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }

        pub fn is_prerelease(&self) -> bool {
            !self.0.pre.is_empty()
        }

        pub fn pre(&self, write: &mut DiplomatWrite) -> Option<()> {
            if self.0.pre.is_empty() { return None; }
            let _ = write.write_str(self.0.pre.as_str());
            Some(())
        }
    }

    impl VersionReq {
        pub fn parse(text: &str) -> Result<Box<VersionReq>, Box<ParseError>> {
            semver::VersionReq::parse(text)
                .map(|r| Box::new(VersionReq(r)))
                .map_err(|e| Box::new(ParseError(e.to_string())))
        }

        pub fn matches(&self, version: &Version) -> bool {
            self.0.matches(&version.0)
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }
    }
}
