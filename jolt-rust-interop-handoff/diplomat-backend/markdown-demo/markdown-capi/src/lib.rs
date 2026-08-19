#[diplomat::bridge]
#[diplomat::abi_rename = "md_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct Markdown(());

    impl Markdown {
        /// Returns a Markdown instance (singleton — no state, just a namespace).
        pub fn create() -> Box<Markdown> {
            Box::new(Markdown(()))
        }

        /// Convert CommonMark markdown to HTML, writing into a DiplomatWrite buffer.
        pub fn to_html(input: &str, write: &mut DiplomatWrite) {
            let html = ::markdown::to_html(input);
            let _ = write.write_str(&html);
        }
    }
}
