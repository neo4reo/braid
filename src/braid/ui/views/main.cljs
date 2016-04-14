(ns braid.ui.views.main
  (:require [chat.client.reagent-adapter :refer [subscribe]]
            [braid.ui.views.error-banner :refer [error-banner-view]]
            [braid.ui.views.sidebar :refer [sidebar-view]]
            [braid.ui.views.header :refer [header-view]]
            [braid.ui.views.pages.inbox :refer [inbox-page-view]]
            [braid.ui.views.pages.recent :refer [recent-page-view]]
            [braid.ui.views.pages.help :refer [help-page-view]]
            [braid.ui.views.pages.users :refer [users-page-view]]
            [braid.ui.views.pages.user :refer [user-page-view]]
            [braid.ui.views.pages.search :refer [search-page-view]]
            [braid.ui.views.pages.tag :refer [tag-page-view]]
            [braid.ui.views.pages.tags :refer [tags-page-view]]
            [braid.ui.views.pages.me :refer [me-page-view]]
            [braid.ui.views.pages.group-explore :refer [group-explore-page-view]]))

(defn main-view []
  (let [page (subscribe [:page])]
    (fn [_]
      [:div.main
       [error-banner-view]
       [sidebar-view]
       [header-view]
       (case (@page :type)
         :inbox [inbox-page-view]
         :recent [recent-page-view]
         :help [help-page-view]
         :users [users-page-view]
         :search [search-page-view]
         :tag [tag-page-view]
         :user [user-page-view]
         :tags [tags-page-view]
         :me [me-page-view]
         :group-explore [group-explore-page-view])])))
