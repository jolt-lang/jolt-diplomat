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
#[diplomat::abi_rename = "chrono_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for DateTime) rather than bare fns.
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

    pub struct DateComponents {
        pub year: i32,
        pub month: u8,
        pub day: u8,
        pub hour: u8,
        pub minute: u8,
        pub second: u8,
    }

    #[diplomat::opaque]
    pub struct DateTime(chrono::DateTime<chrono::Utc>);

    #[diplomat::opaque]
    pub struct DateTimeError(String);

    impl DateTimeError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl DateTime {
        pub fn now() -> Box<DateTime> {
            Box::new(DateTime(chrono::Utc::now()))
        }

        pub fn parse(s: &str) -> Result<Box<DateTime>, Box<DateTimeError>> {
            s.parse::<chrono::DateTime<chrono::Utc>>()
                .map(|dt| Box::new(DateTime(dt)))
                .map_err(|e| Box::new(DateTimeError(e.to_string())))
        }

        pub fn from_timestamp(secs: i64) -> Option<Box<DateTime>> {
            chrono::DateTime::from_timestamp(secs, 0).map(|dt| Box::new(DateTime(dt)))
        }

        pub fn to_rfc3339(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_rfc3339());
        }

        pub fn format(&self, fmt: &str, write: &mut DiplomatWrite) -> Option<()> {
            use chrono::format::strftime::StrftimeItems;
            let items: Vec<_> = StrftimeItems::new(fmt).collect();
            if items.iter().any(|i| matches!(i, chrono::format::Item::Error)) {
                return None;
            }
            let _ = write!(write, "{}", self.0.format(fmt));
            Some(())
        }

        pub fn timestamp_secs(&self) -> i64 {
            self.0.timestamp()
        }

        pub fn components(&self) -> DateComponents {
            use chrono::Datelike as _;
            use chrono::Timelike as _;
            DateComponents {
                year: self.0.year(),
                month: self.0.month() as u8,
                day: self.0.day() as u8,
                hour: self.0.hour() as u8,
                minute: self.0.minute() as u8,
                second: self.0.second() as u8,
            }
        }
    }
}
