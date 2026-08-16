(ns glimmer.widgets-widen
  "Widens the plain-GTK4 widget registry via the existing register-widget!
  mechanism — no new architecture, just more entries. Sequenced: dropdown/
  progressbar/spinbutton first (cheap, commonly needed), stack+switcher
  next (multi-page apps), listview/columnview last (needs its own
  GListModel bridge design spike — don't let it block the rest)."
  (:require [glimmer.widget :as widget]
            [jolt.ffi :as ffi]))

;; --- tier 1: cheap, commonly needed --------------------------------------

(widget/register-widget! :progressbar
  {:ctor "gtk_progress_bar_new"
   :apply (fn [ptr {:keys [fraction] :as props}]
            (widget/apply-generic-props! ptr props)
            (when fraction (progress-bar-set-fraction ptr fraction)))})

(ffi/defcfn progress-bar-set-fraction "gtk_progress_bar_set_fraction" [:pointer :double] :void)

(widget/register-widget! :spinbutton
  {:ctor (fn [{:keys [min max step] :or {min 0.0 max 100.0 step 1.0}}]
           (spin-button-new-with-range min max step))
   :apply widget/apply-generic-props!})

(ffi/defcfn spin-button-new-with-range
  "gtk_spin_button_new_with_range" [:double :double :double] :pointer)

(widget/register-widget! :dropdown
  ;; items via a GtkStringList — simplest case; a full model-backed variant
  ;; is a separate item once :listview's GListModel bridge exists, since
  ;; they'd share the same underlying machinery.
  {:ctor (fn [{:keys [items]}]
           (drop-down-new-from-strings (into-array String (map str items))))
   :apply widget/apply-generic-props!})

(ffi/defcfn drop-down-new-from-strings
  "gtk_drop_down_new_from_strings" [:pointer] :pointer) ;; NULL-terminated char**

;; --- tier 2: multi-page apps ---------------------------------------------

(widget/register-widget! :stack
  {:ctor "gtk_stack_new" :apply widget/apply-generic-props!})

(widget/register-widget! :stack-switcher
  {:ctor "gtk_stack_switcher_new"
   :apply (fn [ptr {:keys [stack] :as props}]
            (widget/apply-generic-props! ptr props)
            (when stack (stack-switcher-set-stack ptr (:ptr stack))))})

(ffi/defcfn stack-switcher-set-stack "gtk_stack_switcher_set_stack" [:pointer :pointer] :void)
(ffi/defcfn stack-add-titled "gtk_stack_add_titled" [:pointer :pointer :string :string] :pointer)

;; --- tier 3: deferred — needs its own GListModel + factory design -------
;;
;; :listview / :columnview are NOT included here. GtkListView expects a
;; GListModel plus a GtkSignalListItemFactory with setup/bind/unbind
;; signal callbacks invoked repeatedly by GTK during scroll/recycling —
;; structurally closer to the egui closure problem than a normal widget.
;; Scope as its own design spike; don't block tiers 1-2 on it.
