use std::ffi::{c_char, CStr, CString};

#[no_mangle]
pub extern "C" fn markdown_to_html(input: *const c_char) -> *mut c_char {
    let s = unsafe { CStr::from_ptr(input) }.to_str().unwrap_or("");
    CString::new(markdown::to_html(s)).unwrap().into_raw()
}

#[no_mangle]
pub extern "C" fn markdown_free_string(s: *mut c_char) {
    if !s.is_null() {
        unsafe { drop(CString::from_raw(s)); }
    }
}
