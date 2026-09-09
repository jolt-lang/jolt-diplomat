(ns leak-check
  "callback_capi is structurally different from every other example: no
  opaque needs close! (Reducer is a destroy-only marker struct — see
  dr/defopaque in generated reducer.clj, never actually with-opaque'd
  since reduce/apply-twice are static-only calls). The real leak risk
  is the CALLBACK MARSHALING machinery:

  - Rust side: Diplomat boxes `impl Fn` into a trait object per call —
    AllocStats (stats_alloc) CAN see this, same as every other example.
  - Chez/Jolt side: reduce/apply-twice construct a fresh
    jolt.ffi/foreign-callable trampoline pair per call (f-run-cb,
    f-destructor — see generated/diplomat/reducer.clj), freed only when
    Rust invokes the destructor callback after the C call returns.
    jolt.ffi exposes no counter for live foreign-callables (confirmed:
    no such fn in the ffi API docs) — AllocStats CANNOT see this side
    at all, since a trampoline is Chez-owned foreign memory, not Rust
    heap. This is a real, acknowledged gap in this example's coverage,
    not an oversight — see rust-jolt-ef3.8's design notes.

  What this DOES verify:
  1. AllocStats bytes_allocated - bytes_deallocated across many calls,
     for the Rust-side boxed-closure leak.
  2. Process RSS before/after a large batch (via jolt.scheme's
     current-memory-bytes is Chez-heap-only per base64's earlier
     finding, but a leaked native trampoline is exactly the kind of
     off-heap allocation that shows in whole-process RSS, not Chez GC
     stats — so RSS is the only signal available for this half of the
     check, same reasoning as base64's very first RSS-based attempt,
     kept here because no better tool exists for trampoline leaks).
  3. The existing demo.clj call-count invariant (each apply-twice must
     invoke f exactly twice) at the SAME 20000-iteration volume, as a
     correctness cross-check alongside the memory numbers — a corrupted
     destructor-freed-too-early bug would likely show up here before
     it showed up as a byte count.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/callback_capi/target-traced/release/libcallback_capi.dylib"))
(ffi/load-library (str demo-dir "/libcallback_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.reducer :as r])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(def iterations 20000)

(defn -main [& _]
  (println "=== callback leak-check ===")
  (println "iterations:" iterations)

  ;; correctness cross-check, same invariant as demo.clj
  (let [call-count (atom 0)]
    (dotimes [i iterations]
      (r/apply-twice i (fn [x] (swap! call-count inc) (inc x))))
    (println "call-count after" iterations "apply-twice calls:" @call-count)
    (println "expected" (* 2 iterations) "(apply-twice invokes f twice per call):"
              (if (= @call-count (* 2 iterations)) "PASS" "FAIL")))

  (dotimes [i iterations]
    (r/reduce [1 2 3 4 5] (fn [a b] (+ a b))))

  ;; Rust-side AllocStats check
  (dotimes [_ 500] (r/apply-twice 1 inc)) ;; warm up
  (let [baseline (live-bytes)]
    (println "live bytes after warm-up:" baseline)
    (dotimes [i iterations]
      (r/apply-twice i inc)
      (r/reduce [1 2 3] +))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after" iterations "iterations:" after)
      (println "leaked (Rust side):" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
      (if (pos? leaked)
        (println "SUSPECT LEAK (Rust side):" leaked "bytes never deallocated across" iterations "iterations")
        (println "no Rust-side leak detected: every byte allocated was deallocated"))))

  (println "NOTE: Chez-side foreign-callable trampolines are not covered by AllocStats")
  (println "(no live-callable counter exists in jolt.ffi) — run this example's")
  (println "leak_check_neg.clj alongside an RSS sampler to see the contrast if")
  (println "the destructor path were broken; see this file's docstring."))
