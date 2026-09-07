(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "semver_capi")
(require '[diplomat.version :as v])
(require '[diplomat.version-req :as vr])

(defn -main [& _]
  (println "=== semver via Diplomat + Jolt ===")

  (dr/with-opaque [ver (v/parse "1.2.3")]
    (println "major:" (v/major ver))
    (println "minor:" (v/minor ver))
    (println "patch:" (v/patch ver))
    (println "pre:"   (v/pre ver))
    (println "str:"   (String. (v/to-string ver))))

  (dr/with-opaque [ver (v/parse "2.0.0-alpha.1")]
    (println "prerelease?" (not= 0 (v/is-prerelease ver)))
    (println "pre tag:"    (some-> (v/pre ver) String.)))

  ;; VersionReq matching
  (dr/with-opaque [req (vr/parse ">=1.0, <2.0")]
    (dr/with-opaque [v1 (v/parse "1.5.0")]
      (println "1.5.0 matches >=1.0,<2.0:" (not= 0 (vr/matches req v1))))
    (dr/with-opaque [v2 (v/parse "2.0.0")]
      (println "2.0.0 matches >=1.0,<2.0:" (not= 0 (vr/matches req v2)))))

  ;; Error path
  (try
    (v/parse "not-a-version")
    (catch clojure.lang.ExceptionInfo e
      (println "parse error:" (ex-message e)))))
