#!/usr/bin/env nbb
;; Print (or write, with --write) the macOS residency for this node: the
;; LaunchDaemon plist and the split-DNS resolver file.
;;
;;   nbb --classpath "src:..." bin/install.cljs kekkai-node.edn [--write]
;;
;; Writing both paths needs root. This prints the privileged commands instead of
;; running them: an installer that silently `sudo`s is one you cannot review.
(ns install-main
  (:require [kekkai.node.launchd :as launchd]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn -main [& args]
  (let [config-file (or (first args) "kekkai-node.edn")
        write? (some #{"--write"} args)
        config (cljs.reader/read-string (.readFileSync fs config-file "utf8"))
        label (or (:launchd-label config) launchd/default-label)
        root (.resolve path ".")
        plist (launchd/plist
               {:label label
                :nbb (or (:nbb-path config) "/opt/homebrew/bin/nbb")
                :classpath (or (:classpath config)
                               "src:../bytes/src:../noise/src:../org-ietf-dns/src:../org-ietf-turn/src")
                :script (.join path root "bin" "agent.cljs")
                :config-file (.resolve path config-file)
                :working-dir root})
        resolver (launchd/resolver-file {:tailnet (:tailnet config)
                                         :port (get-in config [:dns :port])})]
    (if write?
      (do (.writeFileSync fs (launchd/plist-path label) plist)
          (.mkdirSync fs "/etc/resolver" #js {:recursive true})
          (.writeFileSync fs (:path resolver) (:content resolver))
          (println (str "wrote " (launchd/plist-path label) " and " (:path resolver)))
          (println "now run, as root:")
          (doseq [cmd (launchd/install-commands label)]
            (println (str "  " (clojure.string/join " " cmd)))))
      (do (println (str ";; " (launchd/plist-path label)))
          (println plist)
          (println (str ";; " (:path resolver)))
          (println (:content resolver))
          (println ";; privileged steps:")
          (doseq [cmd (launchd/install-commands label)]
            (println (str ";;   " (clojure.string/join " " cmd))))
          (println ";; re-run with --write (as root) to install")))))

(apply -main (drop 3 (js->clj (.-argv js/process))))
