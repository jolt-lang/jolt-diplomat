use jolt_diplomat_macros::blocking;

struct Saver;

impl Saver {
    #[blocking]
    pub fn save(&self, _path: &str) -> u32 {
        0
    }
}

fn main() {}
