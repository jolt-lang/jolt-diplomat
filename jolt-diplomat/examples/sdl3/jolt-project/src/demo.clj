(ns demo
  (:require [diplomat.runtime :as dr]
            [diplomat.sdl-app :as app]
            [diplomat.audio-stream :as audio]))

(require '[jolt.host :as host])
(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "sdl3_capi")

;; ── constants ──────────────────────────────────────────────────────────────
(def W 1200)
(def H 700)
(def FONT "/System/Library/Fonts/SFNSMono.ttf")

(def KEY-W 128)
(def NOTE-H 14)
(def HEADER-H 30)
(def BEAT-W 60)
(def BEATS 64)

(def TOP-NOTE 107)
(def BOT-NOTE 12)

(def EVT-NONE 0)
(def EVT-QUIT 1)
(def EVT-KEYDN 2)
(def EVT-KEYUP 3)
(def EVT-MMOVE 4)
(def EVT-MDOWN 5)
(def EVT-MUP 6)

(def KC-RIGHT 1073741903)
(def KC-LEFT 1073741904)
(def KC-UP 1073741906)
(def KC-DOWN 1073741905)
(def KC-PLUS 61)
(def KC-MINUS 45)
(def KC-SPACE 32)

(def MB-LEFT 1)
(def MB-RIGHT 3)

;; ── audio ──────────────────────────────────────────────────────────────────
(def SAMPLE-RATE 44100)
(def CHANNELS 2)
(def FRAME-SAMPLES 256)
(def MAX-QUEUED-BYTES (* FRAME-SAMPLES CHANNELS 4 2))  ; keep ≤2 frames ahead

(defn midi->hz [midi]
  (* 440.0 (Math/pow 2.0 (/ (- midi 69.0) 12.0))))

;; per-voice state: {:freq :phase :vel}
(defn make-voice [pitch]
  {:freq (midi->hz pitch) :phase 0.0 :vel 0.8})

(defn synth-frame
  "Generate FRAME-SAMPLES stereo f32 frames for all active voices.
   Returns [samples updated-voices]."
  [voices]
  (let [n (* FRAME-SAMPLES CHANNELS)
        buf (float-array n 0.0)
        dt (/ 1.0 SAMPLE-RATE)
        voices' (mapv (fn [{:keys [freq phase vel] :as v}]
                        (let [phase-step (* freq dt 2.0 Math/PI)]
                          (dotimes [i FRAME-SAMPLES]
                            (let [s (* vel (Math/sin (+ phase (* i phase-step))))
                                  idx (* i CHANNELS)]
                              (aset buf idx (float (+ (aget buf idx) s)))
                              (aset buf (inc idx) (float (+ (aget buf (inc idx)) s)))))
                          (assoc v :phase (mod (+ phase (* FRAME-SAMPLES phase-step))
                                               (* 2.0 Math/PI)))))
                      voices)]
    ;; normalize if clipping
    (let [peak (reduce max 0.0 (map #(Math/abs %) buf))]
      (when (> peak 1.0)
        (dotimes [i n] (aset buf i (float (/ (aget buf i) peak))))))
    [(vec buf) voices']))

;; ── helpers ────────────────────────────────────────────────────────────────
(def NOTE-NAMES ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn note-name [midi]
  (str (nth NOTE-NAMES (mod midi 12)) (quot midi 12)))

(defn black-key? [midi]
  (contains? #{1 3 6 8 10} (mod midi 12)))

(defn note->y [midi scroll-note]
  (+ HEADER-H (* (- TOP-NOTE midi) NOTE-H) (- (* scroll-note NOTE-H))))

(defn beat->x [beat scroll-beat zoom]
  (+ KEY-W (* (- beat scroll-beat) BEAT-W zoom)))

(defn x->beat [x scroll-beat zoom]
  (+ scroll-beat (/ (- x KEY-W) (* BEAT-W zoom))))

(defn y->note [y scroll-note]
  (int (- TOP-NOTE (quot (- y HEADER-H (- (* scroll-note NOTE-H))) NOTE-H))))

(defn snap [beat]
  (/ (Math/round (* beat 2.0)) 2.0))

(defn note-at [notes mx my scroll-beat scroll-note zoom]
  (let [beat (x->beat mx scroll-beat zoom)
        pitch (y->note my scroll-note)]
    (first (filter (fn [{b :beat d :dur p :pitch}]
                     (and (= p pitch)
                          (>= beat b)
                          (< beat (+ b d))))
                   notes))))

;; ── drawing ────────────────────────────────────────────────────────────────
(defn draw-piano-keys [ctx scroll-note]
  (doseq [midi (range BOT-NOTE (inc TOP-NOTE))]
    (let [y (note->y midi scroll-note)
          bk (black-key? midi)]
      (when (and (>= y HEADER-H) (< y H))
        (if bk
          (app/set-draw-color ctx 40 40 40 255)
          (app/set-draw-color ctx 220 220 220 255))
        (app/fill-rect ctx 0.0 (float y) (float KEY-W) (float NOTE-H))
        (app/set-draw-color ctx 100 100 100 255)
        (app/draw-rect ctx 0.0 (float y) (float KEY-W) (float NOTE-H))
        (when (= 0 (mod midi 12))
          (app/draw-text ctx (note-name midi) 4.0 (float (+ y 1)) 30 30 30 255))))))

(defn draw-grid [ctx scroll-beat scroll-note zoom]
  (app/set-draw-color ctx 28 28 35 255)
  (app/fill-rect ctx (float KEY-W) (float HEADER-H)
                 (float (- W KEY-W)) (float (- H HEADER-H)))
  (doseq [midi (range BOT-NOTE (inc TOP-NOTE))]
    (let [y (note->y midi scroll-note)]
      (when (and (>= y HEADER-H) (< y H))
        (if (black-key? midi)
          (app/set-draw-color ctx 24 24 30 255)
          (app/set-draw-color ctx 35 35 44 255))
        (app/fill-rect ctx (float KEY-W) (float y) (float (- W KEY-W)) (float NOTE-H))
        (when (= 0 (mod midi 12))
          (app/set-draw-color ctx 60 60 80 255)
          (app/draw-line ctx (float KEY-W) (float y) (float W) (float y))))))
  (app/set-draw-color ctx 20 20 28 255)
  (app/fill-rect ctx (float KEY-W) 0.0 (float (- W KEY-W)) (float HEADER-H))
  (doseq [b (range 0 (+ BEATS 2))]
    (let [x (beat->x b scroll-beat zoom)]
      (when (and (>= x KEY-W) (< x W))
        (let [bar? (= 0 (mod b 4))]
          (app/set-draw-color ctx (if bar? 80 50) (if bar? 80 50) (if bar? 100 65) 255)
          (app/draw-line ctx (float x) (float HEADER-H) (float x) (float H))
          (when bar?
            (app/draw-text ctx (str "Bar " (inc (quot b 4))) (float (+ x 3)) 6.0 180 180 200 255)))))))

(defn draw-notes [ctx notes drag scroll-beat scroll-note zoom]
  (doseq [note notes]
    (let [dragging? (and drag (= note (:orig drag)))
          {:keys [pitch beat dur]} (if dragging? (:note drag) note)
          x (beat->x beat scroll-beat zoom)
          y (note->y pitch scroll-note)
          nw (* dur BEAT-W zoom)]
      (when (and (< x W) (> (+ x nw) KEY-W)
                 (>= y HEADER-H) (< y H))
        (app/set-draw-color ctx
                            (if dragging? 120 80)
                            (if dragging? 200 170)
                            (if dragging? 255 255)
                            220)
        (app/fill-rect ctx
                       (float (max x KEY-W)) (float (+ y 1))
                       (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))
        (app/set-draw-color ctx 160 220 255 255)
        (app/draw-rect ctx
                       (float (max x KEY-W)) (float (+ y 1))
                       (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))))))

;; ── state machine ──────────────────────────────────────────────────────────
(defn handle-mdown [notes mx my mb scroll-beat scroll-note zoom]
  (when (and (> mx KEY-W) (> my HEADER-H))
    (let [hit (note-at notes mx my scroll-beat scroll-note zoom)]
      (cond
        (= mb MB-RIGHT)
        (when hit {:notes (vec (remove #(= % hit) notes))})

        (and (= mb MB-LEFT) hit)
        (let [offset (- (x->beat mx scroll-beat zoom) (:beat hit))]
          {:drag {:orig hit :note hit :offset offset} :notes notes})

        (= mb MB-LEFT)
        (let [beat (snap (x->beat mx scroll-beat zoom))
              pitch (y->note my scroll-note)]
          (when (and (>= pitch BOT-NOTE) (<= pitch TOP-NOTE) (>= beat 0))
            {:notes (conj notes {:pitch pitch :beat beat :dur 1})}))))))

(defn handle-mmove [drag mx my scroll-beat scroll-note zoom]
  (when drag
    (let [raw-beat (- (x->beat mx scroll-beat zoom) (:offset drag))
          new-beat (max 0 (snap raw-beat))
          new-pitch (-> (y->note my scroll-note)
                        (max BOT-NOTE)
                        (min TOP-NOTE))
          updated (assoc (:note drag) :beat new-beat :pitch new-pitch)]
      {:drag (assoc drag :note updated)})))

(defn handle-mup [drag notes]
  (when drag
    {:drag nil
     :notes (vec (map #(if (= % (:orig drag)) (:note drag) %) notes))}))

;; ── main loop ─────────────────────────────────────────────────────────────
(defn drain-events [ctx state]
  (let [ev (app/poll-event ctx)
        k (:kind ev)]
    (if (= k EVT-NONE)
      state
      (let [state' (let [{:keys [notes drag held scroll-beat scroll-note zoom]} state]
                     (cond
                       (= k EVT-QUIT) (assoc state :quit true)
                       (= k EVT-KEYDN) (let [kc (:key-code ev)
                                             s (assoc state :held (conj held kc))]
                                         (if (= kc KC-SPACE)
                                           (assoc s :playing (not (:playing state)) :play-beat 0.0 :voices [])
                                           s))
                       (= k EVT-KEYUP) (assoc state :held (disj held (:key-code ev)))
                       (= k EVT-MDOWN) (let [r (handle-mdown notes (:mouse-x ev) (:mouse-y ev)
                                                             (:mouse-button ev) scroll-beat scroll-note zoom)]
                                         (if r (merge state r) state))
                       (= k EVT-MMOVE) (let [r (handle-mmove drag (:mouse-x ev) (:mouse-y ev)
                                                             scroll-beat scroll-note zoom)]
                                         (if r (merge state r) state))
                       (= k EVT-MUP) (let [r (handle-mup drag notes)]
                                       (if r (merge state r) state))
                       :else state))]
        (recur ctx state')))))

(defn tick-held [state]
  (let [{:keys [held scroll-beat scroll-note zoom]} state]
    (assoc state
           :scroll-beat (cond (held KC-RIGHT) (+ scroll-beat 0.25)
                              (held KC-LEFT) (max 0 (- scroll-beat 0.25))
                              :else scroll-beat)
           :scroll-note (cond (held KC-DOWN) (min 80 (+ scroll-note 0.5))
                              (held KC-UP) (max 0 (- scroll-note 0.5))
                              :else scroll-note)
           :zoom (cond (held KC-PLUS) (min 4.0 (* zoom 1.03))
                       (held KC-MINUS) (max 0.2 (* zoom 0.97))
                       :else zoom))))

;; BPM → beats per frame (assuming ~60fps)
(def BPM 120.0)
(def BEATS-PER-FRAME (/ BPM 60.0 60.0))

(defn notes-starting-at [notes beat]
  "Notes whose start beat is within [beat, beat+BEATS-PER-FRAME)."
  (filter (fn [{b :beat}]
            (and (>= beat b) (< beat (+ b BEATS-PER-FRAME))))
          notes))

(defn tick-audio [state aud]
  (if-not (:playing state)
    state
    (let [{:keys [notes play-beat voices]} state
          ;; activate voices for notes starting this frame
          new-voices (map #(make-voice (:pitch %)) (notes-starting-at notes play-beat))
          ;; expire voices whose note has ended
          active (filter (fn [{:keys [freq]}]
                           (some (fn [{b :beat d :dur p :pitch}]
                                   (and (= (midi->hz p) freq)
                                        (< play-beat (+ b d))))
                                 notes))
                         voices)
          all-voices (concat active new-voices)
          [samples voices'] (if (seq all-voices)
                              (synth-frame (vec all-voices))
                              [[] []])
          next-beat (+ play-beat BEATS-PER-FRAME)
          max-beat (apply max 0 (map #(+ (:beat %) (:dur %)) notes))]
      (when (and (seq samples)
                 (< (audio/queued-bytes aud) MAX-QUEUED-BYTES))
        (audio/put-samples aud samples))
      (assoc state
             :voices voices'
             :play-beat (if (>= next-beat max-beat) 0.0 next-beat)
             :playing (< next-beat max-beat)))))

(defn draw-playhead [ctx play-beat scroll-beat zoom]
  (let [x (beat->x play-beat scroll-beat zoom)]
    (when (and (> x KEY-W) (< x W))
      (app/set-draw-color ctx 255 80 80 200)
      (app/draw-line ctx (float x) (float HEADER-H) (float x) (float H)))))

(defn run []
  (dr/with-opaque [ctx (app/create "Piano Roll" W H)]
    (dr/with-opaque [aud (audio/open SAMPLE-RATE)]
      (app/load-font ctx FONT 11)
      (loop [state {:notes [{:pitch 60 :beat 0 :dur 2}
                            {:pitch 64 :beat 2 :dur 1}
                            {:pitch 67 :beat 3 :dur 1}
                            {:pitch 65 :beat 4 :dur 2}
                            {:pitch 60 :beat 6 :dur 2}]
                    :drag nil
                    :held #{}
                    :scroll-beat 0.0
                    :scroll-note 24.0
                    :zoom 1.0
                    :playing false
                    :play-beat 0.0
                    :voices []
                    :quit false}]
        (let [state' (tick-audio (tick-held (drain-events ctx state)) aud)]
          (when-not (:quit state')
            (let [{:keys [notes drag scroll-beat scroll-note zoom playing play-beat]} state']
              (app/set-draw-color ctx 20 20 28 255)
              (app/clear ctx)
              (draw-piano-keys ctx scroll-note)
              (draw-grid ctx scroll-beat scroll-note zoom)
              (draw-notes ctx notes drag scroll-beat scroll-note zoom)
              (when playing
                (draw-playhead ctx play-beat scroll-beat zoom))
              (app/present ctx))
            (recur state')))))))

(run)
