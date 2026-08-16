# Jolt: GTK4 + libadwaita interop plan

Scope: deepen glimmer's existing GTK4 backend rather than add a second
toolkit — per the earlier decision (GTK's CSS theming, widget breadth via
GObject introspection, and libadwaita's design system all outweigh what a
second backend like FLTK would cost). Nothing here changes glimmer's
architecture; every item is additive to `glimmer.ffi` / `glimmer.widget` /
`glimmer.core`.

Sequenced by leverage-per-effort, not by dependency — items 1-2 are
correctness fixes that should land before the widget set grows further,
since more widgets means more surface for the bugs they fix.

---

## 1. UI-thread marshalling (do first — it's a latent bug, not a feature)

**Problem:** Jolt has real OS threads (`future`, `pmap`, agents). A `swap!`
on a `ratom` from any thread other than the GTK main loop's thread triggers
a widget mutation from the wrong thread. GTK will warn, misbehave, or
segfault depending on what's touched.

**Fix:** wrap `g_idle_add` and route all reactive-cell notifications through
it when the write didn't originate on the GTK thread.

```clojure
(ns glimmer.ui-thread
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn g-idle-add "g_idle_add" [:pointer :pointer] :uint)

;; Marshaled once, at backend init — the GTK thread is whichever thread
;; called g_application_run.
(def ^:private gtk-thread-id (atom nil))

(defn mark-gtk-thread! []
  (reset! gtk-thread-id (ffi/current-thread-id)))

(defn on-gtk-thread? []
  (= @gtk-thread-id (ffi/current-thread-id)))

;; g_idle_add callback signature: gboolean (*)(gpointer) — return FALSE to
;; run once and be removed from the idle queue.
(def ^:private idle-cb
  (ffi/foreign-callable
   (fn [data-ptr]
     (let [thunk (ffi/deref-and-release! data-ptr)] ;; pop from a side table
       (thunk))
     0) ;; G_SOURCE_REMOVE
   [:pointer] :int
   :collect-safe))

(defn schedule-on-ui-thread!
  "Runs f on the GTK main-loop thread. If already on it, runs synchronously
  — this fn is meant to wrap every reconciler mutation, so the common case
  (an event callback triggering its own re-render) should not pay an idle
  round-trip."
  [f]
  (if (on-gtk-thread?)
    (f)
    (let [token (ffi/register-thunk! f)] ;; side table: id -> f
      (g-idle-add idle-cb token))))
```

**Wire-in point:** `glimmer.core`'s reconciler currently calls widget
mutation fns directly from wherever `swap!` happened. Wrap that single call
site — `(schedule-on-ui-thread! #(reconcile! ...))` — rather than pushing
the check into every widget setter. One choke point, one place to get it
right.

**Verification:** a repro script — `future` that `swap!`s a counter atom
50x/sec while the window is open — should run clean under this change and
visibly misbehave (or crash) with it reverted. Keep that script as a
regression test.

---

## 2. `GtkCssProvider` + `:class`/`:id` props

**Problem:** styling isn't in the hiccup vocabulary at all today — no way
to reach GTK's CSS cascade or libadwaita's dark/light switching from
glimmer.

```clojure
(ns glimmer.css
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn css-provider-new "gtk_css_provider_new" [] :pointer)
(ffi/defcfn css-provider-load-from-string
  "gtk_css_provider_load_from_string" [:pointer :string] :void)
(ffi/defcfn style-context-add-provider-for-display
  "gtk_style_context_add_provider_for_display" [:pointer :pointer :uint] :void)
(ffi/defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)
(ffi/defcfn widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)
(ffi/defcfn widget-set-name "gtk_widget_set_name" [:pointer :string] :void) ;; backs #id selectors

(def ^:private GTK-STYLE-PROVIDER-PRIORITY-APPLICATION 600)

(defn load-app-stylesheet!
  "Loads a CSS string app-wide. Call once at startup; call again with a new
  string to hot-swap the whole theme at runtime — this is also the
  mechanism for a manual light/dark toggle if you don't want to lean on
  AdwStyleManager."
  [css-text display-ptr]
  (let [provider (css-provider-new)]
    (css-provider-load-from-string provider css-text)
    (style-context-add-provider-for-display
     display-ptr provider GTK-STYLE-PROVIDER-PRIORITY-APPLICATION)))
```

**Hiccup surface:** two new universal props, applied in `glimmer.widget`'s
generic prop-application path (works for every tag, no per-widget code):

```clojure
[:button {:class ["pill" "suggested-action"] :id "save-btn"} "Save"]
```

```clojure
;; in glimmer.widget's :apply dispatch, alongside existing generic props
(defn apply-class-and-id! [gtk-ptr {:keys [class id]}]
  (when id (widget-set-name gtk-ptr (name id)))
  (doseq [c (if (coll? class) class [class])]
    (when c (widget-add-css-class gtk-ptr (name c)))))
```

Diffing add/remove on `:class` changes between renders is the reconciler's
job (compare old/new class sets, call `remove`/`add` for the delta) —
straightforward once wired into the same prop-diff path everything else
already uses.

---

## 3. Widen the plain-GTK4 widget registry

No new mechanism — `register-widget!` already exists for this. Sequenced
by what a real app needs first:

| Priority | Tag | GTK type | Notes |
|---|---|---|---|
| 1 | `:dropdown` | `GtkDropDown` | needs a `GtkStringList` or model prop |
| 1 | `:progressbar` | `GtkProgressBar` | trivial, single fraction prop |
| 1 | `:spinbutton` | `GtkSpinButton` | numeric input, min/max/step props |
| 2 | `:stack` + `:stackswitcher` | `GtkStack`/`GtkStackSwitcher` | multi-page apps; needed before libadwaita's `AdwViewStack` is worth adding |
| 2 | `:textview` | `GtkTextView` | multiline text; needs a `GtkTextBuffer` wrapper, not just a prop |
| 3 | `:listview`/`:columnview` | `GtkListView`/`GtkColumnView` | the big one — needs a `GListModel` bridge (see below), budget it separately |
| 3 | `:calendar`, `:scale` | — | low effort, low priority unless requested |

**`GListModel` bridge is its own sub-item, not a one-liner.** `GtkListView`
expects a `GListModel` + a factory callback pattern (`GtkSignalListItemFactory`
with `setup`/`bind`/`unbind` signals) — closer in shape to the egui
closure problem than a normal widget, since the factory callback is
invoked repeatedly by GTK during scrolling/recycling. Worth a small design
spike on its own before committing to the table above's item 3; don't let
it block items 1-2.

---

## 4. `glimmer-adw` — libadwaita as an opt-in package

Mirrors the `glimmer-gl` extension pattern already established (README
convention: core stays free of the extra native dependency).

```clojure
(ns glimmer-adw.core
  "Opt-in libadwaita widgets. Requires libadwaita-1 at runtime — do not
  require this namespace from glimmer.core or any core widget file."
  (:require [glimmer.ffi :as ffi]
            [glimmer.widget :as widget]
            [glimmer.genum :as genum]))

;; Same GObject/genum machinery glimmer.widget already uses for plain GTK —
;; libadwaita types are GObjects, so this is registration, not new plumbing.
(widget/register-widget! :adw-application-window
  {:ctor "adw_application_window_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-header-bar
  {:ctor "adw_header_bar_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-toolbar-view
  {:ctor "adw_toolbar_view_new"
   :apply (fn [ptr props]
            (widget/apply-generic-props! ptr props)
            ;; content/top-bar/bottom-bar are set via dedicated methods,
            ;; not generic properties — needs its own :apply, unlike most
            ;; of the table above.
            (when-let [c (:content props)] (adw-toolbar-view-set-content ptr c)))})

(widget/register-widget! :adw-toast-overlay
  {:ctor "adw_toast_overlay_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-navigation-view
  {:ctor "adw_navigation_view_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-navigation-page
  {:ctor "adw_navigation_page_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-preferences-page
  {:ctor "adw_preferences_page_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-action-row
  {:ctor "adw_action_row_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-status-page
  {:ctor "adw_status_page_new" :apply widget/apply-generic-props!})

;; AdwStyleManager — the actual "theme" hook, distinct from raw CSS.
(ffi/defcfn adw-style-manager-get-default "adw_style_manager_get_default" [] :pointer)
(ffi/defcfn adw-style-manager-set-color-scheme
  "adw_style_manager_set_color_scheme" [:pointer :int] :void)

(def color-scheme->int
  {:default 0 :force-light 1 :prefer-light 2 :prefer-dark 4 :force-dark 3})

(defn set-color-scheme! [scheme-kw]
  (adw-style-manager-set-color-scheme
   (adw-style-manager-get-default) (color-scheme->int scheme-kw)))
```

**Sequencing note:** land this *after* items 1-3, not before — libadwaita
widgets need the same generic prop/CSS-class plumbing item 2 builds, and a
`AdwNavigationView` push/pop is a state-management pattern worth having
`:stack` (item 3) already solved for, since they're conceptually siblings.

**Packaging:** ship as a separate `glimmer-adw` project depending on
`glimmer`, own `:jolt/native` entry for `libadwaita-1`, so plain-GTK4 apps
never link it.

---

## 5. Keyed reconciliation

README already flags the gap directly. Becomes urgent the moment `:dropdown`
or `:listview` (item 3) ship, since both are list-shaped and users will
reorder/filter.

Sketch: extend hiccup's existing `[:tag props & children]` shape to accept
an optional `:key` in props, and change the reconciler's child-diffing from
positional (`nth` comparison) to a keyed diff (Clojure-map-of-key→old-vnode
lookup, classic React/Elm keyed-list algorithm — move/insert/remove by key
rather than by index). This is a `glimmer.core` change, not `glimmer.ffi` —
worth scoping as its own small design doc before implementation, since
getting the diff algorithm right matters more than the GTK-specific pieces
above.

---

## Suggested order

1. UI-thread marshalling (latent bug — fix now)
2. CSS provider + `:class`/`:id`
3. Widget registry items 1-2 from the table (skip `:listview` for now)
4. Keyed reconciliation (before `:listview`/`:dropdown` make its absence
   painful)
5. `:listview`/`:columnview` + `GListModel` bridge
6. `glimmer-adw` opt-in package

Each item above is independently shippable and testable — no item requires
a later one to land first except where explicitly noted (5 wants 4 done
first; 6 wants 2-3 done first).
