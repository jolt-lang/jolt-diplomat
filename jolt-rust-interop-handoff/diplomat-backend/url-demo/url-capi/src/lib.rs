#[diplomat::bridge]
#[diplomat::abi_rename = "url_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct Url(url::Url);

    #[diplomat::opaque]
    pub struct UrlError(String);

    impl UrlError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl Url {
        pub fn parse(input: &str) -> Result<Box<Url>, Box<UrlError>> {
            url::Url::parse(input)
                .map(|u| Box::new(Url(u)))
                .map_err(|e| Box::new(UrlError(e.to_string())))
        }

        pub fn scheme(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.scheme());
        }

        pub fn host(&self, write: &mut DiplomatWrite) -> Option<()> {
            let h = self.0.host_str()?;
            let _ = write.write_str(h);
            Some(())
        }

        pub fn path(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.path());
        }

        pub fn query(&self, write: &mut DiplomatWrite) -> Option<()> {
            let q = self.0.query()?;
            let _ = write.write_str(q);
            Some(())
        }

        pub fn port(&self) -> Option<u16> {
            self.0.port()
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(self.0.as_str());
        }
    }
}
