(ns braid.server.db.thread
  (:require [datomic.api :as d]
            [clj-time.core :as t]
            [clj-time.coerce :refer [to-date-time to-long]]
            [clojure.set :refer [difference]]
            [braid.server.db :as db]
            [braid.server.db.common :refer :all]
            [braid.server.db.tag :as tag]))

;; Queries

(declare thread-add-last-open-at)

(defn thread-group-id
  [thread-id]
  (some-> (d/pull (db/db) [{:thread/group [:group/id]}]
                  [:thread/id thread-id])
          :thread/group :group/id))

(defn thread-by-id
  [thread-id]
  (some-> (d/pull (db/db) thread-pull-pattern [:thread/id thread-id])
          db->thread))

(defn threads-by-id
  [thread-ids]
  (->> thread-ids
       (map (fn [id] [:thread/id id]))
       (d/pull-many (db/db) thread-pull-pattern)
       (map db->thread)))

(defn thread-last-open-at
  [thread user-id]
  (let [user-hides-at (->> (d/q
                             '[:find [?inst ...]
                               :in $ ?thread-id ?user-id
                               :where
                               [?u :user/id ?user-id]
                               [?t :thread/id ?thread-id]
                               [?u :user/open-thread ?t ?tx false]
                               [?tx :db/txInstant ?inst]]
                             (d/history (db/db))
                             (thread :id)
                             user-id)
                           (map (fn [t] (.getTime t))))
        user-messages-at (->> (thread :messages)
                              (filter (fn [m] (= (m :user-id) user-id)))
                              (map :created-at)
                              (map (fn [t] (.getTime t))))]
    (apply max (concat [0] user-hides-at user-messages-at))))

(defn users-subscribed-to-thread
  [thread-id]
  (d/q '[:find [?user-id ...]
         :in $ ?thread-id
         :where
         [?user :user/id ?user-id]
         [?user :user/subscribed-thread ?thread]
         [?thread :thread/id ?thread-id]]
       (db/db)
       thread-id))

(defn users-with-thread-open
  [thread-id]
  (d/q '[:find [?user-id ...]
         :in $ ?thread-id
         :where
         [?user :user/id ?user-id]
         [?user :user/open-thread ?thread]
         [?thread :thread/id ?thread-id]]
       (db/db)
       thread-id))

(defn user-can-see-thread?
  [user-id thread-id]
  (or
    ;user can see the thread if it's a new (i.e. not yet in the database) thread...
    (nil? (d/entity (db/db) [:thread/id thread-id]))
    ; ...or they're already subscribed to the thread...
    (contains? (set (users-subscribed-to-thread thread-id)) user-id)
    ; ...or they're mentioned in the thread
    ; TODO: is it possible for them to be mentioned but not subscribed?
    (contains? (-> (d/pull (db/db) [:thread/mentioned] [:thread/id thread-id])
                   :thread/mentioned set)
               user-id)
    ; ...or they are in the group of any tags on the thread
    (seq (d/q '[:find (pull ?group [:group/id])
                :in $ ?thread-id ?user-id
                :where
                [?thread :thread/id ?thread-id]
                [?thread :thread/tag ?tag]
                [?tag :tag/group ?group]
                [?group :group/user ?user]
                [?user :user/id ?user-id]]
              (db/db) thread-id user-id))))

(defn thread-has-tags?
  [thread-id]
  (seq (d/q '[:find ?tag
              :in $ ?thread-id
              :where
              [?thread :thread/id ?thread-id]
              [?thread :thread/tag ?tag]]
            (db/db) thread-id)))

(defn open-threads-for-user
  [user-id]
  (let [visible-tags (tag/tag-ids-for-user user-id)]
    (->> (d/q '[:find (pull ?thread pull-pattern)
                :in $ ?user-id pull-pattern
                :where
                [?e :user/id ?user-id]
                [?e :user/open-thread ?thread]]
              (db/db)
              user-id
              thread-pull-pattern)
         (into ()
               (map (comp
                      #(update-in % [:tag-ids]
                                  (partial into #{} (filter visible-tags)))
                      #(thread-add-last-open-at % user-id)
                      db->thread
                      first))))))

(defn recent-threads
  [{:keys [user-id group-id num-threads] :or {num-threads 10}}]
  (->> (d/q '[:find (pull ?thread pull-pattern)
              :in $ ?group-id ?cutoff pull-pattern
              :where
              [?g :group/id ?group-id]
              [?thread :thread/group ?g]
              [?msg :message/thread ?thread]
              [?msg :message/created-at ?time]
              [(clj-time.coerce/to-date-time ?time) ?dtime]
              [(clj-time.core/after? ?dtime ?cutoff)]]
            (db/db)
            group-id
            (t/minus (t/now) (t/weeks 1))
            thread-pull-pattern)
       (into ()
             (comp (map (comp db->thread first))
                   (filter (comp (partial user-can-see-thread? user-id) :id))
                   (map #(thread-add-last-open-at % user-id))))
       (sort-by (fn [t] (apply max (map (comp to-long :created-at) (t :messages))))
                #(compare %2 %1))
       (take num-threads)))

(defn subscribed-thread-ids-for-user
  [user-id]
  (d/q '[:find [?thread-id ...]
         :in $ ?user-id
         :where
         [?user :user/id ?user-id]
         [?user :user/subscribed-thread ?thread]
         [?thread :thread/id ?thread-id]]
       (db/db)
       user-id))

(defn thread-newest-message
  [thread-id]
  (d/q '[:find (max ?time) .
         :in $ ?t-id
         :where
         [?t :thread/id ?t-id]
         [?m :message/thread ?t]
         [?m :message/created-at ?time]]
       (db/db) thread-id))

;; Transactions

(defn user-hide-thread-txn
  [user-id thread-id]
  [[:db/retract [:user/id user-id] :user/open-thread [:thread/id thread-id]]])

(defn user-show-thread-txn
  [user-id thread-id]
  [[:db/add [:user/id user-id] :user/open-thread [:thread/id thread-id]]])

(defn update-thread-last-open!
  "Bump the tx time of the user opening the thread.  Needs to explicitly be two
  separate transactions, since redundant datoms are eliminated, meaning we
  can't have a transaction that just changes the tx"
  [thread-id user-id]
  (when (seq (d/q '[:find ?t
                    :in $ ?user-id ?thread-id
                    :where
                    [?u :user/id ?user-id]
                    [?t :thread/id ?thread-id]
                    [?u :user/open-thread ?t]]
                  (db/db) user-id thread-id))
    (db/run-txns! (user-hide-thread-txn user-id thread-id))
    (db/run-txns! (user-show-thread-txn user-id thread-id))))

(defn user-unsubscribe-from-thread-txn
  [user-id thread-id]
  [[:db/retract [:user/id user-id] :user/subscribed-thread [:thread/id thread-id]]
   [:db/retract [:user/id user-id] :user/open-thread [:thread/id thread-id]]])

(defn tag-thread-txn
  [group-id thread-id tag-id]
  (concat
    ; upsert-thread
    (if-not (d/entity (db/db) [:thread/id thread-id])
      [{:db/id (d/tempid :entities)
        :thread/id thread-id
        :thread/group [:group/id group-id]}]
      [])
    ; add tag to thread
    [[:db/add [:thread/id thread-id]
      :thread/tag [:tag/id tag-id]]]
    ; open and subscribe thread for users subscribed to tag
    ; ...unless they're already subscribed, which means they've seen it
    (mapcat
      (fn [user-id]
        [[:db/add [:user/id user-id]
          :user/subscribed-thread [:thread/id thread-id]]
         [:db/add [:user/id user-id]
          :user/open-thread [:thread/id thread-id]]])
      (difference (set (tag/users-subscribed-to-tag tag-id))
                  (set (users-subscribed-to-thread thread-id))))))

;; Misc

(defn thread-add-last-open-at
  [thread user-id]
  (assoc thread :last-open-at (thread-last-open-at thread user-id)))
