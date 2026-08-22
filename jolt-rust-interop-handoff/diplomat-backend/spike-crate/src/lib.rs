//! Minimal crate exercising every shape the `jolt` Diplomat backend needs to
//! handle: opaque type + fallible ctor, plain scalar method, string return
//! via DiplomatWriteable, non-opaque struct-by-value param, slice param.
//!
//! Deliberately small. If the backend round-trips all five of these from
//! Jolt, it's ready to point at something real (ICU4X).

#[diplomat::bridge]
pub mod ffi {
    use std::fmt::Write;

    /// Opaque: only ever crosses the boundary behind a pointer. Fields are
    /// private to Jolt; only methods are callable.
    #[diplomat::opaque]
    pub struct Thingy(u8);

    /// A C-like enum for the error path — exercises Result<T, E>.
    #[derive(Debug)]
    pub enum ThingyError {
        ParseError,
    }

    /// A second enum, this time used as a STRUCT FIELD rather than an
    /// error type — the next documented gap from milestone-5-findings.md
    /// ("no enum-valued struct fields").
    #[derive(Debug)]
    pub enum Mode {
        Terse,
        Verbose,
        Debug,
    }

    /// A second options struct exercising an enum field alongside a
    /// plain primitive, to isolate the new shape from the already-proven
    /// bool/double ThingyOptions.
    pub struct ThingyOptions2 {
        pub mode: Mode,
        pub scale: f64,
    }

    /// A plain by-value struct with no enum/opaque fields, used only as
    /// a NESTED field inside another struct below — the next documented
    /// gap ("nested structs").
    pub struct Point {
        pub x: f64,
        pub y: f64,
    }

    /// A struct containing another non-opaque struct as a field.
    pub struct ThingyOptions3 {
        pub point: Point,
        pub scale: f64,
    }

    /// Non-opaque: copied by value across the boundary. This is the
    /// "structs by offset" case — the backend must generate an offset table
    /// for this, not hand-copy one.
    /// (No attribute needed for a plain input-position struct — #[diplomat::out]
    /// is for OUTPUT-only structs, confirmed the hard way against real
    /// diplomat-tool 0.10: "found struct in input that is marked with
    /// #[diplomat::out]". Corrected here.)
    pub struct ThingyOptions {
        pub verbose: bool,
        pub scale: f64,
    }

    /// A separate opaque type, used only as the target of a method that
    /// returns an opaque OTHER than Self — the next documented gap
    /// ("opaque-returning-opaque").
    #[diplomat::opaque]
    pub struct Doubled(u16);

    impl Doubled {
        pub fn value(&self) -> u16 {
            self.0
        }
    }

    impl Thingy {
        /// A callback param — the last documented gap. Rust invokes the
        /// foreign closure synchronously, on the calling thread, once.
        pub fn apply_callback(&self, f: impl Fn(u8) -> u8) -> u8 {
            f(self.0)
        }

        /// Fallible constructor -> Result<Box<Thingy>, ThingyError>.
        pub fn try_create(s: &str) -> Result<Box<Thingy>, ThingyError> {
            s.parse::<u8>()
                .map(Thingy)
                .map(Box::new)
                .map_err(|_| ThingyError::ParseError)
        }

        /// Plain scalar return — the trivial case, should need no marshaling
        /// beyond a single defcfn.
        pub fn value(&self) -> u8 {
            self.0
        }

        /// String return via DiplomatWriteable, plus a by-value struct param.
        pub fn describe(&self, opts: ThingyOptions, w: &mut diplomat_runtime::DiplomatWrite) {
            if opts.verbose {
                let _ = write!(w, "Thingy(value={}, scale={})", self.0, opts.scale);
            } else {
                let _ = write!(w, "{}", self.0);
            }
        }

        /// Slice param — Diplomat copies this at the boundary, not borrows.
        pub fn sum_with(&self, others: &[u8]) -> u32 {
            others.iter().map(|&x| x as u32).sum::<u32>() + self.0 as u32
        }

        /// Non-u8 primitive slice — the generator's documented scope gap
        /// (Milestone 5: "only u8 is wired"). Real Diplomat emits a
        /// DiplomatI32View, not DiplomatU8View, for this.
        pub fn sum_with_i32(&self, others: &[i32]) -> i64 {
            others.iter().map(|&x| x as i64).sum::<i64>() + self.0 as i64
        }

        /// Enum-valued struct field — the next documented gap.
        pub fn describe2(&self, opts: ThingyOptions2, w: &mut diplomat_runtime::DiplomatWrite) {
            use std::fmt::Write as _;
            let _ = match opts.mode {
                Mode::Terse => write!(w, "{}", self.0),
                Mode::Verbose => write!(w, "Thingy(value={}, scale={})", self.0, opts.scale),
                Mode::Debug => write!(w, "Thingy[DEBUG](value={}, mode=Debug, scale={})", self.0, opts.scale),
            };
        }

        /// Nested struct field — the next documented gap after that.
        pub fn describe3(&self, opts: ThingyOptions3, w: &mut diplomat_runtime::DiplomatWrite) {
            use std::fmt::Write as _;
            let _ = write!(
                w,
                "Thingy(value={}, point=({}, {}), scale={})",
                self.0, opts.point.x, opts.point.y, opts.scale
            );
        }

        /// Returns a DIFFERENT opaque type, infallibly — the next
        /// documented gap ("opaque-returning-opaque").
        pub fn double(&self) -> Box<Doubled> {
            Box::new(Doubled(self.0 as u16 * 2))
        }

        /// Struct return by value — exercises the "struct return" generator gap.
        pub fn get_point(&self) -> Point {
            Point { x: self.0 as f64, y: self.0 as f64 * 2.0 }
        }
    }
}
