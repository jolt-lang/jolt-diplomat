(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "markdown_capi")

(require '[diplomat.document :as doc])

(def sample "# Title\n\nSome *text* with a ~~strike~~ and a table:\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n## Second heading\n")

(defn -main [& _]
  (println "=== markdown via Diplomat + Jolt ===")

  ;; extensions off — GFM table/strikethrough syntax passes through literally
  (dr/with-opaque [d (doc/parse sample {:tables false :strikethrough false
                                         :footnotes false :tasklists false})]
    (println "heading-count (plain):" (doc/heading-count d))
    (println "source-len:" (doc/source-len d))
    (println "html (plain, first 120 chars):")
    (println (subs (String. (doc/to-html d)) 0 120)))

  ;; extensions on — table and strikethrough actually render
  (dr/with-opaque [d (doc/parse sample {:tables true :strikethrough true
                                         :footnotes true :tasklists true})]
    (println "heading-count (extended):" (doc/heading-count d))
    (println "html (extended) contains <table>:" (.contains (String. (doc/to-html d)) "<table>"))
    (println "html (extended) contains <del>:" (.contains (String. (doc/to-html d)) "<del>")))

  ;; empty doc — edge case
  (dr/with-opaque [d (doc/parse "" {:tables false :strikethrough false
                                     :footnotes false :tasklists false})]
    (println "empty doc heading-count:" (doc/heading-count d))
    (println "empty doc html:" (pr-str (String. (doc/to-html d))))))
