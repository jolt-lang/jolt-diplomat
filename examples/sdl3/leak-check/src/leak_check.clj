(ns leak-check
  "sdl3_capi is unlike every other example in this repo: SdlApp::create
  opens a REAL OS window and AudioStream::open grabs the REAL default
  playback audio device — not a cheap in-process value. Looping either
  at this repo's usual 20000 iterations would repeatedly create/destroy
  real windows and grab/release real audio hardware at volume, which is
  disruptive (visible flashing, audible clicks) and untested at that
  scale. So: a SMALL iteration count (ITERATIONS below), run once,
  meant to be observed directly rather than launched blind — see
  rust-jolt-ef3.9's design notes.

  Also unlike every other example: this crate has a KNOWN, tracked bug
  (rust-jolt-6xc) — SdlApp has no Drop impl, so a loaded TTF font is
  never closed and TTF_Init is never balanced with TTF_Quit. Calling
  load_font in this loop would therefore leak by design, not prove
  anything about the detector. This positive check exercises only what
  IS currently correct (create/destroy of the base window+canvas+event
  pump, and AudioStream open/put_samples/destroy) and explicitly does
  NOT call load_font — see leak_check_font_bug.clj for a small,
  separately-labeled demonstration of the known font leak, which is
  expected to show nonzero leaked bytes until rust-jolt-6xc is fixed.

  Loads the mem-trace build directly (not dr/load!, which assumes the
  plain <lib>/target/release/... + lib<lib>_shim.dylib layout the
  consumable build uses) — see bind.clj's --out-suffix/CARGO_TARGET_DIR
  handling and rust-jolt-ef3.1.")

(require '[jolt.host :as host])
(require '[jolt.ffi :as ffi])
(require '[diplomat.runtime :as dr])

(def demo-dir (str (host/getenv "PWD") "/.."))
(ffi/load-library (str demo-dir "/sdl3_capi/target-traced/release/libsdl3_capi.dylib"))
(ffi/load-library (str demo-dir "/libsdl3_capi-traced_shim.dylib"))
(require '[diplomat.alloc-stats :as as])
(require '[diplomat.sdl-app :as app])
(require '[diplomat.audio-stream :as audio])

(defn live-bytes []
  (- (as/bytes-allocated) (as/bytes-deallocated)))

;; Small on purpose — see docstring. Each iteration visibly opens and
;; closes a real window; expect brief flashing while this runs.
(def iterations 30)

(defn round-window []
  (dr/with-opaque [a (app/create "leak-check" 320 240)]
    (app/set-draw-color a 0 0 0 255)
    (app/clear a)
    (app/fill-rect a 10.0 10.0 50.0 50.0)
    (app/present a)
    (app/window-width a)
    (app/window-height a)
    (app/poll-event a)))

(defn round-audio []
  (dr/with-opaque [s (audio/open 44100)]
    (audio/put-samples s (vec (repeat 256 (float 0.0))))
    (audio/queued-bytes s)))

(defn -main [& _]
  (println "=== sdl3 leak-check (small volume — see docstring) ===")
  (println "iterations:" iterations)
  (dotimes [_ 2] (round-window) (round-audio)) ;; warm up
  (let [baseline (live-bytes)]
    (println "live bytes after warm-up:" baseline)
    (dotimes [i iterations]
      (round-window)
      (round-audio)
      (println "  iteration" (inc i) "done, live bytes:" (live-bytes)))
    (let [after (live-bytes)
          leaked (- after baseline)]
      (println "live bytes after" iterations "iterations:" after)
      (println "leaked:" leaked "bytes (" (format "%.3f" (double (/ leaked iterations))) "bytes/iteration )")
      (if (pos? leaked)
        (println "SUSPECT LEAK:" leaked "bytes never deallocated across" iterations "iterations")
        (println "no leak detected: every byte allocated was deallocated")))))
