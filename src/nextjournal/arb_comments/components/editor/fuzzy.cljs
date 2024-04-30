;; Converted to cljs from https://gist.github.com/vaughnd/5099299
(ns nextjournal.arb-comments.editor.fuzzy
  (:require [clojure.string :as str]))

(defn str-len-distance
  ;; normalized multiplier 0-1
  ;; measures length distance between strings.
  ;; 1 = same length
  [s1 s2]
  (let [c1 (count s1)
        c2 (count s2)
        max-c1-c2 (max c1 c2)
        min-c1-c2 (min c1 c2)]
    (- 1 (/ (- max-c1-c2 min-c1-c2) max-c1-c2))))

(def MAX-STRING-LENGTH 1000.0)

(defn score [query s]
  (loop [q-chars (seq query)
         s-chars (seq s)
         mult 1
         idx MAX-STRING-LENGTH
         score 0]
    (cond
      ;; add str-len-distance to score, so strings with matches in same position get sorted by length
      ;; boost score if we have an exact match including punctuation
      (empty? q-chars) (+ score
                         (str-len-distance query s)
                         (if (<= 0 (.indexOf s query)) MAX-STRING-LENGTH 0))
      (empty? s-chars) 0
      :default (if (= (first q-chars) (first s-chars))
                 (recur (rest q-chars)
                   (rest s-chars)
                   (inc mult) ;; increase the multiplier as more query chars are matched
                   (dec idx) ;; decrease idx so score gets lowered the further into the string we match
                   (+ mult score)) ;; score for this match is current multiplier * idx
                 (recur q-chars
                   (rest s-chars)
                   1 ;; when there is no match, reset multiplier to one
                   (dec idx)
                   score)))))

(defn search
  ([vs query]
   (search vs identity query))
  ([vs kfn query]
   (when (seq query)
     (let [q (.toLowerCase query)
           _limit 10]
       (map :item
            #_ (take _limit)
            (sort-by :score (comp - compare)
                     (filter #(< 0 (:score %))
                             (for [v vs]
                               {:item v
                                :score (if-some [kstr (kfn v)]
                                         (score q (.toLowerCase kstr))
                                         0)}))))))))

(comment
  (search ["Hello" "Hallo" "Hawedere"] "Ha")
  (search [{:name "Bertha"} {:name "Ludwig"} {:name "Bella"} {:name "Hugo"} {:name "Barbara"}] :name "Ber"))
