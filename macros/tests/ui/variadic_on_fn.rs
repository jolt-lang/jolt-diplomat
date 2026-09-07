use jolt_diplomat_macros::variadic;

struct Printer;

impl Printer {
    #[variadic]
    pub fn print(&self, _count: u32) {}
}

fn main() {}
