(ns onyx.scheduling.percentage-task-scheduler
  (:require [onyx.scheduling.common-task-scheduler :as cts]
            [onyx.log.commands.common :as common]))

(defn tasks-by-pct [replica job tasks]
  (map
    (fn [t]
      {:task t :pct (get-in replica [:task-percentages job t])})
    tasks))

(defn rescale-task-percentages 
  "Rescale task percentages after saturated tasks were removed"
  [tasks]
  (let [total (/ (apply + (map :pct tasks)) 100)]
    (map (fn [task]
           (update-in task [:pct] / total))
         tasks))) 

(defn largest-remainder-allocations 
  "Allocates remaining peers to the tasks with the largest remainder.
  e.g. 3 tasks pct allocated 3.5, 1.75, 1.75 -> 3, 2, 2"
  [tasks n-peers]
  (let [tasks* (rescale-task-percentages tasks)
        unrounded (map (fn [task] 
                         (* 0.01 (:pct task) n-peers))
                       tasks*)
        full (map int unrounded) 
        taken (apply + full)
        remaining (- n-peers taken)
        full-allocated (zipmap tasks* full)
        remainders (->> (map (fn [task v] 
                               (vector task (- v (int v)))) 
                             tasks*
                             unrounded)
                        (sort-by second)
                        (reverse)
                        (take remaining)
                        (map (juxt first (constantly 1)))
                        (into {}))
        final-allocations (merge-with + full-allocated remainders)]
    (mapv (fn [[task allocation]] 
            (assoc task :allocation (int allocation)))
          final-allocations)))

(defn percentage-balanced-taskload 
  "Percentage balance taskload by allocating via largest remainders.
  If a task becomes oversaturated, take it out of the pool, fully allocated,
  and restart the process with the remaining peers and tasks"
  [replica job candidate-tasks n-peers]
  {:post [(>= n-peers 0)
          (= n-peers (reduce + (map :allocation (vals %))))]}
  (let [sorted-tasks (tasks-by-pct replica job candidate-tasks)
        allocations (largest-remainder-allocations sorted-tasks n-peers)
        oversaturated (filter (fn [{:keys [task allocation]}]
                                (> allocation (get-in replica [:task-saturation job task])))
                              allocations)
        cutoff-oversaturated (->> oversaturated
                                  (map (fn [{:keys [task] :as t}]
                                         [task (assoc t :allocation (get-in replica [:task-saturation job task]))]))
                                  (into {}))]
    (if (empty? cutoff-oversaturated)
      (into {} (map (fn [t] {(:task t) t}) allocations))
      (let [n-peers-fully-saturated (apply + (map :allocation (vals cutoff-oversaturated)))
            n-remaining-peers (- n-peers n-peers-fully-saturated)
            unallocated-tasks (remove cutoff-oversaturated candidate-tasks)] 
        (merge (percentage-balanced-taskload replica job unallocated-tasks n-remaining-peers)
               cutoff-oversaturated)))))

(defmethod cts/drop-peers :onyx.task-scheduler/percentage
  [replica job n]
  (let [tasks (keys (get-in replica [:allocations job]))
        balanced (percentage-balanced-taskload replica job tasks n)]
    (mapcat
      (fn [[task {:keys [allocation]}]]
        (drop-last allocation (get-in replica [:allocations job task])))
      balanced)))

(defmethod cts/task-distribute-peer-count :onyx.task-scheduler/percentage
  [replica job n]
  (->> (percentage-balanced-taskload replica job (get-in replica [:tasks job]) n)
       vals
       (map (juxt :task :allocation))
       (into {})))
