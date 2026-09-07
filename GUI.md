# GUI Strategy

## Now — SDL3

SDL3 is a C library with a polling API. Jolt owns the event loop; SDL3 never takes the thread.

```clojure
(dr/load! demo-dir "sdl3_capi")
(require '[diplomat.window :as window])
(require '[diplomat.renderer :as renderer])
(require '[diplomat.event :as event])

(dr/with-opaque [win (window/create "My App" 800 600)]
  (dr/with-opaque [ren (renderer/create win)]
    (loop []
      (event/poll-all)          ; drain event queue, return on quit
      (renderer/set-color ren 30 30 30 255)
      (renderer/clear ren)
      ;; draw things
      (renderer/present ren)
      (recur))))
```

**What SDL3 gives us:**
- Window, canvas, renderer
- Draw rect / line / texture / text (via SDL_ttf)
- Keyboard, mouse, gamepad events
- macOS Metal backend, first-class support
- No widget catalog — everything custom

**Diplomat mapping:**
| SDL3 concept | Diplomat shape |
|---|---|
| `SDL_Window*` | opaque `Window` |
| `SDL_Renderer*` | opaque `Renderer` |
| `SDL_Event` | struct return or enum |
| `SDL_Rect`, `SDL_Color` | struct by value |
| Draw calls | plain fns, primitive args |

**Plan:**
1. Create `examples/sdl3/sdl3_capi/` — thin Diplomat bridge over the `sdl3` Rust crate
2. Wrap: window create/destroy, renderer, basic draw calls, event polling
3. Demo: Jolt script draws a colored rect, responds to keyboard/quit

---

## Later — egui

egui is an immediate-mode widget library (buttons, sliders, panels, plots). It owns
the event loop via `eframe::run_native()` which never returns — incompatible with Jolt
driving the loop directly.

**Workaround: frame callback + atom hot-swap**

```clojure
(def frame-fn (atom nil))

;; swap from REPL while running:
(reset! frame-fn
  (fn [ctx]
    (gui/window ctx "Controls"
      (fn []
        (gui/label ctx "BPM")
        (gui/slider ctx bpm 60 200)))))

;; blocks forever — egui owns the thread from here
(app/run (fn [ctx] (when @frame-fn (@frame-fn ctx))))
```

The atom lets you hot-swap the frame function from a REPL without restarting the app.
It's not native REPL eval-and-see, but gives sub-second feedback if Jolt's REPL can
push to a running namespace.

**When to add egui:**
- Property panels, plugin browsers, transport controls, settings
- Anywhere the widget catalog saves work over custom SDL3 drawing
- Run egui on top of SDL3/wgpu canvas (egui has a wgpu backend) — custom rendering
  for piano roll / waveform / timeline, egui panels for everything else

**Diplomat challenge:**
egui's API is heavily generic and closure-based. A `vizia_capi`-style facade would need
to hide all generics behind opaque handles and flatten the closure-based layout API into
explicit begin/end calls:

```rust
// instead of egui's closure style:
ui.horizontal(|ui| { ui.label("x"); });

// diplomat-compatible:
pub fn horizontal_begin(ui: &mut Ui) { ... }
pub fn horizontal_end(ui: &mut Ui) { ... }
```

Not trivial but tractable for a fixed widget subset.

**Plan (future):**
1. Pick a fixed widget subset: label, button, slider, text input, collapsible, scroll area
2. Write `egui_capi` with begin/end layout calls and no generics in public API
3. Wire egui's wgpu backend into the SDL3 render loop so one canvas hosts both
4. Frame callback model — Jolt's "main" is the update fn, atoms for hot-reload

---

## DAW-specific note

For a DAW the layering is:

```
SDL3 window + wgpu GPU renderer
  └── custom canvas: piano roll, waveform, timeline, mixer strips
  └── egui overlay: transport bar, plugin browser, property panels, settings
  └── audio engine: separate high-priority thread, lock-free ring buffers to GUI
```

VIZIA (a DAW-first Rust UI framework) is worth revisiting when it stabilizes —
it has better parameter binding semantics than egui and is designed for low-latency
audio UI. It currently has no C API and is pre-1.0.
