(ns selmer.util
  (:require [clojure.java.io :as io])
  (:import java.io.File java.io.StringReader))

(def custom-resource-path (atom nil))


(defn read-char [^java.io.Reader rdr]
  (let [ch (.read rdr)]
    (if-not (== -1 ch) (char ch))))

(defn assoc-in*
  "Works best for small collections seemingly."
  [m ks v]
  (let [k (first ks)]
    (if (= (count ks) 0)
      (assoc m k (assoc-in* (get m k) (next ks) v))
      (assoc m k v))))

(def ^:dynamic ^Character *tag-open* \{)
(def ^:dynamic ^Character *tag-close* \})
(def ^:dynamic ^Character *filter-open* \{)
(def ^:dynamic ^Character *filter-close* \})
(def ^:dynamic ^Character *tag-second* \%)

(defn read-tag-info [rdr]
  (let [buf (StringBuilder.)
        tag-type (if (= *filter-open* (read-char rdr)) :filter :expr)]
    (loop [ch1 (read-char rdr)
           ch2 (read-char rdr)]
      (when-not (or (nil? ch1)
                    (and (or (= *filter-close* ch1) (= *tag-second* ch1))
                         (= *tag-close* ch2)))
        (.append buf ch1)
        (recur ch2 (read-char rdr))))
    
    (let [content (->> (.toString buf) 
                       (re-seq #"(?:[^\s\"]|\"[^\"]*\")+")
                       (remove empty?)
                       (map (fn [^String s] (.trim s))))]
      (merge {:tag-type tag-type}
             (if (= :filter tag-type)
               {:tag-value (first content)}
               {:tag-name (keyword (first content))
                :args (next content)})))))

(defn peek-rdr [^java.io.Reader rdr]
  (.mark rdr 1)
  (let [result (read-char rdr)]
    (.reset rdr)
    result))

(defmacro ->buf [[buf] & body]
  `(let [~buf (StringBuilder.)]
    (do ~@body)
    (.toString ~buf)))

(defn read-tag-content [rdr]
  (->buf [buf]
    (let [filter? (not= *tag-second* (peek-rdr rdr))]
      (.append buf *tag-open*)      
      (loop [ch (read-char rdr)]
        (.append buf ch)
        (when (not= *tag-close* ch)
          (recur (read-char rdr))))
      (when filter?
        (.append buf (read-char rdr))))))

(defn open-tag? [ch rdr]
  (and (= *tag-open* ch) 
       (let [next-ch (peek-rdr rdr)]
         (or (= *filter-open* next-ch)
             (= *tag-second* next-ch)))))

(defn split-by-args [s]
  (let [rdr (StringReader. s)
        buf (StringBuilder.)]
    (loop [items []
           ch (read-char rdr)
           open? false]
      (cond
        (nil? ch) items
        
        (and open? (= ch \"))
        (let [value (.trim (.toString buf))]
          (.setLength buf 0)          
          (recur (conj items value) (read-char rdr) false))
        
        (= ch \")
        (recur items (read-char rdr) true)
        
        (= ch \=)
        (let [id (.trim (.toString buf))]
          (.setLength buf 0)
          (recur (conj items id) (read-char rdr) open?))
        
        :else
        (do
          (.append buf ch) 
          (recur items (read-char rdr) open?))))))

#_(defn resource-path
  "returns the path to the public folder of the application"
  [& [path]]
  (if-let [path (io/resource (or path "."))]
    (.getPath path)))

(defn in-jar? [file-path]
  (.contains file-path "jar!/"))

(defn resource-path [template]
  (if-let [path @custom-resource-path]
    (java.net.URL. (str "file:///" path template)) 
    (-> (Thread/currentThread)
      (.getContextClassLoader)
      (.getResource template))))

(defn check-template-exists [^String file-path]
  (when-not (or (in-jar? file-path)
                (.exists (java.io.File. file-path)))
    (throw (Exception. (str "template: \"" file-path "\" not found")))))