#[diplomat::bridge]
#[diplomat::abi_rename = "sv_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct Version(semver::Version);

    #[diplomat::opaque]
    pub struct VersionReq(semver::VersionReq);

    #[diplomat::opaque]
    pub struct ParseError(String);

    impl ParseError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Version {
        pub fn parse(text: &str) -> Result<Box<Version>, Box<ParseError>> {
            semver::Version::parse(text)
                .map(|v| Box::new(Version(v)))
                .map_err(|e| Box::new(ParseError(e.to_string())))
        }

        pub fn major(&self) -> u64 { self.0.major }
        pub fn minor(&self) -> u64 { self.0.minor }
        pub fn patch(&self) -> u64 { self.0.patch }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }

        pub fn is_prerelease(&self) -> bool {
            !self.0.pre.is_empty()
        }

        pub fn pre(&self, write: &mut DiplomatWrite) -> Option<()> {
            if self.0.pre.is_empty() { return None; }
            let _ = write.write_str(self.0.pre.as_str());
            Some(())
        }
    }

    impl VersionReq {
        pub fn parse(text: &str) -> Result<Box<VersionReq>, Box<ParseError>> {
            semver::VersionReq::parse(text)
                .map(|r| Box::new(VersionReq(r)))
                .map_err(|e| Box::new(ParseError(e.to_string())))
        }

        pub fn matches(&self, version: &Version) -> bool {
            self.0.matches(&version.0)
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }
    }
}
