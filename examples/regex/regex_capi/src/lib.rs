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
#[diplomat::abi_rename = "rx_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for Regex) rather than bare fns.
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
    pub struct Regex(regex::Regex);

    #[diplomat::opaque]
    pub struct RegexError(String);

    impl RegexError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Regex {
        /// Compile a regex pattern. Returns Err with a RegexError on invalid pattern.
        pub fn create(pattern: &str) -> Result<Box<Regex>, Box<RegexError>> {
            regex::Regex::new(pattern)
                .map(|r| Box::new(Regex(r)))
                .map_err(|e| Box::new(RegexError(e.to_string())))
        }

        /// Returns true if the regex matches anywhere in text.
        pub fn is_match(&self, text: &str) -> bool {
            self.0.is_match(text)
        }

        /// Writes the first match to write. Returns false (no output) if no match.
        pub fn find(&self, text: &str, write: &mut DiplomatWrite) -> Option<()> {
            let m = self.0.find(text)?;
            let _ = write.write_str(m.as_str());
            Some(())
        }

        /// Replaces all matches with replacement, writing result to write.
        pub fn replace_all(&self, text: &str, replacement: &str, write: &mut DiplomatWrite) {
            let result = self.0.replace_all(text, replacement);
            let _ = write.write_str(&result);
        }
    }
}
