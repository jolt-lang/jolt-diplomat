(ns glimmer-adw.core
  "Opt-in libadwaita widgets, shipped as a separate glimmer-adw package
  (own :jolt/native entry for libadwaita-1) so plain-GTK4 apps never link
  it — mirrors the glimmer-gl extension pattern already established.
  Land AFTER glimmer.css and glimmer.widgets-widen (tiers 1-2): these
  widgets lean on the same generic prop/CSS-class plumbing, and
  AdwNavigationView's push/pop is a sibling concept to :stack, worth
  having that pattern already proven first."
  (:require [glimmer.ffi :as ffi]
            [glimmer.widget :as widget]))

;; --- application shell ----------------------------------------------------

(widget/register-widget! :adw-application-window
  {:ctor "adw_application_window_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-header-bar
  {:ctor "adw_header_bar_new" :apply widget/apply-generic-props!})

(ffi/defcfn adw-toolbar-view-new "adw_toolbar_view_new" [] :pointer)
(ffi/defcfn adw-toolbar-view-set-content "adw_toolbar_view_set_content" [:pointer :pointer] :void)
(ffi/defcfn adw-toolbar-view-add-top-bar "adw_toolbar_view_add_top_bar" [:pointer :pointer] :void)

(widget/register-widget! :adw-toolbar-view
  {:ctor adw-toolbar-view-new
   :apply (fn [ptr {:keys [content top-bar] :as props}]
            (widget/apply-generic-props! ptr props)
            (when content (adw-toolbar-view-set-content ptr (:ptr content)))
            (when top-bar (adw-toolbar-view-add-top-bar ptr (:ptr top-bar))))})

(widget/register-widget! :adw-toast-overlay
  {:ctor "adw_toast_overlay_new" :apply widget/apply-generic-props!})

;; --- navigation -------------------------------------------------------

(widget/register-widget! :adw-navigation-view
  {:ctor "adw_navigation_view_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-navigation-page
  {:ctor "adw_navigation_page_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-split-view
  {:ctor "adw_split_view_new" :apply widget/apply-generic-props!})

;; --- design-system widgets ----------------------------------------------

(widget/register-widget! :adw-preferences-page
  {:ctor "adw_preferences_page_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-preferences-group
  {:ctor "adw_preferences_group_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-action-row
  {:ctor "adw_action_row_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-switch-row
  {:ctor "adw_switch_row_new" :apply widget/apply-generic-props!})

(widget/register-widget! :adw-status-page
  {:ctor "adw_status_page_new" :apply widget/apply-generic-props!})

;; --- styling: the actual "theme" hook, distinct from raw CSS ------------

(ffi/defcfn adw-style-manager-get-default "adw_style_manager_get_default" [] :pointer)
(ffi/defcfn adw-style-manager-set-color-scheme
  "adw_style_manager_set_color_scheme" [:pointer :int] :void)

(def color-scheme->int
  {:default 0 :force-light 1 :prefer-light 2 :prefer-dark 4 :force-dark 3})

(defn set-color-scheme!
  "e.g. (set-color-scheme! :prefer-dark). Live — takes effect immediately,
  no restart, no manual CSS-provider swap needed."
  [scheme-kw]
  (adw-style-manager-set-color-scheme
   (adw-style-manager-get-default)
   (get color-scheme->int scheme-kw 0)))
