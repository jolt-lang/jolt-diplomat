(ns demo
  "End-to-end ICU4X→Jolt demo.")

(def demo-dir "/Users/andrzejfricze/rust-jolt/jolt-rust-interop-handoff/diplomat-backend/icu4x-demo")

(require '[jolt.ffi :as ffi])
(println "Loading dylibs...")
(ffi/load-library (str demo-dir "/icu4x-dylib/target/release/libicu4x.dylib"))
(println "Loaded libicu4x")
(ffi/load-library (str demo-dir "/libicu4x_shim.dylib"))
(println "Loaded libicu4x_shim")

;; Test: can we call the sizeof directly?
(ffi/defcfn test-sizeof "jolt_sizeof_icu4x_Locale_from_string_mv1_result" [] :int)
(println "sizeof result:" (test-sizeof))

(require '[diplomat.runtime :as dr])
(require '[diplomat.locale :as locale])

(defn -main [& _]
  (println "=== ICU4X Locale demo ===")
  (dr/with-opaque [loc (locale/from-string "en-US")]
    (println "to-string:" (String. (locale/to-string loc)))))
