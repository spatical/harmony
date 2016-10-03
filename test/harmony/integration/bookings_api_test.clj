(ns harmony.integration.bookings-api-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [clj-http.client :as client]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [harmony.integration.db-test-util :as db-test-util]
            [harmony.config :as config]
            [harmony.system :as system]))

(def fixed-uuid
  (let [ids-holder (atom {})]
    (fn [id]
      (if (contains? @ids-holder id)
        (get @ids-holder id)
        (get (swap! ids-holder assoc id (java.util.UUID/randomUUID)) id)))))

(defonce test-system nil)

(defn- teardown []
  (when-not (nil? test-system)
    (alter-var-root #'test-system component/stop)))

(defn- setup []
  (let [conf (config/config-harmony-api :test)]
    (teardown)
    (db-test-util/reset-test-db (config/migrations-conf conf))
    (alter-var-root
     #'test-system
     (constantly (system/harmony-api conf))))

  (alter-var-root #'test-system component/start))

(use-fixtures :each (fn [f]
                      (setup)
                      (f)
                      (teardown)))

(defn- do-post [endpoint body]
  (client/post (str "http://localhost:8086" endpoint)
               {:form-params body
                :as :transit+msgpack
                :accept :transit+msgpack
                :content-type :transit+msgpack
                :throw-exceptions false}))

(defn- do-get [endpoint query]
  (client/get (str "http://localhost:8086" endpoint)
              {:query-params query
               :as :transit+msgpack
               :accept :transit+msgpack
               :throw-exceptions false}))

(defn- plus [date plus-days]
  (c/to-date (t/plus (c/from-date date) (t/days plus-days))))

(defn- timeslot [start]
  {:start start
   :end (plus start 1)})

(deftest create-bookable
  (let [{:keys [status body]} (do-post "/bookables/create"
                                    {:marketplaceId (fixed-uuid :marketplaceId)
                                     :refId (fixed-uuid :refId)
                                     :authorId (fixed-uuid :authorId)})]
    (is (= 201 status))
    (is (= {:marketplaceId (fixed-uuid :marketplaceId)
            :refId (fixed-uuid :refId)
            :authorId (fixed-uuid :authorId)
            :unitType :day}
           (select-keys (get-in body [:data :attributes]) [:marketplaceId :refId :authorId :unitType])))))

(deftest prevent-bookable-double-create
  (let [status-first (:status (do-post "/bookables/create"
                                    {:marketplaceId (fixed-uuid :marketplaceId)
                                     :refId (fixed-uuid :refId)
                                     :authorId (fixed-uuid :authorId)}))
        status-second (:status (do-post "/bookables/create"
                                     {:marketplaceId (fixed-uuid :marketplaceId)
                                      :refId (fixed-uuid :refId)
                                      :authorId (fixed-uuid :authorId)}))]

    (is (= 201 status-first))
    (is (= 409 status-second))))


(deftest initiate-booking
  (let [_ (do-post "/bookables/create"
                {:marketplaceId (fixed-uuid :marketplaceId)
                 :refId (fixed-uuid :refId)
                 :authorId (fixed-uuid :authorId)})
        {:keys [status body]} (do-post "/bookings/initiate"
                                    {:marketplaceId (fixed-uuid :marketplaceId)
                                     :customerId (fixed-uuid :customerId)
                                     :refId (fixed-uuid :refId)
                                     :initialStatus :paid
                                     :start #inst "2016-09-19T00:00:00.000Z"
                                     :end #inst "2016-09-20T00:00:00.000Z"})]


    (is (= 201 status))
    (is (= {:customerId (fixed-uuid :customerId)
            :status :paid
            :seats 1
            :start #inst "2016-09-19T00:00:00.000Z"
            :end #inst "2016-09-20T00:00:00.000Z"}
           (select-keys (get-in body [:data :attributes]) [:customerId :status :seats :start :end])))))

(deftest query-timeslots
  (let [_ (do-post "/bookables/create"
                {:marketplaceId (fixed-uuid :marketplaceId)
                 :refId (fixed-uuid :refId)
                 :authorId (fixed-uuid :authorId)})
        {:keys [status body]} (do-get "/timeslots/query"
                                   {:marketplaceId (fixed-uuid :marketplaceId)
                                    :refId (fixed-uuid :refId)
                                    :start "2016-09-19T00:00:00.000Z"
                                    :end "2016-09-26T00:00:00.000Z"})

        free-timeslots (map timeslot [#inst "2016-09-19T00:00:00.000Z"
                                      #inst "2016-09-20T00:00:00.000Z"
                                      #inst "2016-09-21T00:00:00.000Z"
                                      #inst "2016-09-22T00:00:00.000Z"
                                      #inst "2016-09-23T00:00:00.000Z"
                                      #inst "2016-09-24T00:00:00.000Z"
                                      #inst "2016-09-25T00:00:00.000Z"])
        actual   (map #(select-keys (:attributes %) [:refId :unitType :seats :start :end]) (:data body))
        expected (map #(merge % {:refId (fixed-uuid :refId)
                                 :unitType :day
                                 :seats 1}) free-timeslots)]

    (is (= 200 status))
    (is (= (count free-timeslots) (count (:data body))))
    (is (= expected actual))))

(deftest query-reserved-timeslots
  (let [_ (do-post "/bookables/create"
                {:marketplaceId (fixed-uuid :marketplaceId)
                 :refId (fixed-uuid :refId)
                 :authorId (fixed-uuid :authorId)})
        _ (do-post "/bookings/initiate"
                {:marketplaceId (fixed-uuid :marketplaceId)
                 :customerId (fixed-uuid :customerId)
                 :refId (fixed-uuid :refId)
                 :initialStatus :paid
                 :start #inst "2016-09-19T00:00:00.000Z"
                 :end #inst "2016-09-20T00:00:00.000Z"})
        _ (do-post "/bookings/initiate"
                {:marketplaceId (fixed-uuid :marketplaceId)
                 :customerId (fixed-uuid :customerId)
                 :refId (fixed-uuid :refId)
                 :initialStatus :paid
                 :start #inst "2016-09-23T00:00:00.000Z"
                 :end #inst "2016-09-25T00:00:00.000Z"})
        {:keys [status body]} (do-get "/timeslots/query"
                                   {:marketplaceId (fixed-uuid :marketplaceId)
                                    :refId (fixed-uuid :refId)
                                    :start "2016-09-19T00:00:00.000Z"
                                    :end "2016-09-26T00:00:00.000Z"})
        free-timeslots (map timeslot [#inst "2016-09-20T00:00:00.000Z"
                                      #inst "2016-09-21T00:00:00.000Z"
                                      #inst "2016-09-22T00:00:00.000Z"
                                      #inst "2016-09-25T00:00:00.000Z"])
        actual   (map #(select-keys (:attributes %) [:refId :unitType :seats :start :end]) (:data body))
        expected (map #(merge % {:refId (fixed-uuid :refId)
                                 :unitType :day
                                 :seats 1}) free-timeslots)]

    (is (= 200 status))
    (is (= (count free-timeslots) (count (:data body))))
    (is (= expected actual))))
