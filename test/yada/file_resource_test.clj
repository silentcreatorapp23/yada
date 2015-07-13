;; Copyright © 2015, JUXT LTD.

(ns yada.file-resource-test
  (:require
   [byte-streams :as bs]
   [bidi.ring :refer [make-handler]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [ring.mock.request :refer [request]]
   [ring.util.time :refer [parse-date format-date]]
   [yada.bidi :as yb]
   [yada.core :refer [yada]]
   [clojure.tools.logging :refer :all]
   [yada.resources.file-resource :refer :all]
   [yada.resource :as yst]
   [yada.test.util :refer [given]])
  (:import [java.io File ByteArrayInputStream]
           [java.util Date]))

(def exists? (memfn exists))

#_(deftest legal-name-test
  (are [x] (not (#'legal-name x))
    ".." "./foo" "./../home" "./.." "/foo" "a/b")
  (are [x] (legal-name x)
    "a" "b.txt" "c..txt"))

;; Test an actual file resource
(deftest file-test
  (let [resource (io/file "test/yada/state/test.txt")
        handler (yada resource)
        request (request :get "/")
        response @(handler request)]

    (testing "expectations of set-up"
      (given resource
        identity :? exists?
        (memfn getName) := "test.txt"))

    (testing "response"
      (given response
        identity :? some?
        :status := 200
        [:headers "content-type"] := "text/plain"
        [:body type] := File
        [:headers "content-length"] := (.length resource)))



    (testing "last-modified"
      (given response
        [:headers "last-modified" parse-date] := (java.util.Date. (.lastModified resource))
        [:headers "last-modified" parse-date (memfn getTime)] := (.lastModified resource)))

    (testing "conditional-response"
      (given @(handler (assoc-in request [:headers "if-modified-since"]
                                 (format-date (Date. (.lastModified resource)))))
        :status := 304))))

(deftest temp-file-test
  (testing "creation of a new file"
    (let [f (java.io.File/createTempFile "yada" nil nil)]
      (io/delete-file f)
      (is (not (exists? f)))

      (let [options {:methods #{:get :head :put :delete}}
            newstate {:username "alice" :name "Alice"}]

        ;; A PUT request arrives on a new URL, containing a
        ;; representation which is parsed into the following model :-
        (letfn [(make-put []
                  (merge (request :put "/" )
                         {:headers {"x-yada-debug" "true"}}
                         {:body (ByteArrayInputStream. (.getBytes (pr-str newstate)))}))]

          ;; If this resource didn't allow the PUT method, we'd get a 405.
          (let [handler (yada f (update-in options [:methods] disj :put))]
            (given @(handler (make-put))
              :status := 405))

          ;; The resource allows a PUT, the server
          ;; should create the resource with the given content and
          ;; receive a 201.

          (let [handler (yada f options)]
            (given @(handler (make-put))
              :status := 201
              :body :? nil?))

          (is (= (edn/read-string (slurp f)) newstate)
              "The file content after the PUT was not the same as that
                in the request")

          (let [handler (yada f options)]
            (given @(handler (request :get "/"))
              :status := 200
              [:body slurp edn/read-string] := newstate)

            ;; Update the resource, since it already exists, we get a 204
            ;; TODO Check spec, is this the correct status code?

            (given @(handler (make-put))
              :status := 204)

            (given @(handler (request :delete "/"))
              :status := 204)

            (given @(handler (request :get "/"))
              :status := 404
              :body :? nil?))

          (is (not (.exists f)) "File should have been deleted by the DELETE"))))))

(deftest temp-dir-test
  (let [f (java.io.File/createTempFile "yada" nil nil)]
    (io/delete-file f)
    (is (not (exists? f)))
    (.mkdirs f)
    (is (exists? f))

    (let [resource f
          options {:methods #{:get :head :put :delete}}
          handler (yb/resource-branch resource options)
          root-handler (make-handler ["/dir/" handler])]

      (testing "Start with 0 files"
        (given f
          [yst/make-resource #(yst/exists? % {})] :? true?
          [(memfn listFiles) count] := 0))

      (testing "PUT a new file"
        (given @(root-handler
                 (merge (request :put "/dir/abc.txt")
                        {:body (ByteArrayInputStream. (.getBytes "foo"))}))
          :status := 204))

      (given f
        [(memfn listFiles) count] := 1)

      (testing "GET the new file"
        (given @(root-handler (request :get "/dir/abc.txt"))
          :status := 200
          [:body slurp] := "foo"))

      (testing "PUT another file"
        (given @(root-handler
                 (merge (request :put "/dir/håkan.txt")
                        {:body (ByteArrayInputStream. (.getBytes "bar"))}))
          :status := 204))

      (testing "GET the index, in US-ASCII"
        (given @(root-handler (merge-with merge (request :get "/dir/")
                                          {:headers {"accept" "text/plain"
                                                     "accept-charset" "US-ASCII"}}))
          :status := 200
          [:headers "content-type"] := "text/plain;charset=us-ascii"
          [:body #(bs/convert % String)] := "abc.txt\nh?kan.txt\n"))


      ;; In ASCII, Håkan's 'å' gets turned into a '?', so let's try UTF-8 (the default)
      (testing "GET the index"
        (given @(root-handler (merge-with merge (request :get "/dir/")
                                          {:headers {"accept" "text/plain"}}))
          :status := 200
          [:headers "content-type"] := "text/plain;charset=utf-8"
          [:body #(bs/convert % String)] := "abc.txt\nhåkan.txt\n"))



      (testing "GET the file that doesn't exist"
        (given @(root-handler (request :get "/dir/abcd.txt"))
          :status := 404))

      (testing "DELETE the new files"
        (given @(root-handler (request :delete "/dir/abc.txt"))
          :status := 204))

      (given f
        [(memfn listFiles) count] := 1)

      (given @(root-handler (request :delete "/dir/håkan.txt"))
        :status := 204)

      (given f
        [(memfn listFiles) count] := 0)

      (testing "DELETE the directory"
        (given @(root-handler (request :delete "/dir/"))
          :status := 204))

      (given f
        identity :!? exists?))))
