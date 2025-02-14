(ns halboy.navigator
  (:refer-clojure :exclude [get])
  (:require [clojure.walk :refer [keywordize-keys stringify-keys]]
            [halboy.resource :as hal]
            [halboy.data :refer [transform-values]]
            [halboy.json :as haljson]
            [halboy.http.default :as client]
            [halboy.http.protocol :as http]
            [halboy.params :as params]
            [halboy.argutils :refer [deep-merge]]
            [halboy.url :as url])
  (:import (halboy.resource Resource)))

(def default-settings
  {:client           (client/new-http-client)
   :follow-redirects true
   :http             {:headers {}}})

(def error-descriptions
  {:not-valid-json "Response body was not valid JSON"})

(defn failed? [response]
  (some? (:error response)))

(defn build-error [response]
  (let [error (:error response)]
    (ex-info
      (get-in error-descriptions [(:code error)] "Unknown error")
      response
      (:cause error))))

(defrecord Navigator [href settings response resource])

(defn- follow-redirect? [navigator]
  (and (= 201 (get-in navigator [:response :status]))
    (get-in navigator [:settings :follow-redirects])))

(defn- extract-header [navigator header]
  (get-in navigator [:response :headers header]))

(defn- extract-redirect-location [navigator]
  (let [base-url (:href navigator)
        endpoint (extract-header navigator :location)]
    (url/resolve-url base-url endpoint)))

(defn- get-resume-location [resource settings]
  (let [resume-from (:resume-from settings)
        self-link (hal/get-href resource :self)]
    (or
      resume-from
      (if (and self-link (url/absolute? self-link))
        self-link
        (throw
          (ex-info
            "No :resume-from option, and self link not absolute"
            {:self-link-value self-link}))))))

(defn- response->Navigator [response settings]
  (if (failed? response)
    (throw (build-error response))
    (let [current-url (:url response)
          resource (haljson/map->resource (:body response))]
      (->Navigator current-url settings response resource))))

(defn- resource->Navigator
  [resource settings]
  (let [current-url (get-resume-location resource settings)
        response {:status nil}]
    (->Navigator current-url settings response resource)))

(defn- resolve-absolute-href [navigator href]
  (url/resolve-url (:href navigator) href))

(defn- resolve-link [navigator link params]
  (let [resource (:resource navigator)
        href (hal/get-href resource link)]
    (if (nil? href)
      (throw (ex-info
               "Attempting to follow a link which does not exist"
               {:missing-rel    link
                :available-rels (hal/links resource)
                :resource       resource
                :response       (:response navigator)}))
      (params/build-query href params))))

(defn- read-url [request settings]
  (let [settings (deep-merge default-settings settings)
        request (deep-merge (:http settings) request)
        client (:client settings)]
    (response->Navigator (http/exchange client request) settings)))

(defn- head-url
  ([url settings]
   (head-url url {} settings))
  ([url params settings]
   (read-url
     {:method       :head
      :url          url
      :query-params params}
     settings)))

(defn- get-url
  ([url settings]
   (get-url url {} settings))
  ([url params settings]
   (read-url
     {:method       :get
      :url          url
      :query-params params}
     settings)))

(defn- delete-url
  ([url settings]
   (delete-url url {} settings))
  ([url params settings]
   (read-url
     {:method       :delete
      :url          url
      :query-params params}
     settings)))

(defn- write-url [request settings]
  (let [settings (deep-merge default-settings settings)
        request (deep-merge (:http settings) request)
        client (:client settings)
        result (response->Navigator (http/exchange client request) settings)]
    (if (follow-redirect? result)
      (get-url (extract-redirect-location result) settings)
      result)))

(defn- post-url
  ([url body settings]
   (post-url url body {} settings))
  ([url body params settings]
   (write-url
     {:method       :post
      :url          url
      :body         body
      :query-params params}
     settings)))

(defn- put-url
  ([url body settings]
   (put-url url body {} settings))
  ([url body params settings]
   (write-url
     {:method       :put
      :url          url
      :body         body
      :query-params params}
     settings)))

(defn- patch-url
  ([url body settings]
   (patch-url url body {} settings))
  ([url body params settings]
   (write-url
     {:method       :patch
      :url          url
      :body         body
      :query-params params}
     settings)))

(defn location
  "Gets the current location of the navigator"
  [navigator]
  (:href navigator))

(defn settings
  "Gets the navigation settings"
  [navigator]
  (:settings navigator))

(defn resource
  "Gets the resource from the navigator"
  [navigator]
  (:resource navigator))

(defn response
  "Gets the last response from the navigator"
  [navigator]
  (:response navigator))

(defn status
  "Gets the status code from the last response from the navigator"
  [navigator]
  (:status (response navigator)))

(defn discover
  "Starts a conversation with an API. Use this on the discovery endpoint."
  ([href]
   (discover href {}))
  ([href settings]
   (get-url href settings)))

(defn resume
  "Resumes a conversation with an API. Your resource needs a self
  link for this to work. If your self link is not absolute, you
  can pass an absolute url in the :resume-from key in the settings
  parameter."
  ([resource]
   (resume resource {}))
  ([resource settings]
   (resource->Navigator resource settings)))

(defn head
  "Performs a HEAD request against a link in an API."
  ([navigator link]
   (head navigator link {}))
  ([navigator link params]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (head-url href query-params (:settings navigator)))))

(defn get
  "Fetches the contents of a link in an API."
  ([navigator link]
   (get navigator link {}))
  ([navigator link params]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (get-url href query-params (:settings navigator)))))

(defn post
  "Posts content to a link in an API."
  ([navigator link body]
   (post navigator link {} body))
  ([navigator link params body]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (post-url href body query-params (:settings navigator)))))

(defn put
  "Puts content to a link in an API."
  ([navigator link body]
   (put navigator link {} body))
  ([navigator link params body]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (put-url href body query-params (:settings navigator)))))

(defn patch
  "Patch content to a link in an API."
  ([navigator link body]
   (patch navigator link {} body))
  ([navigator link params body]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (patch-url href body query-params (:settings navigator)))))

(defn delete
  "Delete content of a link in an API."
  ([navigator link]
   (delete navigator link {}))
  ([navigator link params]
   (let [resolved-link (resolve-link navigator link params)
         href (resolve-absolute-href navigator (:href resolved-link))
         query-params (:query-params resolved-link)]
     (delete-url href query-params (:settings navigator)))))

(defn- focus-key
  [resource key]
  (if (keyword? key)
    (hal/get-resource resource key)
    (nth resource key)))

(defn- focus-recursive
  [resource keys]
  (loop [result resource
         remaining-keys keys]
    (let [key (first remaining-keys)]
      (if key
        (recur
          (focus-key result key)
          (rest remaining-keys))
        result))))

(defn- focused-resource->Navigator
  [navigator resource]
  (let [self-link (hal/get-href resource :self)
        resume-from (if (url/absolute? self-link)
                      self-link
                      (resolve-absolute-href navigator self-link))]
    (resource->Navigator
      resource
      {:resume-from resume-from})))

(defn focus
  "Focuses navigator on embedded resource."
  [navigator key-or-keys]
  (let [resource (resource navigator)
        focused-resource (if (keyword key-or-keys)
                           (focus-key resource key-or-keys)
                           (focus-recursive resource key-or-keys))]
    (if (instance? Resource focused-resource)
      (focused-resource->Navigator navigator focused-resource)
      (throw (ex-info (format "Focusing must result in a single resource, resulted in %s"
                        (type focused-resource))
               {:resource resource})))))

(defn follow-redirect
  "Fetches the url of the location header"
  [navigator]
  (let [redirect-location (extract-redirect-location navigator)]
    (if-not (nil? redirect-location)
      (get-url redirect-location (:settings navigator))
      (throw (ex-info "Attempting to follow a redirect without a location header"
               {:headers  (get-in navigator [:response :headers])
                :resource resource
                :response (:response navigator)})))))

(defn get-header
  "Retrieves a specified header from the response"
  [navigator header]
  (extract-header navigator header))

(defn set-header
  "Sets a header for all subsequent calls"
  [navigator header-key header-value]
  (let [headers (get-in navigator [:settings :http :headers] {})
        updated-headers (assoc headers header-key header-value)]
    (assoc-in navigator [:settings :http :headers] updated-headers)))
