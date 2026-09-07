use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, Item};

/// Marks a `#[diplomat::bridge]` method as blocking (I/O, locks, sleeps).
///
/// The jolt-diplomat generator emits `:blocking` on the generated `defcfn`,
/// preventing the call from pinning the GC for other threads while it waits.
///
/// # Usage
///
/// ```ignore
/// #[diplomat::bridge]
/// mod ffi {
///     impl MyType {
///         #[jolt_diplomat::blocking]
///         pub fn save(&self, path: &str) -> Result<(), Box<MyError>> { ... }
///     }
/// }
/// ```
#[proc_macro_attribute]
pub fn blocking(_args: TokenStream, input: TokenStream) -> TokenStream {
    let mut item = parse_macro_input!(input as Item);
    prepend_doc(&mut item, "jolt-diplomat: blocking");
    quote!(#item).into()
}

/// Marks a `#[diplomat::bridge]` method as variadic (accepts trailing `:&`).
///
/// The jolt-diplomat generator emits `:&` on the generated `defcfn`.
#[proc_macro_attribute]
pub fn variadic(_args: TokenStream, input: TokenStream) -> TokenStream {
    let mut item = parse_macro_input!(input as Item);
    prepend_doc(&mut item, "jolt-diplomat: variadic");
    quote!(#item).into()
}

fn prepend_doc(item: &mut Item, marker: &str) {
    let attrs = match item {
        Item::Fn(f) => &mut f.attrs,
        Item::Impl(i) => &mut i.attrs,
        Item::Struct(s) => &mut s.attrs,
        Item::Enum(e) => &mut e.attrs,
        _ => return,
    };
    // Insert as the first attribute so it's easy to spot.
    let doc: syn::Attribute = syn::parse_quote!(#[doc = #marker]);
    attrs.insert(0, doc);
}
