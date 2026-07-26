#!/usr/bin/env nbb
;; Run the resident node agent.
;;
;;   nbb --classpath "src:..." bin/agent.cljs kekkai-node.edn
;;
;; See README "Configuration". Prints a status line every 30s; the netmap is the
;; only source of who this node may talk to.
(ns agent-main
  (:require [kekkai.node.agent :as agent]))

(apply agent/-main (drop 3 (js->clj (.-argv js/process))))
