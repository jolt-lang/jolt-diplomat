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
#[diplomat::abi_rename = "json_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    /// Cumulative bytes this process has passed to the allocator, via
    /// stats_alloc wrapping the global System allocator — see GLOBAL in
    /// the parent module. mem-trace only — see that cfg gate above.
    /// This repo's jolt-diplomat backend doesn't support Diplomat free
    /// functions yet ("backend does not support free functions"), so
    /// these are static methods on an opaque (same shape the backend
    /// already handles for JsonValue) rather than bare fns.
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

    pub enum JsonKind {
        Null,
        Bool,
        Number,
        String,
        Array,
        Object,
    }

    #[diplomat::opaque]
    pub struct JsonError(String);

    impl JsonError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    #[diplomat::opaque_mut]
    pub struct JsonValue(serde_json::Value);

    impl JsonValue {
        pub fn parse(text: &str) -> Result<Box<JsonValue>, Box<JsonError>> {
            serde_json::from_str(text)
                .map(|v| Box::new(JsonValue(v)))
                .map_err(|e| Box::new(JsonError(e.to_string())))
        }

        pub fn kind(&self) -> JsonKind {
            match &self.0 {
                serde_json::Value::Null => JsonKind::Null,
                serde_json::Value::Bool(_) => JsonKind::Bool,
                serde_json::Value::Number(_) => JsonKind::Number,
                serde_json::Value::String(_) => JsonKind::String,
                serde_json::Value::Array(_) => JsonKind::Array,
                serde_json::Value::Object(_) => JsonKind::Object,
            }
        }

        pub fn as_bool(&self) -> Option<bool> {
            self.0.as_bool()
        }

        pub fn as_f64(&self) -> Option<f64> {
            self.0.as_f64()
        }

        pub fn as_str(&self, write: &mut DiplomatWrite) -> Option<()> {
            let s = self.0.as_str()?;
            let _ = write.write_str(s);
            Some(())
        }

        pub fn array_len(&self) -> Option<u64> {
            self.0.as_array().map(|a| a.len() as u64)
        }

        pub fn array_get(&self, index: u64) -> Option<Box<JsonValue>> {
            self.0.as_array()?.get(index as usize).map(|v| Box::new(JsonValue(v.clone())))
        }

        pub fn object_get(&self, key: &str, write: &mut DiplomatWrite) -> Option<()> {
            let v = self.0.as_object()?.get(key)?;
            let _ = write.write_str(&v.to_string());
            Some(())
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }

        // -- builder API (added for lambda-mvp-rst: Jolt has no native
        // JSON library, so a handler that wants to *build* a response
        // object — not just parse/read one — needs these. See
        // lambda-mvp-jlt's handler.clj, which hand-assembles JSON by
        // string interpolation for exactly this reason.)

        pub fn new_object() -> Box<JsonValue> {
            Box::new(JsonValue(serde_json::Value::Object(serde_json::Map::new())))
        }

        pub fn new_array() -> Box<JsonValue> {
            Box::new(JsonValue(serde_json::Value::Array(Vec::new())))
        }

        pub fn new_string(value: &str) -> Box<JsonValue> {
            Box::new(JsonValue(serde_json::Value::String(value.to_string())))
        }

        pub fn new_number(value: f64) -> Box<JsonValue> {
            let n = serde_json::Number::from_f64(value)
                .map(serde_json::Value::Number)
                .unwrap_or(serde_json::Value::Null);
            Box::new(JsonValue(n))
        }

        pub fn new_bool(value: bool) -> Box<JsonValue> {
            Box::new(JsonValue(serde_json::Value::Bool(value)))
        }

        /// Sets `key` to a string value. No-op (returns `false`) if `self`
        /// isn't an object — the generator's `unwrap-result!`/nullable
        /// conventions don't cover a silently-ignored write, so callers
        /// must check the return value rather than assume success.
        pub fn set_string(&mut self, key: &str, value: &str) -> bool {
            match self.0.as_object_mut() {
                Some(map) => {
                    map.insert(key.to_string(), serde_json::Value::String(value.to_string()));
                    true
                }
                None => false,
            }
        }

        pub fn set_number(&mut self, key: &str, value: f64) -> bool {
            match self.0.as_object_mut() {
                Some(map) => {
                    let n = serde_json::Number::from_f64(value)
                        .map(serde_json::Value::Number)
                        .unwrap_or(serde_json::Value::Null);
                    map.insert(key.to_string(), n);
                    true
                }
                None => false,
            }
        }

        pub fn set_bool(&mut self, key: &str, value: bool) -> bool {
            match self.0.as_object_mut() {
                Some(map) => {
                    map.insert(key.to_string(), serde_json::Value::Bool(value));
                    true
                }
                None => false,
            }
        }

        /// Embeds a clone of `value` (any kind — object/array/scalar) under
        /// `key`. Clone, not move: `value` stays owned by its own caller-held
        /// handle and must still be freed independently — this mirrors
        /// `array_get`'s existing clone-out convention above rather than
        /// introducing a new ownership rule for one method.
        pub fn set_value(&mut self, key: &str, value: &JsonValue) -> bool {
            match self.0.as_object_mut() {
                Some(map) => {
                    map.insert(key.to_string(), value.0.clone());
                    true
                }
                None => false,
            }
        }

        /// Appends a clone of `value` to `self`. Same clone convention as
        /// `set_value`.
        pub fn push(&mut self, value: &JsonValue) -> bool {
            match self.0.as_array_mut() {
                Some(arr) => {
                    arr.push(value.0.clone());
                    true
                }
                None => false,
            }
        }
    }
}
