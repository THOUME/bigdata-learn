package com.undergrowth.algorithm.itemcf;

/**
 * ItemBasedCFSparkJob
 *
 * @author zhangwu
 * @version 1.0.0
 * @date 2018-10-24-15:05
 */

import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import scala.Tuple2;

/**
 * Item-Based Collaborative Filtering Spark Job <br/> use cosin similarity
 *
 * @author gaohangwang@gmail.com
 */
public class ItemBasedCFSparkJob implements Serializable {

    class CFModel {
        private final JavaPairRDD<Long, List<Tuple2<Long, Float>>> item_similaries;
        private final JavaPairRDD<Long, List<Tuple2<Long, Float>>> user_similaries;

        public CFModel(JavaPairRDD<Long, List<Tuple2<Long, Float>>> item_similaries,
            JavaPairRDD<Long, List<Tuple2<Long, Float>>> user_similaries) {
            this.item_similaries = item_similaries;
            this.user_similaries = user_similaries;
        }

        public JavaPairRDD<Long, List<Tuple2<Long, Float>>> getItem_similaries() {
            return item_similaries;
        }

        public JavaPairRDD<Long, List<Tuple2<Long, Float>>> getUser_similaries() {
            return user_similaries;
        }
    }

    class ItemPair implements Serializable {
        long a;
        long b;

        public ItemPair(long a, long b) {
            this.a = Math.min(a, b);
            this.b = Math.max(a, b);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            long result = 1;
            result = prime * result + a * 31;
            result = prime * result + b * 31;
            return (int) result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ItemPair other = (ItemPair) obj;
            if (other.a == a && other.b == b) {
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format("[%s, %s]", a, b);
        }
    }

    /**
     * @param data format: [(long userid,long itemid,float score),...]
     * @param minVisitedPerItem use to filter new item
     * @param maxPrefsPerUser use to filter too active user
     * @param minPrefsPerUser use to filter new user
     * @param maxPrefsPerUserForRec in final computer user's recommendations stage, only use top maxPrefsPerUserForRec number of prefs for each user
     * @param min_similary min similary between items
     * @return ItemBasedCFSparkJob.CFModel model
     */
    public CFModel run(JavaSparkContext jsc,
        JavaRDD<Row> data,
        final int numRecommendations,
        final boolean booleanData,
        final int minVisitedPerItem,
        final int maxPrefsPerUser,
        final int minPrefsPerUser,
        final int maxSimilaritiesPerItem,
        final int maxPrefsPerUserForRec,
        final float min_similary) {
        //collect legal user and there visited items
        Map<Long, Tuple2<Set<Long>, Set<Long>>> user_visited = data
            .filter(row -> row.getFloat(2) > 0)
            .mapToPair(row -> new Tuple2<>(row.getLong(0), new Tuple2<>(row.getLong(1), row.getFloat(2))))
            .aggregateByKey(new ArrayList<Tuple2<Long, Float>>(),
                (list, t) -> {
                    list.add(t);
                    return list;
                },
                (l1, l2) -> {
                    l1.addAll(l2);
                    return l1;
                })
            .filter(t -> t._2.size() <= maxPrefsPerUser && t._2.size() >= minPrefsPerUser)
            .mapValues(list -> {
                if (list.size() > maxPrefsPerUserForRec) {
                    list = (ArrayList<Tuple2<Long, Float>>) list.stream()
                        .sorted((a, b) -> b._2.compareTo(a._2))
                        .collect(Collectors.toList());
                }
                //items use to computer user similary items
                Set<Long> visited_a = new HashSet<>();
                //abandoned items which not use to computer user similary items
                Set<Long> visited_b = list.size() <= maxPrefsPerUserForRec ? null : new HashSet<>();
                for (int i = 0; i < list.size(); i++) {
                    if (i < maxPrefsPerUserForRec) {
                        visited_a.add(list.get(i)._1);
                    }
                    if (i >= maxPrefsPerUserForRec) {
                        visited_b.add(list.get(i)._1);
                    }
                }
                return new Tuple2<Set<Long>, Set<Long>>(visited_a, visited_b);
            })
            .collectAsMap();
        Broadcast<Map<Long, Tuple2<Set<Long>, Set<Long>>>> user_visited_bd = jsc.broadcast(new HashMap<>(user_visited));
        System.out.println("user_visited---->");
        for (Map.Entry<Long, Tuple2<Set<Long>, Set<Long>>> entry : user_visited.entrySet()) {
            System.out.println(entry.getKey() + "\t" + entry.getValue());
        }
        //collect legal items
        List<Long> legal_items = data.mapToPair(row -> new Tuple2<Long, Integer>(row.getLong(1), 1))
            .reduceByKey((a, b) -> a + b)
            .filter(t -> t._2 >= minVisitedPerItem)
            .keys()
            .collect();
        Broadcast<Set<Long>> legal_items_bd = jsc.broadcast(new HashSet<>(legal_items));

        //filter illegal user
        JavaRDD<Row> filted_data = data
            .filter(row -> user_visited_bd.getValue().containsKey(row.getLong(0))
                && legal_items_bd.getValue().contains(row.getLong(1)));
        filted_data.cache();
        System.out.println("filted_data:" + filted_data.collect().size());
        //user list that visited the same item
        JavaPairRDD<Long, Iterable<Tuple2<Long, Float>>> item_user_list = filted_data
            .mapToPair(row -> new Tuple2<>(row.getLong(1),
                new Tuple2<Long, Float>(row.getLong(0), row.getFloat(2))))
            .groupByKey();
        item_user_list.cache();
        System.out.println("item_user_list:" + item_user_list.collect().size());
        item_user_list.foreach(it -> System.out.println(it));

        //computer sqrt(|item|)
        Map<Long, Float> item_norm = item_user_list
            .mapValues(iter -> {
                float score = 0.0f;
                for (Tuple2<Long, Float> t : iter) {
                    score += t._2 * t._2;
                }
                return score;
            })
            .collectAsMap();
        Broadcast<Map<Long, Float>> item_norm_bd = jsc.broadcast(new HashMap<>(item_norm));

        //get items topN similaries
        JavaPairRDD<Long, List<Tuple2<Long, Float>>> item_similaries = filted_data
            //group items visited by the same user
            .mapToPair(row -> new Tuple2<>(row.getLong(0), new Tuple2<>(row.getLong(1), row.getFloat(2))))
            .groupByKey()
            //computer item1 * item2
            .flatMapToPair((PairFlatMapFunction<Tuple2<Long, Iterable<Tuple2<Long, Float>>>, ItemPair, Float>) t -> {
                List<Tuple2<Long, Float>> items = Lists.newArrayList(t._2);
                Set<Tuple2<ItemPair, Float>> list_set = new HashSet<>(items.size() * (items.size() - 1) / 2);
                for (Tuple2<Long, Float> i : items) {
                    for (Tuple2<Long, Float> j : items) {
                        if (i._1.longValue() == j._1.longValue()) {
                            continue;
                        }
                        list_set.add(new Tuple2<ItemPair, Float>(new ItemPair(i._1, j._1), i._2 * j._2));
                    }
                }
                return list_set.iterator();
            })
            .reduceByKey((a, b) -> a + b)
            //computer two item vector cosin: (item1 * item2) / (|item1| * |item2|)
            .mapToPair(t -> {
                ItemPair up = t._1;
                float norm_a = item_norm_bd.getValue().get(up.a);
                float norm_b = item_norm_bd.getValue().get(up.b);
                return new Tuple2<>(up, t._2 / (float) Math.sqrt(norm_a * norm_b));
            })
            .filter(t -> t._2 >= min_similary)
            //expand matrix
            .flatMapToPair((PairFlatMapFunction<Tuple2<ItemPair, Float>, Long, Tuple2<Long, Float>>) t -> {
                List<Tuple2<Long, Tuple2<Long, Float>>> list = new ArrayList<>(2);
                list.add(new Tuple2<>(t._1.a, new Tuple2<Long, Float>(t._1.b, t._2)));
                list.add(new Tuple2<>(t._1.b, new Tuple2<Long, Float>(t._1.a, t._2)));
                return list.iterator();
            })
            //sort and get topN similary items for item
            .aggregateByKey(new MinHeap(maxSimilaritiesPerItem),
                (heap, t) -> heap.add(t),
                (h1, h2) -> h1.addAll(h2))
            .mapValues(heap -> {
                List<Tuple2<Long, Float>> tops = heap.getSortedItems();
                //normalized similary
                float max = tops.get(0)._2;
                return tops.stream()
                    .map(t -> new Tuple2<>(t._1, t._2 / max))
                    .collect(Collectors.toList());
            });
        item_similaries.cache();

        //get user topN recommendtions
        JavaPairRDD<Long, Tuple2<Long, Float>> user_similaries = item_user_list
            .join(item_similaries)
            .flatMapToPair(
                (PairFlatMapFunction<Tuple2<Long, Tuple2<Iterable<Tuple2<Long, Float>>, List<Tuple2<Long, Float>>>>, Long, Tuple2<Long, Float>>) t -> {
                    Set<Tuple2<Long, Tuple2<Long, Float>>> user_similary_items = new HashSet<>();
                    Iterable<Tuple2<Long, Float>> users = t._2._1;
                    List<Tuple2<Long, Float>> items = t._2._2;
                    for (Tuple2<Long, Float> user : users) {
                        Tuple2<Set<Long>, Set<Long>> visited = user_visited_bd.getValue().get(user._1);
                        Set<Long> visited_ids = visited._1;
                        Set<Long> abandoned_ids = visited._2;
                        //filter items > maxPrefsPerUserForRec
                        if (abandoned_ids != null && abandoned_ids.contains(t._1)) {
                            continue;
                        }

                        for (Tuple2<Long, Float> item : items) {
                            if (!visited_ids.contains(item._1)) {
                                float score = item._2;
                                if (!booleanData) {
                                    score *= user._2;
                                }
                                user_similary_items.add(new Tuple2<>(user._1,
                                    new Tuple2<Long, Float>(item._1, score)));
                            }
                        }
                    }
                    return user_similary_items.iterator();
                });
        System.out.println("user_similaries---->" + user_similaries.count());
        user_similaries.take(30).forEach(t -> System.out.println("user_similaries---->" + t));
        //sum all scores
        JavaPairRDD<Long, List<Tuple2<Long, Float>>> user_similaries_1 = user_similaries.aggregateByKey(new HashMap<Long, Float>(),
            (m, item) -> {
                Float score = m.get(item._1);
                if (score == null) {
                    m.put(item._1, item._2);
                } else {
                    m.put(item._1, item._2 + score);
                }
                return m;
            },
            (m1, m2) -> {
                HashMap<Long, Float> m_big;
                HashMap<Long, Float> m_small;
                if (m1.size() > m2.size()) {
                    m_big = m1;
                    m_small = m2;
                } else {
                    m_big = m2;
                    m_small = m1;
                }
                for (Map.Entry<Long, Float> e : m_small.entrySet()) {
                    Float v = m_big.get(e.getKey());
                    if (v != null) {
                        m_big.put(e.getKey(), e.getValue() + v);
                    } else {
                        m_big.put(e.getKey(), e.getValue());
                    }
                }
                return m_big;
            })
            //sort and get topN similary items for user
            .mapValues(all_items -> {
                List<Tuple2<Long, Float>> limit_items = all_items.entrySet().stream()
                    .map(e -> new Tuple2<Long, Float>(e.getKey(), e.getValue()))
                    .sorted((a, b) -> b._2.compareTo(a._2))
                    .limit(numRecommendations)
                    .collect(Collectors.toList());
                return limit_items;
            });

        CFModel model = new CFModel(item_similaries, user_similaries_1);
        return model;
    }

    public static void main(String[] args) throws Exception {
        SparkConf sparkConf = new SparkConf().setMaster("local[2]");
        sparkConf.setAppName("ItemBasedCFSparkJob");
        JavaSparkContext jsc = new JavaSparkContext(sparkConf);
        //input userid itemid score
        JavaRDD<Row> data = jsc.textFile("data/cf_data.txt")
            .map(line -> {
                String[] info = line.split(" ");
                return RowFactory.create(Long.parseLong(info[0]),
                    Long.parseLong(info[1]),
                    Float.parseFloat(info[2]));
            });

        ItemBasedCFSparkJob job = new ItemBasedCFSparkJob();
        int numRecommendations = 100;
        boolean booleanData = false;
        int minVisitedPerItem = 1;
        int maxPrefsPerUser = 500;
        int minPrefsPerUser = 2;
        int maxSimilaritiesPerItem = 30;
        int maxPrefsPerUserForRec = 30;
        float min_similary = 0.4f;
        CFModel model = job.run(jsc,
            data,
            numRecommendations,
            booleanData,
            minVisitedPerItem,
            maxPrefsPerUser,
            minPrefsPerUser,
            maxSimilaritiesPerItem,
            maxPrefsPerUserForRec,
            min_similary);

        Map<Long, List<Tuple2<Long, Float>>> item_similaries_map = model.getItem_similaries().collectAsMap();
        System.out.println("=============== item_similaries_map ==============\n");
        for (Map.Entry e : item_similaries_map.entrySet()) {
            System.out.println(e.getKey() + "\t" + e.getValue());
        }
        System.out.println("\n================================================");

        Map<Long, List<Tuple2<Long, Float>>> user_similaries_map = model.getUser_similaries().collectAsMap();
        System.out.println("=============== user_similaries_map ==============\n");
        for (Map.Entry e : user_similaries_map.entrySet()) {
            System.out.println(e.getKey() + "\t" + e.getValue());
        }
        System.out.println("\n================================================");

    }
}