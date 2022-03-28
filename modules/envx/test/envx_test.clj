(ns envx-test
  (:require [clojure.test :refer [deftest testing is]]
            envx))

(deftest get-protocol-test
  (is (= "unix-pass" (envx/get-protocol "unix-pass://test")))
  (is (= "curl" (envx/get-protocol "curl-https://test")))
  (is (= "curl" (envx/get-protocol "curl-http://test")))
  (is (= "exec" (envx/get-protocol "exec://test")))
  (is (= "load-file" (envx/get-protocol "load-file://test")))
  (is (nil? (envx/get-protocol "test")))
  (is (nil? (envx/get-protocol "http://test")))
  (is (nil? (envx/get-protocol "file://test"))))

(deftest strip-protocol-test
  (is (= "test" (envx/strip-protocol "unix-pass://test")))
  (is (= "https://test" (envx/strip-protocol "curl-https://test")))
  (is (= "http://test" (envx/strip-protocol "curl-http://test")))
  (is (= "test" (envx/strip-protocol "exec://test")))
  (is (= "test" (envx/strip-protocol "load-file://test")))
  (is (= "test"(envx/strip-protocol "unix-pass://test"))))

(comment
  (clojure.test/run-all-tests))
