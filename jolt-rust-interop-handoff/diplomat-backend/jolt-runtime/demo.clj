(ns demo
  "PLAN.md 'done' criterion: constructs a Thingy, calls a writeable-returning
  method, passes a by-value options struct, passes a slice, and triggers the
  error path — no manual FFI code outside generated files + diplomat.runtime."
  (:require [diplomat.thingy :as thingy]
            [diplomat.runtime :as dr]))

(defn -main [& _]
  (dr/with-opaque [t (thingy/try-create "42")]
    (println "value:" (thingy/value t))
    (println "describe (terse):" (thingy/describe t {:verbose false :scale 1.0}))
    (println "describe (verbose):" (thingy/describe t {:verbose true :scale 2.5}))
    (println "sum-with [1 2 3]:" (thingy/sum-with t [1 2 3])))

  (try
    (thingy/try-create "not a number")
    (catch clojure.lang.ExceptionInfo e
      (println "error path ok:" (ex-data e)))))
