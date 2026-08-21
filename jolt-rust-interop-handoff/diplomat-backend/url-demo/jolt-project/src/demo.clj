(ns demo)

(def demo-dir "/Users/andrzejfricze/rust-jolt/jolt-rust-interop-handoff/diplomat-backend/url-demo")

(require '[jolt.ffi :as ffi])
(ffi/load-library (str demo-dir "/url-capi/target/release/liburl_capi.dylib"))
(ffi/load-library (str demo-dir "/liburl_capi_shim.dylib"))

(require '[diplomat.runtime :as dr])
(require '[diplomat.url :as u])

(defn -main [& _]
  (println "=== url via Diplomat + Jolt ===")

  (dr/with-opaque [url (u/parse "https://user@example.com:8080/path/to/page?foo=bar&baz=qux")]
    (println "scheme:" (String. (u/scheme url)))
    (println "host:"   (some-> (u/host url) String.))
    (println "path:"   (String. (u/path url)))
    (println "query:"  (some-> (u/query url) String.))
    (println "port:"   (u/port url))
    (println "full:"   (String. (u/to-string url))))

  ;; no query, no port
  (dr/with-opaque [url (u/parse "https://example.com/no-query")]
    (println "query (none):" (u/query url))
    (println "port (none):"  (u/port url)))

  ;; no host (data: URL)
  (dr/with-opaque [url (u/parse "data:text/plain,hello")]
    (println "host (data url):" (u/host url))
    (println "scheme (data url):" (String. (u/scheme url))))

  ;; error path
  (try
    (u/parse "not a url at all %%%")
    (catch clojure.lang.ExceptionInfo e
      (println "parse error:" (ex-message e)))))
