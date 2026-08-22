#[diplomat::bridge]
#[diplomat::abi_rename = "b64_{0}_mv1"]
mod ffi {
    use base64::Engine as _;
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct Base64Error(String);

    impl Base64Error {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    #[diplomat::opaque]
    pub struct Codec(base64::engine::GeneralPurpose);

    impl Codec {
        pub fn standard() -> Box<Codec> {
            Box::new(Codec(base64::engine::general_purpose::STANDARD))
        }

        pub fn url_safe() -> Box<Codec> {
            Box::new(Codec(base64::engine::general_purpose::URL_SAFE))
        }

        pub fn encode(&self, input: &[u8], write: &mut DiplomatWrite) {
            let encoded = self.0.encode(input);
            let _ = write.write_str(&encoded);
        }

        pub fn decode(&self, input: &str, write: &mut DiplomatWrite) -> Result<(), Box<Base64Error>> {
            let bytes = self.0.decode(input)
                .map_err(|e| Box::new(Base64Error(e.to_string())))?;
            for b in bytes {
                let _ = write.write_char(b as char);
            }
            Ok(())
        }
    }

    #[diplomat::opaque]
    pub struct Hex(());

    impl Hex {
        pub fn new() -> Box<Hex> { Box::new(Hex(())) }

        pub fn encode(input: &[u8], write: &mut DiplomatWrite) {
            let _ = write.write_str(&hex::encode(input));
        }

        pub fn decode(input: &str, write: &mut DiplomatWrite) -> Result<(), Box<Base64Error>> {
            let bytes = hex::decode(input)
                .map_err(|e| Box::new(Base64Error(e.to_string())))?;
            for b in bytes {
                let _ = write.write_char(b as char);
            }
            Ok(())
        }
    }
}
