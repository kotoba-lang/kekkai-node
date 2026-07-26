(ns kekkai.node.launchd-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kekkai.node.launchd :as launchd]))

(def cfg {:label "cloud.kekkai.node" :nbb "/opt/homebrew/bin/nbb"
          :classpath "src:../noise/src" :script "/opt/kekkai/bin/agent.cljs"
          :config-file "/opt/kekkai/node.edn" :working-dir "/opt/kekkai"})

(deftest plist-is-a-daemon-that-survives-logout
  (let [p (launchd/plist cfg)]
    (is (str/includes? p "<key>RunAtLoad</key>\n  <true/>"))
    (is (str/includes? p "<key>KeepAlive</key>\n  <true/>"))
    (is (str/includes? p "<key>ThrottleInterval</key>"))
    (testing "installed as a system daemon, not a user agent — a LaunchAgent dies
              with the login session and does not even appear in `launchctl list`
              over SSH"
      (is (= "/Library/LaunchDaemons/cloud.kekkai.node.plist"
             (launchd/plist-path "cloud.kekkai.node"))))
    (testing "the program arguments are the real invocation, not a shell string"
      (is (str/includes? p "<string>/opt/homebrew/bin/nbb</string>"))
      (is (str/includes? p "<string>--classpath</string>"))
      (is (str/includes? p "<string>/opt/kekkai/node.edn</string>")))))

(deftest plist-escapes-xml
  (let [p (launchd/plist (assoc cfg :classpath "a&b<c>"))]
    (is (str/includes? p "a&amp;b&lt;c&gt;"))
    (is (not (str/includes? p "a&b<c>")))))

(deftest resolver-file-points-at-the-unprivileged-dns-port
  (let [{:keys [path content]} (launchd/resolver-file {:tailnet "kekkai.example" :port 5354})]
    (is (= "/etc/resolver/kekkai.example" path))
    (is (= "nameserver 127.0.0.1\nport 5354\n" content))))

(deftest install-commands-are-data-not-side-effects
  (is (= [["launchctl" "bootout" "system/x"]
          ["launchctl" "bootstrap" "system" "/Library/LaunchDaemons/x.plist"]
          ["launchctl" "enable" "system/x"]]
         (launchd/install-commands "x"))))
