(ns demo
  (:require [diplomat.runtime :as dr]
            [diplomat.sdl-app :as app]))

(require '[jolt.host :as host])
(def sdl3-dir (str (host/getenv "PWD") "/.."))
(dr/load! sdl3-dir "sdl3_capi")

(def W 800)
(def H 600)
(def FPS 60)
(def FRAME-MS (/ 1000 FPS))

(def EVT-NONE  0)
(def EVT-QUIT  1)
(def EVT-KEYDN 2)
(def KC-ESC    27)
(def KC-SPACE  32)

;; ── box state ──────────────────────────────────────────────────────────────

(def COLORS
  [[220  80  80]   ; red
   [ 80 180  80]   ; green
   [ 80 130 220]   ; blue
   [220 180  50]   ; yellow
   [180  80 220]   ; purple
   [ 60 200 200]]) ; cyan

(defn make-box [i]
  (let [sz  (+ 30 (* i 15))
        spd (+ 1.5 (* i 0.7))]
    {:x (+ 50 (* i 110)) :y (+ 50 (* i 70))
     :w sz :h sz
     :vx spd :vy (* spd 0.7)
     :color (nth COLORS (mod i (count COLORS)))}))

(def BOXES (mapv make-box (range 6)))

(defn step-box [{:keys [x y w h vx vy] :as box}]
  (let [nx (+ x vx) ny (+ y vy)
        nvx (if (or (< nx 0) (> (+ nx w) W)) (- vx) vx)
        nvy (if (or (< ny 0) (> (+ ny h) H)) (- vy) vy)
        nx  (max 0 (min nx (- W w)))
        ny  (max 0 (min ny (- H h)))]
    (assoc box :x nx :y ny :vx nvx :vy nvy)))

;; ── render ─────────────────────────────────────────────────────────────────

(defn draw-grid [a]
  (app/set-draw-color a 40 40 40 255)
  (doseq [x (range 0 W 50)]
    (app/draw-line a x 0 x H))
  (doseq [y (range 0 H 50)]
    (app/draw-line a 0 y W y)))

(defn draw-boxes [a boxes paused?]
  (doseq [{:keys [x y w h color]} boxes]
    (let [[r g b] color]
      (app/set-draw-color a r g b 200)
      (app/fill-rect a x y w h)
      ;; bright border
      (app/set-draw-color a
                          (min 255 (+ r 60))
                          (min 255 (+ g 60))
                          (min 255 (+ b 60))
                          255)
      (app/draw-rect a x y w h)))
  (when paused?
    (app/set-draw-color a 255 255 255 255)
    (app/draw-text a "PAUSED — press SPACE" 10 10 255 255 255 200)))

(defn render [a boxes paused?]
  (app/set-draw-color a 20 20 20 255)
  (app/clear a)
  (draw-grid a)
  (draw-boxes a boxes paused?)
  (app/present a))

;; ── main loop ──────────────────────────────────────────────────────────────

(defn run []
  (dr/with-opaque [a (app/create "Bouncing Boxes" W H)]
    (let [font "/System/Library/Fonts/SFNSMono.ttf"]
      (try (app/load-font a font 14) (catch Exception _)))
    (loop [boxes BOXES paused? false]
      (let [evt    (app/poll-event a)
            kind   (:kind evt)
            kc     (:key-code evt)
            quit?  (or (= kind EVT-QUIT)
                       (and (= kind EVT-KEYDN) (= kc KC-ESC)))
            paused? (if (and (= kind EVT-KEYDN) (= kc KC-SPACE))
                      (not paused?)
                      paused?)]
        (when-not quit?
          (let [next-boxes (if paused? boxes (mapv step-box boxes))]
            (render a next-boxes paused?)
            (Thread/sleep FRAME-MS)
            (recur next-boxes paused?)))))))

(run)
