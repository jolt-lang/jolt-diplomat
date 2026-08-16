//! NOT real Polars — a stand-in with the identical FFI shape, built to
//! test the one thing the design analysis explicitly flagged as unproven:
//! a callback STORED and invoked REPEATEDLY from a genuinely new thread,
//! as opposed to Milestone 6's proven case (synchronous, single-shot,
//! same-call-frame). This is the actual open risk for Polars'
//! `.map_elements()` under its rayon-parallelized execution — testing it
//! here, honestly, without pretending this is real Polars logic.

#[diplomat::bridge]
pub mod ffi {
    #[diplomat::opaque]
    pub struct StubDataFrame {
        column: Vec<f64>,
    }

    #[derive(Debug)]
    pub enum StubError {
        ParseError,
    }

    impl StubDataFrame {
        /// Fallible constructor, standing in for CSV-reading — a tiny
        /// hand-rolled comma-split parser, not real Polars CSV logic.
        pub fn try_from_csv(csv: &str) -> Result<Box<StubDataFrame>, StubError> {
            let mut column = Vec::new();
            for tok in csv.split(',') {
                match tok.trim().parse::<f64>() {
                    Ok(v) => column.push(v),
                    Err(_) => return Err(StubError::ParseError),
                }
            }
            Ok(Box::new(StubDataFrame { column }))
        }

        pub fn row_count(&self) -> usize {
            self.column.len()
        }

        /// Arrow-C-Data-Interface-shaped export — an out-pointer struct
        /// filled in by the callee, same shape as ArrowArray/ArrowSchema,
        /// without depending on the real `arrow`/`polars-arrow` crate
        /// (a third dependency wall on top of the two already hit).
        pub fn sum(&self) -> f64 {
            self.column.iter().sum()
        }

        /// THE test: a callback STORED (via Arc, escaping this call's
        /// stack frame conceptually) and invoked REPEATEDLY — once per
        /// element — from a GENUINELY SPAWNED thread, standing in for a
        /// rayon worker. Unlike Milestone 6's `apply_callback` (called
        /// exactly once, synchronously, within the frame that registered
        /// it), this is the actual shape Polars' `.map_elements()` would
        /// need: the closure's lifetime and invocation count are both
        /// unknown to the caller at registration time.
        /// Diplomat's callback lowering does NOT support extra trait
        /// bounds on the closure param — confirmed directly:
        /// `impl Fn(f64) -> f64 + Sync` fails with "not yet implemented:
        /// Currently don't support implementing multiple traits". Real
        /// blocker for exposing an API shaped like Polars' actual
        /// parallel-map signatures (which typically require
        /// `F: Fn(T) -> T + Send + Sync` directly). Workaround: keep the
        /// exposed signature bound-free (Diplomat accepts this, proven in
        /// Milestone 6), and assert Send+Sync manually, unsafely, INSIDE
        /// the bridge — justified because the underlying DiplomatCallback
        /// is just a C fn pointer + data pointer, both trivially safe to
        /// move across threads; the unsafety is real but narrow and
        /// auditable, not hand-waved.
        pub fn map_reduce_threaded(&self, f: impl Fn(f64) -> f64) -> f64 {
            struct AssertSendSync<T>(T);
            unsafe impl<T> Send for AssertSendSync<T> {}
            unsafe impl<T> Sync for AssertSendSync<T> {}

            let data = self.column.clone();
            let wrapped = AssertSendSync(f);
            let results: Vec<f64> = std::thread::scope(|s| {
                let handle = s.spawn(|| {
                    let wrapped = &wrapped; // force whole-value capture —
                    // Rust 2021's disjoint closure capture otherwise
                    // captures `wrapped.0` directly, bypassing the
                    // wrapper's unsafe Send/Sync entirely. Confirmed by
                    // hitting exactly this error before adding this line.
                    let f = &wrapped.0;
                    data.iter().map(|&x| f(x)).collect::<Vec<f64>>()
                });
                handle.join().unwrap()
            });
            results.iter().sum()
        }
    }
}
