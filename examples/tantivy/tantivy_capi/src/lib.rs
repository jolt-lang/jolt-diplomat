use tantivy::schema::{Schema, SchemaBuilder, Field, TEXT, STORED, STRING, FAST, INDEXED, NumericOptions};
use tantivy::{Index, IndexWriter, IndexReader, TantivyDocument, ReloadPolicy};

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

    /// In-RAM index for demos and tests; use IndexBuilder::new_on_disk for persistence.
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
    }
}

pub(crate) struct Inner {
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

fn make_schema() -> (Schema, Fields) {
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
    Ok(Inner { fields, writer: std::sync::Mutex::new(writer), reader })
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
}
