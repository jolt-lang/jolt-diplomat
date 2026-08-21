#[diplomat::bridge]
#[diplomat::abi_rename = "json_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    pub enum JsonKind {
        Null,
        Bool,
        Number,
        String,
        Array,
        Object,
    }

    #[diplomat::opaque]
    pub struct JsonError(String);

    impl JsonError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    #[diplomat::opaque]
    pub struct JsonValue(serde_json::Value);

    impl JsonValue {
        pub fn parse(text: &str) -> Result<Box<JsonValue>, Box<JsonError>> {
            serde_json::from_str(text)
                .map(|v| Box::new(JsonValue(v)))
                .map_err(|e| Box::new(JsonError(e.to_string())))
        }

        pub fn kind(&self) -> JsonKind {
            match &self.0 {
                serde_json::Value::Null => JsonKind::Null,
                serde_json::Value::Bool(_) => JsonKind::Bool,
                serde_json::Value::Number(_) => JsonKind::Number,
                serde_json::Value::String(_) => JsonKind::String,
                serde_json::Value::Array(_) => JsonKind::Array,
                serde_json::Value::Object(_) => JsonKind::Object,
            }
        }

        pub fn as_bool(&self) -> Option<bool> {
            self.0.as_bool()
        }

        pub fn as_f64(&self) -> Option<f64> {
            self.0.as_f64()
        }

        pub fn as_str(&self, write: &mut DiplomatWrite) -> Option<()> {
            let s = self.0.as_str()?;
            let _ = write.write_str(s);
            Some(())
        }

        pub fn array_len(&self) -> Option<u64> {
            self.0.as_array().map(|a| a.len() as u64)
        }

        pub fn array_get(&self, index: u64) -> Option<Box<JsonValue>> {
            self.0.as_array()?.get(index as usize).map(|v| Box::new(JsonValue(v.clone())))
        }

        pub fn object_get(&self, key: &str, write: &mut DiplomatWrite) -> Option<()> {
            let v = self.0.as_object()?.get(key)?;
            let _ = write.write_str(&v.to_string());
            Some(())
        }

        pub fn to_string(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_string());
        }
    }
}
