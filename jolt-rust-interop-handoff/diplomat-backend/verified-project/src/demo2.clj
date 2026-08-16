(require '[jolt.ffi :as ffi])
(ffi/load-library "/home/claude/generated-build/libgenerated.so")
(require '[diplomat.thingy :as thingy])
(require '[diplomat.runtime :as dr])

(let [t (thingy/try-create "42")]
  (println :ptr (:ptr t))
  (println (thingy/describe t {:verbose false :scale 1.0})))
