#!/usr/bin/env jolt
;; jolt-diplomat bind script — replaces bind.sh
;;
;; Usage:
;;   ./bind.clj <capi-dir> [--release]
;;   jolt bind.clj <capi-dir> [--release]

(require '[jolt.fs      :as fs]
         '[jolt.process :as p])

(def args *command-line-args*)

(when (< (count args) 1)
  (println "Usage: bind.clj <capi-dir> [--release]")
  (System/exit 1))

(def capi      (fs/absolutize (first args)))
(def release?  (= (second args) "--release"))
(def cargo-flag (when release? "--release"))
(def build-dir  (if release? "release" "debug"))

(def demo      (fs/parent capi))
(def script    (fs/absolutize *file*))
(def root      (fs/parent script))
(def backend   (fs/path root "backend"))
(def headers   (fs/path demo "c-headers"))
(def generated (fs/path demo "generated"))
(def generator (fs/path backend "target" "debug" "jolt-diplomat-backend"))

(defn die [msg]
  (println msg)
  (System/exit 1))

;; Extract [lib] name from Cargo.toml
(def cargo-toml (fs/slurp (fs/path capi "Cargo.toml")))
(def lib-name
  (or (some->> (re-find #"(?m)^\[lib\][^\[]*\nname\s*=\s*\"([^\"]+)\"" cargo-toml)
               second)
      (die (str "Error: could not find [lib] name in " (fs/path capi "Cargo.toml")))))

(def dylib-dir (fs/path capi "target" build-dir))
(def shim-out  (fs/path demo (str "lib" lib-name "_shim.dylib")))

(println (str "=== jolt-bind: " lib-name " ==="))
(println (str "    capi:      " capi))
(println (str "    headers:   " headers))
(println (str "    generated: " generated))
(println (str "    shim:      " shim-out))
(println "")

(println "--- 1. Build Rust cdylib ---")
(apply p/shell (cond-> ["cargo" "build" "--manifest-path" (str (fs/path capi "Cargo.toml"))]
                 cargo-flag (conj cargo-flag)))

(println "--- 2. Generate C headers (diplomat-tool) ---")
(fs/create-dirs headers)
(p/shell {:dir (str capi)} "diplomat-tool" "c" (str headers) "-e" "src/lib.rs")

(println "--- 3. Build Jolt generator ---")
(p/shell "cargo" "build" "--manifest-path" (str (fs/path backend "Cargo.toml")))

(println "--- 4. Generate Clojure bindings + shim C ---")
(when (fs/exists? generated) (fs/delete-tree generated))
(fs/create-dirs generated)
(p/shell (str generator) (str (fs/path capi "src" "lib.rs")) (str generated) (str headers))

(println "--- 5. Compile shim dylib ---")
(p/shell "cc"
         (str (fs/path generated "generated_shim.c"))
         "-I" (str headers)
         (str "-L" dylib-dir) (str "-l" lib-name)
         "-shared" "-fPIC"
         "-o" (str shim-out)
         (str "-Wl,-rpath," dylib-dir))

(println "")
(println "Done.")
(println (str "  cdylib: " dylib-dir "/lib" lib-name ".dylib"))
(println (str "  shim:   " shim-out))
(println (str "  clj:    " generated "/diplomat/"))
