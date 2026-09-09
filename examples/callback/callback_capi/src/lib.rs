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
mod ffi {
    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for Reducer) rather than bare fns.
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
    pub struct Reducer;

    impl Reducer {
        // Exercises the backend's Type::Callback param shape: a bare
        // Fn callback, primitive-in/primitive-out only (the only shape
        // gen_method's callback branch supports). No other example crate
        // in this repo uses a Diplomat callback — this one exists solely
        // to give that generator path real test coverage.
        pub fn reduce(items: &[i32], f: impl Fn(i32, i32) -> i32) -> i32 {
            items.iter().skip(1).fold(items[0], |acc, &x| f(acc, x))
        }

        pub fn apply_twice(x: i32, f: impl Fn(i32) -> i32) -> i32 {
            f(f(x))
        }

        // Test fixture for rust-jolt-ef3.8's negative control ONLY — not part
        // of the real example API. reduce/apply_twice above run their
        // closure inline and never retain it, so there is no real
        // caller-controlled leak vector for this crate's actual surface;
        // this method exists solely to give leak_check_neg.clj something
        // genuine to detect. Box::leak intentionally leaks the boxed `impl
        // Fn` trait object Diplomat constructs to pass f across the FFI
        // boundary, instead of letting the destructor callback free it —
        // this is the Rust-side half of what a broken destructor
        // invocation would produce.
        pub fn apply_twice_leaky(x: i32, f: impl Fn(i32) -> i32 + 'static) -> i32 {
            let boxed: Box<dyn Fn(i32) -> i32> = Box::new(f);
            let leaked: &'static dyn Fn(i32) -> i32 = Box::leak(boxed);
            leaked(leaked(x))
        }
    }
}
