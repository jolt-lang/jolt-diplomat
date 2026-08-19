use interoptopus::ffi;
use interoptopus::{function, inventory};
use interoptopus::ffi::CStrPtr;

/// Convert CommonMark markdown to HTML.
///
/// Writes UTF-8 HTML into `out` (capacity `out_len` bytes).
/// Returns the number of bytes written (excluding NUL terminator).
/// If the buffer is too small, returns the required size — caller can retry.
#[ffi]
pub fn markdown_to_html(input: CStrPtr, out: *mut u8, out_len: u32) -> u32 {
    let s = unsafe { input.as_str() }.unwrap_or("");
    let html = markdown::to_html(s);
    let bytes = html.as_bytes();
    let needed = bytes.len() as u32;
    if out_len as usize >= bytes.len() && !out.is_null() {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    }
    needed
}

pub fn inventory() -> interoptopus::Inventory {
    inventory!(function!(markdown_to_html))
}
