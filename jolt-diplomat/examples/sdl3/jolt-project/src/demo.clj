(ns demo
  (:require [diplomat.runtime :as dr]
            [diplomat.sdl-app :as app]
            [diplomat.audio-stream :as audio]
            [diplomat.tunes-mixer :as tunes]
            [diplomat.tunes-error :as tunes-error]
            [jolt.ffi :as ffi]))

(require '[jolt.host :as host])
(def sdl3-dir (str (host/getenv "PWD") "/.."))
(def tunes-dir (str (host/getenv "PWD") "/../../tunes"))
(dr/load! sdl3-dir "sdl3_capi")
(dr/load! tunes-dir "tunes_capi")

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
(def KC-E 101)

(def MB-LEFT 1)
(def MB-RIGHT 3)

;; ── audio ──────────────────────────────────────────────────────────────────
(def SAMPLE-RATE 44100)
(def CHANNELS 2)
(def FRAME-SAMPLES 256)
(def MAX-QUEUED-BYTES (* FRAME-SAMPLES CHANNELS 4 8))  ; ~23ms at 44100

(def BPM 120.0)
(def SEC-PER-BEAT (/ 60.0 BPM))

(defn midi->hz [midi]
  (* 440.0 (Math/pow 2.0 (/ (- midi 69.0) 12.0))))

(ffi/defcfn ^:private c-process-block
  "jolt_tunes_TunesMixer_process_block_mv1"
  [:pointer :pointer :size_t :float :float] :void)

(defn process-block! [mixer ptr n sample-rate start-time]
  (c-process-block (:ptr mixer) ptr n sample-rate start-time))

;; bypass with-primitive-buffer — ptr already native, zero copies
(ffi/defcfn ^:private c-put-samples-raw
  "jolt_sdl3_AudioStream_put_samples_mv1"
  [:pointer :pointer :size_t] :void)

(defn put-samples-raw! [aud ptr n]
  (c-put-samples-raw (:ptr aud) ptr n))

;; ── waveform toolbar ───────────────────────────────────────────────────────
(def WAVEFORMS [:sine :square :sawtooth :triangle])
(def WF-LABELS {:sine "Sine" :square "Square" :sawtooth "Saw" :triangle "Tri"})

;; note fill colors [normal-rgb dragging-rgb] per waveform
(def WF-COLORS
  {:sine [[80 170 255] [120 200 255]]
   :square [[80 220 120] [120 255 160]]
   :sawtooth [[220 120 80] [255 160 120]]
   :triangle [[200 80 220] [240 120 255]]})

;; toolbar: 4 buttons, each ~70px wide, starting at x=KEY-W+4
(def BTN-W 68)
(def BTN-H 22)
(def BTN-Y 4)
(def BTN-X0 (+ KEY-W 4))

(defn btn-rect [i]
  [(+ BTN-X0 (* i (+ BTN-W 4))) BTN-Y BTN-W BTN-H])

(defn toolbar-hit [mx my]
  (when (and (>= my BTN-Y) (< my (+ BTN-Y BTN-H)))
    (first (keep-indexed (fn [i wf]
                           (let [[bx _ bw _] (btn-rect i)]
                             (when (and (>= mx bx) (< mx (+ bx bw)))
                               wf)))
                         WAVEFORMS))))

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

(defn draw-toolbar [ctx selected-wf]
  (doseq [[i wf] (map-indexed vector WAVEFORMS)]
    (let [[bx by bw bh] (btn-rect i)
          active? (= wf selected-wf)
          [r g b] (first (WF-COLORS wf))]
      (if active?
        (app/set-draw-color ctx r g b 255)
        (app/set-draw-color ctx 40 40 50 255))
      (app/fill-rect ctx (float bx) (float by) (float bw) (float bh))
      (app/set-draw-color ctx (if active? 255 r) (if active? 255 g) (if active? 255 b) 255)
      (app/draw-rect ctx (float bx) (float by) (float bw) (float bh))
      (app/draw-text ctx (WF-LABELS wf) (float (+ bx 4)) (float (+ by 6))
                     (if active? 10 r)
                     (if active? 10 g)
                     (if active? 10 b)
                     255))))

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
          display (if dragging? (:note drag) note)
          {:keys [pitch beat dur waveform]} display
          wf (or waveform :sine)
          [nr ng nb] (nth (WF-COLORS wf) (if dragging? 1 0))
          x (beat->x beat scroll-beat zoom)
          y (note->y pitch scroll-note)
          nw (* dur BEAT-W zoom)]
      (when (and (< x W) (> (+ x nw) KEY-W)
                 (>= y HEADER-H) (< y H))
        (app/set-draw-color ctx nr ng nb 220)
        (app/fill-rect ctx
                       (float (max x KEY-W)) (float (+ y 1))
                       (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))
        (app/set-draw-color ctx 255 255 255 180)
        (app/draw-rect ctx
                       (float (max x KEY-W)) (float (+ y 1))
                       (float (min nw (- W (max x KEY-W)))) (float (- NOTE-H 2)))))))

(defn draw-playhead [ctx play-beat scroll-beat zoom]
  (let [x (beat->x play-beat scroll-beat zoom)]
    (when (and (> x KEY-W) (< x W))
      (app/set-draw-color ctx 255 80 80 200)
      (app/draw-line ctx (float x) (float HEADER-H) (float x) (float H)))))

;; ── state machine ──────────────────────────────────────────────────────────
(defn handle-mdown [notes mx my mb scroll-beat scroll-note zoom selected-wf]
  ;; toolbar click in header
  (if (< my HEADER-H)
    (when-let [wf (toolbar-hit mx my)]
      {:selected-waveform wf})
    ;; piano roll click
    (when (> mx KEY-W)
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
              {:notes (conj notes {:pitch pitch :beat beat :dur 1 :waveform selected-wf})})))))))

(defn handle-mmove [drag mx my scroll-beat scroll-note zoom]
  (when drag
    (let [raw-beat (- (x->beat mx scroll-beat zoom) (:offset drag))
          new-beat (max 0 (snap raw-beat))
          new-pitch (-> (y->note my scroll-note) (max BOT-NOTE) (min TOP-NOTE))
          updated (assoc (:note drag) :beat new-beat :pitch new-pitch)]
      {:drag (assoc drag :note updated)})))

(defn handle-mup [drag notes]
  (when drag
    {:drag nil
     :notes (vec (map #(if (= % (:orig drag)) (:note drag) %) notes))}))

;; ── tunes mixer ────────────────────────────────────────────────────────────
(defn build-mixer [notes]
  (let [mixer (tunes/new BPM)]
    (tunes/disable-cache mixer)
    (doseq [{:keys [pitch beat dur waveform]} notes]
      (tunes/add-note mixer (midi->hz pitch)
                      (* beat SEC-PER-BEAT)
                      (* dur SEC-PER-BEAT)
                      (or waveform :sine)))
    mixer))

;; ── main loop ──────────────────────────────────────────────────────────────
(defn drain-events [ctx state]
  (let [ev (app/poll-event ctx)
        k (:kind ev)]
    (if (= k EVT-NONE)
      state
      (let [state' (let [{:keys [notes drag held scroll-beat scroll-note zoom mixer selected-waveform]} state]
                     (cond
                       (= k EVT-QUIT)
                       (do (when mixer (dr/close! mixer)) (assoc state :quit true :mixer nil))

                       (= k EVT-KEYDN)
                       (let [kc (:key-code ev)
                             s (assoc state :held (conj held kc))]
                         (cond
                           (= kc KC-SPACE)
                           (let [playing' (not (:playing state))]
                             (when mixer (dr/close! mixer))
                             (if playing'
                               (assoc s :playing true :play-beat 0.0
                                      :mixer (build-mixer notes))
                               (assoc s :playing false :mixer nil)))

                           (= kc KC-E)
                           (do
                             (let [path (str (host/getenv "HOME") "/piano-roll.wav")]
                               (dr/with-opaque [m (build-mixer notes)]
                                 (tunes/export-wav m path SAMPLE-RATE))
                               (println (str "Exported: " path)))
                             s)

                           :else s))

                       (= k EVT-KEYUP)
                       (assoc state :held (disj held (:key-code ev)))

                       (= k EVT-MDOWN)
                       (let [r (handle-mdown notes (:mouse-x ev) (:mouse-y ev)
                                             (:mouse-button ev) scroll-beat scroll-note zoom
                                             selected-waveform)]
                         (if r (merge state r) state))

                       (= k EVT-MMOVE)
                       (let [r (handle-mmove drag (:mouse-x ev) (:mouse-y ev)
                                             scroll-beat scroll-note zoom)]
                         (if r (merge state r) state))

                       (= k EVT-MUP)
                       (let [r (handle-mup drag notes)]
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

(def BLOCK-DUR-S (/ FRAME-SAMPLES (float SAMPLE-RATE)))
(def BLOCK-DUR-BEATS (* BLOCK-DUR-S (/ BPM 60.0)))

(defn tick-audio [state aud block-ptr]
  (if-not (:playing state)
    state
    (let [{:keys [notes mixer]} state
          max-beat (apply max 1 (map #(+ (:beat %) (:dur %)) notes))
          n (* FRAME-SAMPLES CHANNELS)]
      ;; push blocks until queue has at least MAX-QUEUED-BYTES ahead
      (loop [play-beat (:play-beat state)]
        (if (>= (audio/queued-bytes aud) MAX-QUEUED-BYTES)
          (assoc state :play-beat play-beat)
          (let [start-s (* play-beat SEC-PER-BEAT)
                next-beat (+ play-beat BLOCK-DUR-BEATS)]
            (process-block! mixer block-ptr n (float SAMPLE-RATE) (float start-s))
            (put-samples-raw! aud block-ptr n)
            (if (>= next-beat max-beat)
              (do (dr/close! mixer)
                  (assoc state :playing false :play-beat 0.0 :mixer nil))
              (recur next-beat))))))))

(defn run []
  (dr/with-opaque [ctx (app/create "Piano Roll" W H)]
    (dr/with-opaque [aud (audio/open SAMPLE-RATE)]
      (app/load-font ctx FONT 11)
      (let [block-ptr (ffi/alloc (* FRAME-SAMPLES CHANNELS 4))]
        (try
          (loop [state {:notes [{:pitch 60 :beat 0 :dur 2 :waveform :sine}
                                {:pitch 64 :beat 2 :dur 1 :waveform :sine}
                                {:pitch 67 :beat 3 :dur 1 :waveform :sine}
                                {:pitch 65 :beat 4 :dur 2 :waveform :square}
                                {:pitch 60 :beat 6 :dur 2 :waveform :triangle}]
                        :drag nil
                        :held #{}
                        :scroll-beat 0.0
                        :scroll-note 24.0
                        :zoom 1.0
                        :playing false
                        :play-beat 0.0
                        :mixer nil
                        :selected-waveform :sine
                        :quit false}]
            (let [state' (tick-audio (tick-held (drain-events ctx state)) aud block-ptr)]
              (when-not (:quit state')
                (let [{:keys [notes drag scroll-beat scroll-note zoom playing play-beat
                              selected-waveform]} state']
                  (app/set-draw-color ctx 20 20 28 255)
                  (app/clear ctx)
                  (draw-piano-keys ctx scroll-note)
                  (draw-grid ctx scroll-beat scroll-note zoom)
                  (draw-notes ctx notes drag scroll-beat scroll-note zoom)
                  (when playing
                    (draw-playhead ctx play-beat scroll-beat zoom))
                  (draw-toolbar ctx selected-waveform))
                (app/present ctx)
                (recur state'))))
          (finally (ffi/free block-ptr)))))))

(run)
