#!/usr/bin/env nbb
;; Pure-namespace tests under the first-class runtime.
;;   nbb --classpath "src:test:..." run-tests.cljs
;; The socket-level proof is a separate script: test/e2e.cljs.
(ns run-tests
  (:require [cljs.test :as t]
            [kekkai.node.application-test]
            [kekkai.node.application-test]
            [kekkai.node.disco-test]
            [kekkai.node.launchd-test]
            [kekkai.node.magicdns-test]
            [kekkai.node.netmap-test]
            [kekkai.node.packet-test]
            [kekkai.node.peer-test]
            [kekkai.node.publisher-parity-test]
            [kekkai.node.relay-test]
            [kekkai.node.signed-netmap-test]
            [kekkai.node.stream-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  ;; Only the exit code — cljs.test's own default report already printed the
  ;; "Ran N tests containing M assertions" line, and a second hand-rolled total
  ;; here disagreed with it (`:test`/`:pass` at this hook are not the per-run
  ;; totals). One number, from the framework.
  (when (or (pos? (:fail m)) (pos? (:error m)))
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kekkai.node.application-test
             'kekkai.node.disco-test
             'kekkai.node.launchd-test
             'kekkai.node.magicdns-test
             'kekkai.node.netmap-test
             'kekkai.node.packet-test
             'kekkai.node.publisher-parity-test
             'kekkai.node.peer-test
             'kekkai.node.relay-test
             'kekkai.node.signed-netmap-test
             'kekkai.node.stream-test)
