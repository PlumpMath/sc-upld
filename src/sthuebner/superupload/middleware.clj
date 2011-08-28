;;;; Ring middleware to workaround the provided wrap-multipart-param


(ns sthuebner.superupload.middleware
  (:use [ring.middleware multipart-params])
  (:require [sthuebner.superupload.storage :as storage]
	    [clojure.java.io :as io])
  (:import [java.io File]))


(defn- make-temp-file
  "Returns a new temporary file. The file will be deleted on VM exit."
  []
  (let [temp-file (File/createTempFile "superupload-" nil)]
    (.deleteOnExit temp-file)
    temp-file))


;; a multipart-store-function takes a map with keys :filename, :content-type, and :stream
;; it processes the stream and returns a map with keys :filename, :content-type, :tempfile, and :size
(defn- store-file-and-add-to-storage
  "Stores the file behind the given ITEM and adds an entry to our storage."
  [id expected-bytes]
  (fn [item]
    (let [temp-file (make-temp-file)
	  upload-entry (-> (select-keys item [:filename :content-type])
			   (assoc :tempfile temp-file))]
      ;; create storage entry
      (storage/add-upload id (assoc upload-entry
			       ;; expected-bytes is probably not the actual file size, so we update afterwards
			       :size expected-bytes
			       :status "incomplete"))
      
      (io/copy (:stream item) temp-file)

      ;; update storage entry
      (storage/update-upload id (assoc upload-entry
				  ;; take the acutal file size
				  :size (.length temp-file)
				  :status "complete")))))


;; parse multipart forms (also saves uploaded files as temp files)
(defn- wrap-multipart-params-with-storage
  "Wrapper to parse multipart forms. Files are stored using async-uploader"
  [handler id]
  (fn [{:keys [content-length] :as req}]
    (let [uploader (store-file-and-add-to-storage id content-length)
	  ;; use the original wrapper to do the multipart parsing
	  ;; just inject our custom store-function
	  inner-wrapper (wrap-multipart-params handler {:store uploader})]
      (inner-wrapper req))))
