#[diplomat::bridge]
mod ffi {
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
    }
}
