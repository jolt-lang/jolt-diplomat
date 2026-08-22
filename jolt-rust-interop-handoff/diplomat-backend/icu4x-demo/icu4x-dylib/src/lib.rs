// Re-export icu_capi — its #[no_mangle] C symbols are already there,
// cdylib link just makes them visible to dlopen.
extern crate icu_capi;
