(ns demo)

(def demo-dir "/Users/andrzejfricze/rust-jolt/jolt-rust-interop-handoff/diplomat-backend/regex-demo")

(require '[jolt.ffi :as ffi])
(ffi/load-library (str demo-dir "/regex-capi/target/release/libregex_capi.dylib"))
(ffi/load-library (str demo-dir "/libregex_shim.dylib"))

(require '[diplomat.runtime :as dr])
(require '[diplomat.regex :as rx])

(defn -main [& _]
  (println "=== regex via Diplomat + Jolt ===")

  (dr/with-opaque [re (rx/create "\\d+")]
    (println "is-match '42':"     (rx/is-match re "42"))
    (println "is-match 'hello':"  (rx/is-match re "hello"))
    (println "find in 'abc 123':" (some-> (rx/find re "abc 123") String.))
    (println "replace-all:"       (String. (rx/replace-all re "a1 b2 c3" "NUM"))))

  ;; Nullable find returns nil on no match
  (dr/with-opaque [re (rx/create "[A-Z]+")]
    (println "find uppercase in 'hello':" (rx/find re "hello"))
    (println "find uppercase in 'HELLO':" (some-> (rx/find re "HELLO") String.)))

  ;; Error path: invalid pattern
  (try
    (rx/create "[invalid")
    (catch clojure.lang.ExceptionInfo e
      (println "bad pattern message:" (ex-message e))
      (println "bad pattern data:"    (ex-data e)))))
