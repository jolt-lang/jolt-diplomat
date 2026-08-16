(require '[jolt.ffi :as ffi])
(ffi/load-library "/home/claude/generated-build/libgenerated.so")
(require '[diplomat.thingy :as thingy])
(require '[diplomat.runtime :as dr])
(require '[diplomat.doubled :as doubled])

(dr/with-opaque [t (thingy/try-create "42")]
  (println :value (thingy/value t))
  (println :describe-terse (thingy/describe t {:verbose false :scale 1.0}))
  (println :describe-verbose (thingy/describe t {:verbose true :scale 2.5}))
  (println :sum-with (thingy/sum-with t [1 2 3]))
  (println :sum-with-i32 (thingy/sum-with-i32 t [-100 200 -300])))

(try
  (thingy/try-create "not a number")
  (catch Exception e
    (println :error-path-ok (ex-data e))))

(dr/with-opaque [t2 (thingy/try-create "7")]
  (println :describe2-terse (thingy/describe2 t2 {:mode :terse :scale 0.0}))
  (println :describe2-verbose (thingy/describe2 t2 {:mode :verbose :scale 9.9}))
  (println :describe2-debug (thingy/describe2 t2 {:mode :debug :scale 1.5})))

(dr/with-opaque [t3 (thingy/try-create "99")]
  (println :describe3 (thingy/describe3 t3 {:point {:x 1.5 :y -2.25} :scale 3.0})))

(dr/with-opaque [t4 (thingy/try-create "21")]
  (dr/with-opaque [d (thingy/double t4)]
    (println :doubled-value (doubled/value d))))

(dr/with-opaque [t5 (thingy/try-create "10")]
  (println :apply-callback (thingy/apply-callback t5 (fn [x] (+ x 5)))))
