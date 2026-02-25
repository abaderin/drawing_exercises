(ns drawing-exercises.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [drawing-exercises.core :as core]))

(deftest greet-test
  (testing "greet returns proper greeting"
    (is (= "Hello, World!" (core/greet "World")))
    (is (= "Hello, Alice!" (core/greet "Alice")))))
