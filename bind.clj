#!/usr/bin/env jolt
;; jolt-diplomat bind script — replaces bind.sh
;;
;; Usage:
;;   ./bind.clj <capi-dir> [--release] [--features F1,F2] [--out-suffix SUFFIX]
;;   jolt bind.clj <capi-dir> [--release] [--features F1,F2] [--out-suffix SUFFIX]
;;
;; --features and --out-suffix exist because diplomat-tool/the jolt backend
;; parse src/lib.rs textually — they don't run cargo's feature/cfg
;; resolution, so a #[cfg(feature = "x")]-gated item is always visible to
;; codegen regardless of which cargo features the .dylib was actually built
;; with. A crate that wants a "consumable" build (no tracing surface) and a
;; "traced" build (e.g. AllocStats, see rust-jolt-ef3.1) from the same
;; lib.rs therefore needs two separate generated/ trees, one per feature
;; set, produced by running this script twice with different --features/
;; --out-suffix — NOT one generated/ tree that happens to sometimes match
;; the dylib and sometimes not.

(require '[jolt.fs      :as fs]
         '[jolt.process :as p]
         '[clojure.string :as str])

(def args *command-line-args*)

(when (< (count args) 1)
  (println "Usage: bind.clj <capi-dir> [--release] [--features F1,F2] [--out-suffix SUFFIX]")
  (System/exit 1))

(def capi (fs/absolutize (first args)))
(def opts (rest args))

(defn opt-value [flag opts]
  (loop [xs opts]
    (cond
      (empty? xs) nil
      (= flag (first xs)) (second xs)
      :else (recur (rest xs)))))

(def release?     (some #{"--release"} opts))
(def cargo-flag   (when release? "--release"))
(def build-dir    (if release? "release" "debug"))
(def features-str (opt-value "--features" opts))
(def out-suffix   (or (opt-value "--out-suffix" opts) ""))

(def demo      (fs/parent capi))
(def script    (fs/absolutize *file*))
(def root      (fs/parent script))
(def backend   (fs/path root "backend"))
(def headers   (fs/path demo (str "c-headers" out-suffix)))
(def generated (fs/path demo (str "generated" out-suffix)))
(def generator (fs/path backend "target" "debug" "jolt-diplomat-backend"))

;; A plain `cargo build` always writes to <capi>/target/<profile>/lib<name>.
;; With no separate target dir per feature set, a default build and a
;; --features build alternate overwriting the SAME dylib path — whichever
;; ran most recently is silently what's on disk, mismatched against
;; whatever generated/ tree a caller happens to load against. A non-empty
;; --out-suffix therefore gets its own CARGO_TARGET_DIR (capi/target<suffix>)
;; so the two dylibs coexist permanently instead of clobbering each other.
(def target-dir (fs/path capi (str "target" out-suffix)))

(defn die [msg]
  (println msg)
  (System/exit 1))

;; Extract [lib] name from Cargo.toml
(def cargo-toml (fs/slurp (fs/path capi "Cargo.toml")))
(def lib-name
  (or (some->> (re-find #"(?m)^\[lib\][^\[]*\nname\s*=\s*\"([^\"]+)\"" cargo-toml)
               second)
      (die (str "Error: could not find [lib] name in " (fs/path capi "Cargo.toml")))))

(def dylib-dir (fs/path target-dir build-dir))
;; cargo already picks the right cdylib extension per-OS (.dylib on macOS,
;; .so on Linux) -- the hand-written shim name must match it, or bind.clj
;; silently produces a shim no Linux (e.g. AL2023 Lambda) build can load.
(def native-ext (if (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
                   "dylib"
                   "so"))
(def shim-out  (fs/path demo (str "lib" lib-name out-suffix "_shim." native-ext)))

(println (str "=== jolt-bind: " lib-name " ==="))
(println (str "    capi:      " capi))
(println (str "    headers:   " headers))
(println (str "    generated: " generated))
(println (str "    shim:      " shim-out))
(println "")

(println "--- 1. Build Rust cdylib ---")
(apply p/shell {:extra-env {"CARGO_TARGET_DIR" (str target-dir)}}
       (cond-> ["cargo" "build" "--manifest-path" (str (fs/path capi "Cargo.toml"))]
         cargo-flag (conj cargo-flag)
         features-str (conj "--features" features-str)))

(println "--- 2. Generate C headers (diplomat-tool) ---")
;; NOTE: diplomat-tool parses src/lib.rs textually — #[cfg(feature = "x")]
;; items are always included in the generated headers/bindings regardless
;; of features-str above. See the file-level comment: this is why a
;; feature-gated item needs its own --out-suffix run, not a single shared
;; generated/ tree.
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
(println (str "  cdylib: " dylib-dir "/lib" lib-name "." native-ext))
(println (str "  shim:   " shim-out))
(println (str "  clj:    " generated "/diplomat/"))
