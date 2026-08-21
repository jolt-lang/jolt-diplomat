(ns demo)

(def demo-dir "/Users/andrzejfricze/rust-jolt/jolt-rust-interop-handoff/diplomat-backend/markdown-demo")

(require '[jolt.ffi :as ffi])
(ffi/load-library (str demo-dir "/markdown-capi/target/release/libmarkdown_capi.dylib"))
(ffi/load-library (str demo-dir "/libmarkdown_capi_shim.dylib"))

(require '[diplomat.runtime :as dr])
(require '[diplomat.markdown :as md])

(defn -main [& _]
  (println "=== markdown-rs via Diplomat + Jolt generator ===")

  ;; to-html is a static fn — no opaque needed, no self param.
  (println (String. (md/to-html "# Hello\n\nThis is **generated** with Diplomat.\n\n- zero hand-written FFI\n- one Rust `#[diplomat::bridge]`\n- one `jolt-diplomat-backend` invocation\n")))

  (println (String. (md/to-html "> it works\n\n```clojure\n(md/to-html input)\n```\n"))))
