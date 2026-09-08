use tantivy::schema::{Schema, SchemaBuilder, TEXT, STORED, STRING, FAST, INDEXED, NumericOptions};
use tantivy::Index;

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

    #[diplomat::opaque]
    pub struct IndexBuilder(super::BuiltIndex);

    impl IndexBuilder {
        pub fn new_in_ram() -> Box<IndexBuilder> {
            Box::new(IndexBuilder(super::build_index_in_ram()))
        }

        pub fn new_on_disk(path: &str) -> Result<Box<IndexBuilder>, Box<TantivyError>> {
            super::build_index_on_disk(path)
                .map(|i| Box::new(IndexBuilder(i)))
                .map_err(|e| Box::new(TantivyError(e.to_string())))
        }
    }
}

pub(crate) struct BuiltIndex {
    pub schema: Schema,
    pub index:  Index,
}

fn make_schema() -> Schema {
    let mut sb = SchemaBuilder::new();
    sb.add_text_field("title",       TEXT | STORED);
    sb.add_text_field("description", TEXT | STORED);
    sb.add_text_field("category",    STRING | STORED | FAST);
    sb.add_u64_field("price_cents",  NumericOptions::default() | STORED | FAST | INDEXED);
    sb.build()
}

pub(crate) fn build_index_in_ram() -> BuiltIndex {
    let schema = make_schema();
    let index  = Index::create_in_ram(schema.clone());
    BuiltIndex { schema, index }
}

pub(crate) fn build_index_on_disk(path: &str) -> tantivy::Result<BuiltIndex> {
    let schema = make_schema();
    let dir    = tantivy::directory::MmapDirectory::open(path)?;
    let index  = Index::open_or_create(dir, schema.clone())?;
    Ok(BuiltIndex { schema, index })
}
