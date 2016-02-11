spark-submit --class ClusterContextClueAggregator --master=yarn-cluster --queue=shortrunning --num-executors 200 --driver-memory 7g --executor-memory 7g ~/noun-sense-induction-scala/bin/spark/nsi_2.10-0.0.1.jar ukwac/senses/senses-ukwac-dep-cw-e0-N200-n200-minsize5-js-format.csv ukwac/output/Holing-dependency_Lemmatize-true_Coocs-false_MaxLen-110_NounsOnly-false_NounNounOnly-false_Semantify-true/W-* ukwac/output/Holing-dependency_Lemmatize-true_Coocs-false_MaxLen-110_NounsOnly-false_NounNounOnly-false_Semantify-true/F-* ukwac/output/Holing-dependency_Lemmatize-true_Coocs-false_MaxLen-110_NounsOnly-false_NounNounOnly-false_Semantify-true/WF-* ukwac/agg/1 100 deps 10000 2