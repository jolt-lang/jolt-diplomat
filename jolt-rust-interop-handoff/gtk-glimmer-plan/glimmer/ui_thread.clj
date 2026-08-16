(ns glimmer.ui-thread
  "Fixes a latent bug: Jolt has real OS threads (future, pmap, agents), and
  a swap! on a ratom from any thread other than GTK's main-loop thread
  triggers a widget mutation from the wrong thread, which GTK will warn
  about, misbehave on, or crash on. Route every reconciler mutation through
  schedule-on-ui-thread!, wired in at glimmer.core's single reconcile
  call site — one choke point, not a check in every widget setter."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn g-idle-add "g_idle_add" [:pointer :pointer] :uint)

(def ^:private gtk-thread-id (atom nil))

(defn mark-gtk-thread!
  "Call once, from inside g_application_run's thread, at startup."
  []
  (reset! gtk-thread-id (ffi/current-thread-id)))

(defn on-gtk-thread? []
  (= @gtk-thread-id (ffi/current-thread-id)))

;; g_idle_add's callback signature is gboolean (*)(gpointer); returning
;; FALSE (0) removes it from the idle queue after one run.
(def ^:private idle-cb
  (ffi/foreign-callable
   (fn [data-ptr]
     (let [thunk (ffi/deref-and-release! data-ptr)]
       (thunk))
     0)
   [:pointer] :int
   :collect-safe))

(defn schedule-on-ui-thread!
  "Runs f on the GTK main-loop thread. Synchronous fast path when already
  on it, so the common case (an event callback re-rendering itself) pays
  no idle round-trip."
  [f]
  (if (on-gtk-thread?)
    (f)
    (let [token (ffi/register-thunk! f)]
      (g-idle-add idle-cb token))))

;; --- regression test sketch ---------------------------------------------
;; Keep a repro script alongside this: a `future` that `swap!`s a counter
;; ratom ~50x/sec while the window is open. Should run clean with this
;; module wired in at glimmer.core's reconcile call site, and visibly
;; misbehave (GTK warnings, or a crash) with that wiring reverted. That
;; contrast is the actual test — there's no unit-testable assertion for
;; "didn't corrupt GTK's internal state from the wrong thread."
