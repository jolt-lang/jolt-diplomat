#[diplomat::bridge]
#[diplomat::abi_rename = "chrono_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    pub struct DateComponents {
        pub year: i32,
        pub month: u8,
        pub day: u8,
        pub hour: u8,
        pub minute: u8,
        pub second: u8,
    }

    #[diplomat::opaque]
    pub struct DateTime(chrono::DateTime<chrono::Utc>);

    #[diplomat::opaque]
    pub struct DateTimeError(String);

    impl DateTimeError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    impl DateTime {
        pub fn now() -> Box<DateTime> {
            Box::new(DateTime(chrono::Utc::now()))
        }

        pub fn parse(s: &str) -> Result<Box<DateTime>, Box<DateTimeError>> {
            s.parse::<chrono::DateTime<chrono::Utc>>()
                .map(|dt| Box::new(DateTime(dt)))
                .map_err(|e| Box::new(DateTimeError(e.to_string())))
        }

        pub fn from_timestamp(secs: i64) -> Option<Box<DateTime>> {
            chrono::DateTime::from_timestamp(secs, 0).map(|dt| Box::new(DateTime(dt)))
        }

        pub fn to_rfc3339(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0.to_rfc3339());
        }

        pub fn format(&self, fmt: &str, write: &mut DiplomatWrite) -> Option<()> {
            use chrono::format::strftime::StrftimeItems;
            let items: Vec<_> = StrftimeItems::new(fmt).collect();
            if items.iter().any(|i| matches!(i, chrono::format::Item::Error)) {
                return None;
            }
            let _ = write!(write, "{}", self.0.format(fmt));
            Some(())
        }

        pub fn timestamp_secs(&self) -> i64 {
            self.0.timestamp()
        }

        pub fn components(&self) -> DateComponents {
            use chrono::Datelike as _;
            use chrono::Timelike as _;
            DateComponents {
                year: self.0.year(),
                month: self.0.month() as u8,
                day: self.0.day() as u8,
                hour: self.0.hour() as u8,
                minute: self.0.minute() as u8,
                second: self.0.second() as u8,
            }
        }
    }
}
