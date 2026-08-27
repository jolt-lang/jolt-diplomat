(ns demo
  (:require [diplomat.runtime :as dr]
            [diplomat.tunes-mixer :as tunes]
            [diplomat.audio-stream :as audio]
            [jolt.ffi :as ffi]))

(require '[jolt.host :as host])
(def tunes-dir (str (host/getenv "PWD") "/.."))
(def sdl3-dir  (str (host/getenv "PWD") "/../../sdl3"))
(dr/load! sdl3-dir "sdl3_capi")
(dr/load! tunes-dir "tunes_capi")

(def SAMPLE-RATE 44100.0)
(def BPM 120.0)
(def SEC-PER-BEAT (/ 60.0 BPM))

(defn midi->hz [midi]
  (* 440.0 (Math/pow 2.0 (/ (- midi 69.0) 12.0))))

;; C major scale: C4 D E F G A B C5, then C major chord
(def SCALE [60 62 64 65 67 69 71 72])

(defn build-melody [mixer]
  (doseq [[i pitch] (map-indexed vector SCALE)]
    (tunes/add-note mixer (midi->hz pitch) (* i SEC-PER-BEAT) SEC-PER-BEAT :sine))
  (let [chord-t (* (count SCALE) SEC-PER-BEAT)]
    (doseq [pitch [60 64 67]]
      (tunes/add-note mixer (midi->hz pitch) chord-t (* 2 SEC-PER-BEAT) :sine))))

;; render-into writes into a native buffer — use raw FFI to read it back
(ffi/defcfn ^:private c-render-into
  "jolt_tunes_TunesMixer_render_into_mv1"
  [:pointer :pointer :size_t :float] :void)

(defn render-offline [mixer sample-rate]
  (let [n (tunes/render-buffer-size mixer sample-rate)
        ptr (ffi/alloc (* n 4))]  ; 4 bytes per f32
    (try
      (c-render-into (:ptr mixer) ptr n sample-rate)
      (vec (for [i (range n)]
             (ffi/read ptr :float (* i 4))))
      (finally (ffi/free ptr)))))

(defn play-buffer [aud samples]
  (let [chunk 2048
        n (count samples)]
    (loop [i 0]
      (when (< i n)
        (let [end (min (+ i chunk) n)]
          (loop []
            (when (> (audio/queued-bytes aud) (* chunk 4 8))
              (Thread/sleep 5)
              (recur)))
          (audio/put-samples aud (subvec samples i end))
          (recur end))))))

(defn run []
  (dr/with-opaque [mixer (tunes/new BPM)]
    (build-melody mixer)
    (let [dur (tunes/total-duration mixer)]
      (println (str "Rendering " (format "%.2f" dur) "s..."))
      (let [samples (render-offline mixer SAMPLE-RATE)]
        (println (str "Done (" (count samples) " samples). Playing..."))
        (dr/with-opaque [aud (audio/open (int SAMPLE-RATE))]
          (play-buffer aud samples)
          (loop []
            (when (> (audio/queued-bytes aud) 0)
              (Thread/sleep 20)
              (recur)))
          (println "Done."))))))

(run)
