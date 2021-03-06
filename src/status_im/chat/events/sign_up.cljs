(ns status-im.chat.events.sign-up
  (:require [re-frame.core :as re-frame]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.phone-number :as phone-number-util]
            [status-im.constants :as const]
            [status-im.chat.sign-up :as sign-up]
            [status-im.ui.screens.accounts.events :as accounts-events]
            [status-im.ui.screens.contacts.events :as contacts-events]))

;;;; Helpers fns

(defn sign-up
  "Creates effects for signing up"
  [db phone-number message-id]
  (let [current-account-id (:accounts/current-account-id db)
        {:keys [public-key address]} (get-in db [:accounts/accounts current-account-id])]
    {:dispatch sign-up/start-listening-confirmation-code-sms-event
     :http-post {:action                "sign-up"
                 :data                  {:phone-number     (phone-number-util/format-phone-number phone-number)
                                         :whisper-identity public-key
                                         :address          address}
                 :success-event-creator (fn [_]
                                          [::sign-up-success message-id])
                 :failure-event-creator (fn [_]
                                          [::http-request-failure [::sign-up phone-number message-id]])}}))

(defn sign-up-confirm
  "Creates effects for sign-up confirmation"
  [db confirmation-code message-id]
  {:http-post {:action                "sign-up-confirm"
               :data                  {:code confirmation-code}
               :success-event-creator (fn [body]
                                        [::sign-up-confirm-response body message-id])
               :failure-event-creator (fn [_]
                                        [::http-request-failure [::sign-up-confirm confirmation-code message-id]])}})

;;;; Handlers

(handlers/register-handler-fx
  ::sign-up
  [re-frame/trim-v]
  (fn [{:keys [db]} [phone-number message-id]]
    (sign-up db phone-number message-id)))

(defn- message-seen [{:keys [db] :as fx} message-id]
  (merge fx
         {:db             (assoc-in db [:message-data :statuses message-id] {:status :seen})
          :update-message {:message-id     message-id
                           :message-status :seen}}))

(handlers/register-handler-fx
  ::sign-up-success
  [re-frame/trim-v (re-frame/inject-cofx :random-id)]
  (fn [{:keys [db random-id]} message-id]
    (-> {:db db
         :dispatch-n [;; create manual way for entering confirmation code
                      (sign-up/enter-confirmation-code-event random-id)
                      ;; create automatic way for receiving confirmation code
                      sign-up/start-listening-confirmation-code-sms-event]}
        (message-seen message-id))))

(handlers/register-handler-fx
  :start-listening-confirmation-code-sms
  [re-frame/trim-v]
  (fn [{:keys [db]} [sms-listener]]
    {:db (if-not (:confirmation-code-sms-listener db)
           (assoc db :confirmation-code-sms-listener sms-listener)
           db)}))

(handlers/register-handler-fx
  ::sign-up-confirm
  (fn [{:keys [db]} [confirmation-code message-id]]
    (sign-up-confirm db confirmation-code message-id)))

(defn- sign-up-confirmed [{:keys [db] :as fx}]
  (cond-> (update fx :dispatch-n conj [:request-permissions
                                       [:read-contacts]
                                       #(re-frame/dispatch [:sync-contacts (fn [contacts]
                                                                             [::contacts-synced contacts])])])
    (:confirmation-code-sms-listener db)
    (merge {:db                  (dissoc db :confirmation-code-sms-listener)
            :remove-sms-listener (:confirmation-code-sms-listener db)})))

(handlers/register-handler-fx
  ::sign-up-confirm-response
  [re-frame/trim-v (re-frame/inject-cofx :random-id)]
  (fn [{:keys [db random-id]} [{:keys [message status]} message-id]]
    (cond-> {:db db
             :dispatch-n [[:received-message
                           {:message-id   random-id
                            :content      message
                            :content-type const/text-content-type
                            :outgoing     false
                            :chat-id      const/console-chat-id
                            :from         const/console-chat-id
                            :to           "me"}]]}
      message-id
      (message-seen message-id)

      (= "confirmed" status)
      sign-up-confirmed

      (= "failed" status)
      (update :dispatch-n conj (sign-up/incorrect-confirmation-code-event random-id)))))

(handlers/register-handler-fx
  ::contacts-synced
  [re-frame/trim-v (re-frame/inject-cofx :random-id)]
  (fn [{:keys [db random-id] :as cofx} [contacts]]
    (-> {:db db}
        (as-> fx
              (merge fx
                     (accounts-events/account-update (assoc cofx :db (:db fx)) {:signed-up? true})
                     {:dispatch (sign-up/contacts-synchronised-event random-id)})))))

(handlers/register-handler-fx
  ::http-request-failure
  [re-frame/trim-v]
  (fn [_ [original-event-vector]]
    ;; TODO(janherich): in case of http request failure, we will try to hit http endpoint in loop forever,
    ;; maybe it's better to cut it after N tries and display error message with explanation to user
    {:dispatch-later [{:ms 1000 :dispatch original-event-vector}]}))
