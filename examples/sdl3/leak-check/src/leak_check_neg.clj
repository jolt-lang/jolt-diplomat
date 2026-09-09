(ns leak-check-neg
  "Negative control for sdl3 — deliberately leaks SdlApp handles (no
  close!) to prove the detector actually fires on a real leak.

  Unlike every other example, this one CANNOT loop create-without-close
  more than once: sdl3::EventPump is a process-wide singleton the sdl3
  crate itself enforces ('an `EventPump` instance is already alive -
  there can only be one `EventPump` in use at a time') — confirmed by
  running this against a naive 30-iteration loop, which threw that
  exact error on the SECOND create call, since the first SdlApp (and
  its EventPump) was never closed. That failure IS itself a correct,
  detectable symptom of the leak — a properly-closed loop (see
  leak_check.clj) never hits it — but it means AllocStats can only be
  read from exactly one leaked create, not accumulated across N.

  So this file does ONE leaked create (proving both: (a) the singleton
  guard fires, confirming SdlApp was never released, and (b) AllocStats
  shows nonzero live bytes for that one leaked instance) rather than
  pretending a multi-iteration loop is possible here.

  Loads the mem-trace build directly — see leak_check.clj's docstring.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/sdl3_capi/target-traced/release/libsdl3_capi.dylib"))
(ffi/load-library (str demo-dir "/libsdl3_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.sdl-app :as app])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

(defn -main [& _]
  (println "=== sdl3 leak-check (negative control: single intentional leak) ===")
  (let [baseline (live-bytes)]
    (println "live bytes before leak:" baseline)
    (let [a (app/create "leak-check-neg" 320 240)] ;; no with-opaque, no close! — leaks the Rust-side Box<SdlApp>
      (app/window-width a))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after one leaked SdlApp:" after)
      (println "leaked:" leaked "bytes")
      (if (pos? leaked)
        (println "SUSPECT LEAK:" leaked "bytes never deallocated for the leaked SdlApp")
        (println "no leak detected — unexpected, investigate")))
    (println)
    (println "Confirming the leak's OTHER symptom: a second create should now fail")
    (println "because the first SdlApp's EventPump was never released...")
    (try
      (app/create "should fail" 320 240)
      (println "UNEXPECTED: second create succeeded — EventPump singleton guard did not fire")
      (catch clojure.lang.ExceptionInfo e
        (println "confirmed:" (ex-message e))))))
