(ns demo
  (:require [diplomat.runtime :as dr]
            [diplomat.sdl-app :as app]))

(require '[jolt.host :as host])
(def demo-dir (str (host/getenv "PWD") "/.."))
(dr/load! demo-dir "sdl3_capi")

;; ── constants ──────────────────────────────────────────────────────────────
(def W 1200)
(def H 700)
(def FONT "/System/Library/Fonts/SFNSMono.ttf")

(def KEY-W    128)   ; piano keyboard column width
(def NOTE-H   14)    ; px per semitone row
(def HEADER-H 30)    ; bar header height

(def BEAT-W   60)    ; px per beat at zoom=1
(def BEATS    32)    ; visible beats (8 bars × 4)

;; MIDI notes: C8 (top) down to C0 (bottom) — 96 rows
(def TOP-NOTE  107)
(def BOT-NOTE  12)
(def N-NOTES   (- TOP-NOTE BOT-NOTE))   ; 95

(def EVT-NONE  0)
(def EVT-QUIT  1)
(def EVT-KEYDN 2)
(def EVT-KEYUP 3)
(def EVT-MMOVE 4)
(def EVT-MDOWN 5)
(def EVT-MUP   6)

(def KC-ESCAPE 27)
(def KC-PLUS   61)   ; = / +
(def KC-MINUS  45)
(def KC-LEFT   1073741904)
(def KC-RIGHT  1073741903)

;; ── helpers ────────────────────────────────────────────────────────────────
(def NOTE-NAMES ["C" "C#" "D" "D#" "E" "F" "F#" "G" "G#" "A" "A#" "B"])

(defn note-name [midi]
  (let [pc (mod midi 12) oct (quot midi 12)]
    (str (nth NOTE-NAMES pc) oct)))

(defn black-key? [midi]
  (contains? #{1 3 6 8 10} (mod midi 12)))

(defn note->y [midi scroll-note]
  ;; top row = highest pitch
  (+ HEADER-H (* (- TOP-NOTE midi) NOTE-H) (- (* scroll-note NOTE-H))))

(defn beat->x [beat scroll-beat zoom]
  (+ KEY-W (* beat BEAT-W zoom) (- (* scroll-beat BEAT-W zoom))))

(defn x->beat [x scroll-beat zoom]
  (/ (- x KEY-W (* (- scroll-beat) BEAT-W zoom)) (* BEAT-W zoom)))

(defn y->note [y scroll-note]
  (- TOP-NOTE (quot (- y HEADER-H (- (* scroll-note NOTE-H))) NOTE-H)))

;; ── drawing ────────────────────────────────────────────────────────────────
(defn draw-piano-keys [ctx scroll-note]
  (doseq [midi (range BOT-NOTE (inc TOP-NOTE))]
    (let [y (note->y midi scroll-note)
          bk (black-key? midi)]
      (when (and (>= y HEADER-H) (< y H))
        ;; key background
        (if bk
          (app/set-draw-color ctx 40 40 40 255)
          (app/set-draw-color ctx 220 220 220 255))
        (app/fill-rect ctx 0.0 (float y) (float KEY-W) (float NOTE-H))
        ;; key border
        (app/set-draw-color ctx 100 100 100 255)
        (app/draw-rect ctx 0.0 (float y) (float KEY-W) (float NOTE-H))
        ;; C label
        (when (and (= 0 (mod midi 12)) (not bk))
          (app/draw-text ctx (note-name midi) 4.0 (float (+ y 1)) 30 30 30 255))))))

(defn draw-grid [ctx scroll-beat scroll-note zoom]
  ;; background
  (app/set-draw-color ctx 28 28 35 255)
  (app/fill-rect ctx (float KEY-W) (float HEADER-H)
                     (float (- W KEY-W)) (float (- H HEADER-H)))
  ;; horizontal lines per semitone
  (doseq [midi (range BOT-NOTE (inc TOP-NOTE))]
    (let [y (note->y midi scroll-note)]
      (when (and (>= y HEADER-H) (< y H))
        (if (black-key? midi)
          (app/set-draw-color ctx 24 24 30 255)
          (app/set-draw-color ctx 35 35 44 255))
        (app/fill-rect ctx (float KEY-W) (float y) (float (- W KEY-W)) (float NOTE-H))
        ;; C rows brighter border
        (when (= 0 (mod midi 12))
          (app/set-draw-color ctx 60 60 80 255)
          (app/draw-line ctx (float KEY-W) (float y) (float W) (float y))))))
  ;; vertical beat lines + bar header
  (app/set-draw-color ctx 20 20 28 255)
  (app/fill-rect ctx (float KEY-W) 0.0 (float (- W KEY-W)) (float HEADER-H))
  (doseq [b (range 0 (+ BEATS 2))]
    (let [x (beat->x b scroll-beat zoom)]
      (when (and (>= x KEY-W) (< x W))
        (let [bar? (= 0 (mod b 4))]
          (app/set-draw-color ctx (if bar? 80 50) (if bar? 80 50) (if bar? 100 65) 255)
          (app/draw-line ctx (float x) (float HEADER-H) (float x) (float H))
          (when bar?
            (app/draw-text ctx (str (inc (quot b 4))) (float (+ x 3)) 6.0 180 180 200 255)))))))

(defn draw-notes [ctx notes scroll-beat scroll-note zoom]
  (doseq [{:keys [pitch beat dur]} notes]
    (let [x  (beat->x beat scroll-beat zoom)
          y  (note->y pitch scroll-note)
          nw (* dur BEAT-W zoom)]
      (when (and (< x W) (> (+ x nw) KEY-W)
                 (>= y HEADER-H) (< y H))
        (app/set-draw-color ctx 80 170 255 220)
        (app/fill-rect ctx (float (max x KEY-W)) (float (+ y 1))
                           (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))
        (app/set-draw-color ctx 140 210 255 255)
        (app/draw-rect ctx (float (max x KEY-W)) (float (+ y 1))
                           (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))))))

;; ── event loop ─────────────────────────────────────────────────────────────
(defn note-at [notes beat pitch]
  (first (filter (fn [{b :beat d :dur p :pitch}]
                   (and (= p pitch) (>= beat b) (< beat (+ b d))))
                 notes)))

(defn process-events [ctx held scroll-beat scroll-note zoom notes]
  (let [ev (app/poll-event ctx)
        k  (:kind ev)]
    (cond
      (= k EVT-NONE)
      {:quit false :held held :sb scroll-beat :sn scroll-note :zoom zoom :notes notes :mx (:mouse-x ev) :my (:mouse-y ev)}

      (= k EVT-QUIT) {:quit true :held held :sb scroll-beat :sn scroll-note :zoom zoom :notes notes}

      (= k EVT-KEYDN)
      (let [kc (:key-code ev)
            held' (conj held kc)]
        (recur ctx held' scroll-beat scroll-note zoom notes))

      (= k EVT-KEYUP)
      (recur ctx (disj held (:key-code ev)) scroll-beat scroll-note zoom notes)

      (= k EVT-MDOWN)
      (let [mx (:mouse-x ev) my (:mouse-y ev)]
        (if (and (> mx KEY-W) (> my HEADER-H))
          (let [beat  (int (x->beat mx scroll-beat zoom))
                pitch (y->note my scroll-note)
                hit   (note-at notes beat pitch)
                notes' (if hit
                         (remove #(= % hit) notes)
                         (conj notes {:pitch pitch :beat beat :dur 1}))]
            (recur ctx held scroll-beat scroll-note zoom notes'))
          (recur ctx held scroll-beat scroll-note zoom notes)))

      :else (recur ctx held scroll-beat scroll-note zoom notes))))

(defn tick-scroll [held sb sn]
  [(cond (held KC-RIGHT) (+ sb 0.5) (held KC-LEFT) (max 0 (- sb 0.5)) :else sb)
   sn])

(defn run []
  (dr/with-opaque [ctx (app/create "Piano Roll" W H)]
    (app/load-font ctx FONT 11)
    (loop [held #{} scroll-beat 0.0 scroll-note 24.0 zoom 1.0
           notes [{:pitch 60 :beat 0 :dur 2}
                  {:pitch 64 :beat 2 :dur 1}
                  {:pitch 67 :beat 3 :dur 1}
                  {:pitch 65 :beat 4 :dur 2}
                  {:pitch 60 :beat 6 :dur 2}]]
      (let [ev (process-events ctx held scroll-beat scroll-note zoom notes)]
        (when-not (:quit ev)
          (let [[sb' sn'] (tick-scroll (:held ev) (:sb ev) (:sn ev))
                zoom'  (cond
                         ((:held ev) KC-PLUS)  (min 4.0 (* zoom 1.02))
                         ((:held ev) KC-MINUS) (max 0.25 (* zoom 0.98))
                         :else zoom)]
            (app/set-draw-color ctx 20 20 28 255)
            (app/clear ctx)
            (draw-piano-keys ctx sn')
            (draw-grid ctx sb' sn' zoom')
            (draw-notes ctx (:notes ev) sb' sn' zoom')
            (app/present ctx)
            (recur (:held ev) sb' sn' zoom' (:notes ev))))))))

(run)
