(ns demo
  (:require [markdown :as md]))

(defn -main [& _]
  (println (md/to-html "# Hello from Jolt\n\nThis is **markdown-rs** called from Jolt via FFI.\n\n- item one\n- item two\n"))
  (println (md/to-html "> blockquote\n\n```\ncode block\n```\n"))
  (println "done."))
