(ns markdown
  (:require [jolt.ffi :as ffi]))

(def ^:private dylib
  "/Users/andrzejfricze/rust-jolt/jolt-rust-interop-handoff/diplomat-backend/markdown-demo/markdown-dylib/target/release/libmarkdown_rs.dylib")

(ffi/load-library dylib)

(ffi/defcfn ^:private c-to-html   "markdown_to_html"   [:pointer] :pointer)
(ffi/defcfn ^:private c-free-str  "markdown_free_string" [:pointer] :void)

(defn to-html
  "Convert CommonMark markdown string to HTML string."
  [md]
  (let [in-buf  (ffi/string->ptr md)
        out-ptr (c-to-html in-buf)]
    (try
      (ffi/ptr->string out-ptr)
      (finally
        (c-free-str out-ptr)
        (ffi/free in-buf)))))
