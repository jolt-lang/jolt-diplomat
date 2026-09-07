#[diplomat::bridge]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    // Struct-by-value param — exercises the same flattened-scalar path
    // as ThingyOptions/UrlInfo, applied to pulldown-cmark's Options
    // bitflags (exposed as bools rather than the raw u32, since Diplomat
    // structs are POD-only, no bitflag type).
    pub struct MarkdownOptions {
        pub tables: bool,
        pub strikethrough: bool,
        pub footnotes: bool,
        pub tasklists: bool,
    }

    #[diplomat::opaque]
    pub struct Document {
        source: String,
        heading_count: u32,
        html: String,
    }

    impl Document {
        // Infallible — pulldown-cmark's parser never fails on malformed
        // input, it just produces different events (unlike url/json/regex,
        // which is why this is the one example crate with no Result path).
        // Parses once, up front, rather than re-parsing per accessor call —
        // to_html/heading_count are then just reads of already-computed data.
        pub fn parse(text: &str, opts: MarkdownOptions) -> Box<Document> {
            let options = to_pulldown_options(&opts);
            let parser = pulldown_cmark::Parser::new_ext(text, options);
            let mut html = String::new();
            let mut heading_count = 0u32;
            let events: Vec<_> = parser.collect();
            pulldown_cmark::html::push_html(&mut html, events.iter().cloned());
            for ev in &events {
                if matches!(ev, pulldown_cmark::Event::Start(pulldown_cmark::Tag::Heading { .. })) {
                    heading_count += 1;
                }
            }
            Box::new(Document { source: text.to_string(), heading_count, html })
        }

        pub fn to_html(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.html);
        }

        pub fn heading_count(&self) -> u32 {
            self.heading_count
        }

        pub fn source_len(&self) -> u32 {
            self.source.len() as u32
        }
    }

    fn to_pulldown_options(opts: &MarkdownOptions) -> pulldown_cmark::Options {
        let mut o = pulldown_cmark::Options::empty();
        if opts.tables { o.insert(pulldown_cmark::Options::ENABLE_TABLES); }
        if opts.strikethrough { o.insert(pulldown_cmark::Options::ENABLE_STRIKETHROUGH); }
        if opts.footnotes { o.insert(pulldown_cmark::Options::ENABLE_FOOTNOTES); }
        if opts.tasklists { o.insert(pulldown_cmark::Options::ENABLE_TASKLISTS); }
        o
    }
}
