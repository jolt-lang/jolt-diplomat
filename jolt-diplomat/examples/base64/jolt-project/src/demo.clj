(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "base64_capi")
(require '[diplomat.codec :as b64])
(require '[diplomat.hex :as hex])

(defn bytes [s] (.getBytes s "UTF-8"))

(defn -main [& _]
  (println "=== base64 + hex via Diplomat + Jolt ===")

  ;; base64 standard
  (dr/with-opaque [codec (b64/standard)]
    (let [encoded (String. (b64/encode codec (bytes "Hello, World!")))]
      (println "encode 'Hello, World!':" encoded)
      (println "decode back:" (String. (b64/decode codec encoded)))))

  ;; url-safe variant
  (dr/with-opaque [codec (b64/url-safe)]
    (let [raw (bytes "data with /+= chars")
          enc (String. (b64/encode codec raw))]
      (println "url-safe encode:" enc)
      (println "url-safe decode:" (String. (b64/decode codec enc)))))

  ;; base64 error path
  (dr/with-opaque [codec (b64/standard)]
    (try
      (b64/decode codec "not!valid!base64!!!")
      (catch clojure.lang.ExceptionInfo e
        (println "decode error:" (ex-message e)))))

  ;; hex
  (let [encoded (String. (hex/encode (bytes "deadbeef")))]
    (println "hex encode 'deadbeef':" encoded)
    (println "hex decode back:" (String. (hex/decode encoded))))

  ;; hex error
  (try
    (hex/decode "xyz")
    (catch clojure.lang.ExceptionInfo e
      (println "hex error:" (ex-message e)))))
