(ns glimmer.css
  "GtkCssProvider binding + :class/:id hiccup props — the piece missing
  between 'GTK has themes' and glimmer actually being able to reach them.
  Two new universal props, applied via glimmer.widget's generic prop-diff
  path, so every tag gets them for free rather than needing per-widget
  wiring."
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
  string to hot-swap the whole theme at runtime — also usable as a manual
  light/dark toggle if you'd rather not lean on AdwStyleManager (see
  glimmer-adw.core/set-color-scheme! for that alternative)."
  [css-text display-ptr]
  (let [provider (css-provider-new)]
    (css-provider-load-from-string provider css-text)
    (style-context-add-provider-for-display
     display-ptr provider GTK-STYLE-PROVIDER-PRIORITY-APPLICATION)))

;; --- hiccup prop application ---------------------------------------------
;; Usage: [:button {:class ["pill" "suggested-action"] :id "save-btn"} "Save"]

(defn apply-class-and-id!
  "Called from glimmer.widget's generic :apply dispatch, alongside the
  existing generic props (not a per-widget special case)."
  [gtk-ptr {:keys [class id]}]
  (when id (widget-set-name gtk-ptr (name id)))
  (doseq [c (if (coll? class) class [class])]
    (when c (widget-add-css-class gtk-ptr (name c)))))

(defn diff-class!
  "Reconciler hook: called with old and new :class values on re-render.
  Only the delta gets add/remove calls, not a full clear-and-reapply —
  matters if any class was added by GTK itself between renders (e.g. a
  :hover-driven pseudo-class GTK manages internally, which this must not
  clobber)."
  [gtk-ptr old-classes new-classes]
  (let [old-set (set (map name (if (coll? old-classes) old-classes [old-classes])))
        new-set (set (map name (if (coll? new-classes) new-classes [new-classes])))]
    (doseq [removed (clojure.set/difference old-set new-set)]
      (widget-remove-css-class gtk-ptr removed))
    (doseq [added (clojure.set/difference new-set old-set)]
      (widget-add-css-class gtk-ptr added))))
