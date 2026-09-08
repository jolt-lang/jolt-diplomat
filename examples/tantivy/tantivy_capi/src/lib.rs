use tantivy::schema::{SchemaBuilder, Field, TEXT, STORED, STRING, FAST, INDEXED, NumericOptions};
use tantivy::{Index, IndexWriter, IndexReader, TantivyDocument, ReloadPolicy};
use tantivy::collector::TopDocs;
use tantivy::query::QueryParser;
use tantivy::schema::Value as SchemaValue;
use tantivy::snippet::SnippetGenerator;

#[diplomat::bridge]
#[diplomat::abi_rename = "tantivy_{0}_mv1"]
mod ffi {
    use diplomat_runtime::DiplomatWrite;
    use std::fmt::Write as _;

    #[diplomat::opaque]
    pub struct TantivyError(String);

    impl TantivyError {
        pub fn message(&self, write: &mut DiplomatWrite) {
            let _ = write.write_str(&self.0);
        }
    }

    /// Full-text search index over a product catalog.
    #[diplomat::opaque]
    pub struct SearchIndex(super::Inner);

    impl SearchIndex {
        pub fn new_in_ram() -> Box<SearchIndex> {
            Box::new(SearchIndex(super::Inner::new_in_ram()))
        }

        pub fn new_on_disk(path: &str) -> Result<Box<SearchIndex>, Box<TantivyError>> {
            super::Inner::new_on_disk(path)
                .map(|i| Box::new(SearchIndex(i)))
                .map_err(|e| Box::new(TantivyError(e.to_string())))
        }

        pub fn add_product(
            &self,
            title: &str,
            description: &str,
            category: &str,
            price_cents: u64,
        ) -> Result<(), Box<TantivyError>> {
            self.0.add_product(title, description, category, price_cents)
                .map_err(|e| Box::new(TantivyError(e.to_string())))
        }

        /// jolt-diplomat: blocking
        pub fn commit(&self) -> Result<(), Box<TantivyError>> {
            self.0.commit()
                .map_err(|e| Box::new(TantivyError(e.to_string())))
        }

        pub fn doc_count(&self) -> u64 {
            self.0.doc_count()
        }

        pub fn search(
            &self,
            query: &str,
            limit: u32,
        ) -> Result<Box<ResultSet>, Box<TantivyError>> {
            self.0.search(query, limit as usize)
                .map(Box::new)
                .map_err(|e| Box::new(TantivyError(e.to_string())))
        }
    }

    #[diplomat::opaque]
    pub struct ResultSet(pub(super) Vec<super::Hit>);

    impl ResultSet {
        pub fn count(&self) -> u32 {
            self.0.len() as u32
        }

        pub fn get_score(&self, i: u32) -> f32 {
            self.0.get(i as usize).map_or(0.0, |h| h.score)
        }

        pub fn get_title(&self, i: u32, write: &mut DiplomatWrite) {
            if let Some(h) = self.0.get(i as usize) {
                let _ = write.write_str(&h.title);
            }
        }

        pub fn get_description(&self, i: u32, write: &mut DiplomatWrite) {
            if let Some(h) = self.0.get(i as usize) {
                let _ = write.write_str(&h.description);
            }
        }

        pub fn get_category(&self, i: u32, write: &mut DiplomatWrite) {
            if let Some(h) = self.0.get(i as usize) {
                let _ = write.write_str(&h.category);
            }
        }

        pub fn get_price_cents(&self, i: u32) -> u64 {
            self.0.get(i as usize).map_or(0, |h| h.price_cents)
        }

        pub fn get_snippet(&self, i: u32, write: &mut DiplomatWrite) {
            if let Some(h) = self.0.get(i as usize) {
                let _ = write.write_str(&h.snippet);
            }
        }
    }
}

pub(crate) struct Hit {
    pub score:       f32,
    pub title:       String,
    pub description: String,
    pub category:    String,
    pub price_cents: u64,
    pub snippet:     String,
}

pub(crate) struct Inner {
    index:  Index,
    fields: Fields,
    writer: std::sync::Mutex<IndexWriter>,
    reader: IndexReader,
}

struct Fields {
    title:       Field,
    description: Field,
    category:    Field,
    price_cents: Field,
}

fn make_schema() -> (tantivy::schema::Schema, Fields) {
    let mut sb = SchemaBuilder::new();
    let title       = sb.add_text_field("title",       TEXT | STORED);
    let description = sb.add_text_field("description", TEXT | STORED);
    let category    = sb.add_text_field("category",    STRING | STORED | FAST);
    let price_cents = sb.add_u64_field("price_cents",  NumericOptions::default() | STORED | FAST | INDEXED);
    let schema = sb.build();
    (schema, Fields { title, description, category, price_cents })
}

fn open_inner(index: Index, fields: Fields) -> tantivy::Result<Inner> {
    let writer = index.writer(50_000_000)?;
    let reader = index.reader_builder()
        .reload_policy(ReloadPolicy::Manual)
        .try_into()?;
    Ok(Inner { index, fields, writer: std::sync::Mutex::new(writer), reader })
}

impl Inner {
    pub(crate) fn new_in_ram() -> Self {
        let (schema, fields) = make_schema();
        let index = Index::create_in_ram(schema);
        open_inner(index, fields).expect("in-RAM index writer always succeeds")
    }

    pub(crate) fn new_on_disk(path: &str) -> tantivy::Result<Self> {
        let (schema, fields) = make_schema();
        let dir   = tantivy::directory::MmapDirectory::open(path)?;
        let index = Index::open_or_create(dir, schema)?;
        open_inner(index, fields)
    }

    pub(crate) fn add_product(
        &self,
        title: &str,
        description: &str,
        category: &str,
        price_cents: u64,
    ) -> tantivy::Result<()> {
        let mut doc = TantivyDocument::default();
        doc.add_text(self.fields.title,       title);
        doc.add_text(self.fields.description, description);
        doc.add_text(self.fields.category,    category);
        doc.add_u64(self.fields.price_cents,  price_cents);
        self.writer.lock().unwrap().add_document(doc)?;
        Ok(())
    }

    pub(crate) fn commit(&self) -> tantivy::Result<()> {
        self.writer.lock().unwrap().commit()?;
        self.reader.reload()?;
        Ok(())
    }

    pub(crate) fn doc_count(&self) -> u64 {
        self.reader.searcher().num_docs()
    }

    pub(crate) fn search(&self, query_str: &str, limit: usize) -> tantivy::Result<ffi::ResultSet> {
        let searcher = self.reader.searcher();
        let mut parser = QueryParser::for_index(
            &self.index,
            vec![self.fields.title, self.fields.description, self.fields.category],
        );
        parser.set_field_boost(self.fields.title, 2.0);
        let (query, _errs) = parser.parse_query_lenient(query_str);

        let top_docs = searcher.search(&query, &TopDocs::with_limit(limit.max(1)))?;

        let mut snippet_gen = SnippetGenerator::create(
            &searcher, &query, self.fields.description,
        )?;
        snippet_gen.set_max_num_chars(120);

        let mut hits = Vec::with_capacity(top_docs.len());
        for (score, addr) in top_docs {
            let doc: TantivyDocument = searcher.doc(addr)?;
            let title = doc.get_first(self.fields.title)
                .and_then(|v| v.as_str()).unwrap_or("").to_string();
            let description = doc.get_first(self.fields.description)
                .and_then(|v| v.as_str()).unwrap_or("").to_string();
            let category = doc.get_first(self.fields.category)
                .and_then(|v| v.as_str()).unwrap_or("").to_string();
            let price_cents = doc.get_first(self.fields.price_cents)
                .and_then(|v| v.as_u64()).unwrap_or(0);
            let snippet = snippet_gen.snippet_from_doc(&doc).fragment().to_string();
            hits.push(Hit { score, title, description, category, price_cents, snippet });
        }
        Ok(ffi::ResultSet(hits))
    }
}
