(ns demo)

(require '[jolt.ffi :as ffi])
(require '[jolt.host :as host])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "callback_capi")

(require '[diplomat.reducer :as r])

(defn -main [& _]
  (println "=== callback via Diplomat + Jolt ===")

  (println "reduce [1 2 3 4 5] with +:" (r/reduce [1 2 3 4 5] +))
  (println "apply-twice 3 with inc:" (r/apply-twice 3 inc))
  (println "reduce [10 3] with -:" (r/reduce [10 3] -))

  ;; A stateful closure, not a bare fn — exercises the real usage shape:
  ;; each call constructs a fresh foreign-callable pair (dr/reducer.clj's
  ;; f-run-cb/f-destructor), and Diplomat's destructor callback frees them
  ;; once the C call returns. Ran at volume to check that repeated
  ;; construct/call/free cycles don't crash, corrupt, or leak observably.
  (let [call-count (atom 0)]
    (dotimes [i 20000]
      (let [offset i]
        (r/apply-twice 1 (fn [x] (swap! call-count inc) (+ x offset)))))
    (println "call-count after 20000 stateful-closure iterations:" @call-count)
    (println "expected 40000 (apply-twice invokes f twice per call):"
              (if (= @call-count 40000) "PASS" "FAIL"))))
