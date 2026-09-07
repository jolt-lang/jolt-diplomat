#[diplomat::bridge]
#[diplomat::abi_rename = "rx_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct Regex(regex::Regex);

    #[diplomat::opaque]
    pub struct RegexError(String);

    impl RegexError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Regex {
        /// Compile a regex pattern. Returns Err with a RegexError on invalid pattern.
        pub fn create(pattern: &str) -> Result<Box<Regex>, Box<RegexError>> {
            regex::Regex::new(pattern)
                .map(|r| Box::new(Regex(r)))
                .map_err(|e| Box::new(RegexError(e.to_string())))
        }

        /// Returns true if the regex matches anywhere in text.
        pub fn is_match(&self, text: &str) -> bool {
            self.0.is_match(text)
        }

        /// Writes the first match to write. Returns false (no output) if no match.
        pub fn find(&self, text: &str, write: &mut DiplomatWrite) -> Option<()> {
            let m = self.0.find(text)?;
            let _ = write.write_str(m.as_str());
            Some(())
        }

        /// Replaces all matches with replacement, writing result to write.
        pub fn replace_all(&self, text: &str, replacement: &str, write: &mut DiplomatWrite) {
            let result = self.0.replace_all(text, replacement);
            let _ = write.write_str(&result);
        }
    }
}
