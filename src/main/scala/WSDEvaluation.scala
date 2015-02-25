import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._

object WSDMode extends Enumeration {
    type WSDMode = Value
    val Product, Maximum, Average = Value
}

object WSDEvaluation {

    /*def computeFeatureProb(featureArr:Array[String], clusterSize:Int): (String, Double, Double) = {
        val feature = featureArr(0)
        if (featureArr.length != 8)
            return (feature, 0, 0)
        //val lmi     = featureArr(1).toFloat
        //val avgProb = featureArr(2).toFloat // (p(w1|f) + .. + p(wn|f)) / n
        //val avgCov  = featureArr(3).toFloat // (p(f|w1) + .. + p(f|wn)) / n
        val sc      = featureArr(4).toLong  // c(w1) + .. + c(wn) = c(s)
        val fc      = featureArr(5).toLong  // c(f)
        //val avgWc   = wc.toFloat / clusterSize // c(s) / n = (c(w1) + .. + c(wn)) / n
        val sfc     = featureArr(6).toLong // c(s,f)
        //val totalNumObservations = featureArr(7).toLong
        //val normalizedAvgWfc = avgCov * avgWc // (p(f|w1) + .. + p(f|wn))/n * c(s)/n = (c(w1,f) + .. + c(wn,f))/n = c(s,f)/n
        //val score = (normalizedAvgWfc * normalizedAvgWfc) / (avgWc * fc)
        //val pmi = (avgProb * totalNumObservations) / wc; // p(w|f)*n / c(w) = c(w|f) / c(w) = pmi(w,f)
        (feature, 0, 0)
    }

    def computeFeatureProbs(featuresWithValues:Array[String], numFeatures:Int, clusterSize:Int): Map[String, Double] = {
        featuresWithValues
            .map(featureArr => computeFeatureProb(featureArr.split(":"), clusterSize))
            .sortBy({case (feature, avgProb, score) => score})
            .reverse
            .take(numFeatures)
            .map({case (feature, avgProb, score) => (feature, avgProb)})
            .toMap
    }*/

    /**
     * Computes the NMI (normalized mutual information) of two clustering of the same N documents.
     * A clustering here is an aggregation of documents into multiple sets (clusters).
     * @tparam D    Type of document representation (e.g. Int or Long as document IDs)
     * @param clustering1 First clustering
     * @param clustering2 Second clustering
     * @param N     Number of documents
     * @return      NMI of both clusterings
     */
    def nmi[D](clustering1:Seq[Set[D]], clustering2:Seq[Set[D]], N:Int): Double = {
        var H1 = 0.0
        var H2 = 0.0
        var sum1 = 0
        var sum2 = 0
        for (docIDs <- clustering1) {
            val n = docIDs.size
            sum1 += n
            val p = n.toDouble / N.toDouble
            H1 -= p * math.log(p)
        }
        for (docIDs <- clustering2) {
            val n = docIDs.size
            sum2 += n
            val p = n.toDouble / N.toDouble
            H2 -= p * math.log(p)
        }
        if (sum1 != N || sum2 != N)
            return -1.0

        var I = 0.0
        for (docIDs1 <- clustering1; docIDs2 <- clustering2) {
            val p_ij = docIDs1.intersect(docIDs2).size / N.toDouble
            val p_i = docIDs1.size / N.toDouble
            val p_j = docIDs2.size / N.toDouble

            if (p_ij != 0) {
                I += p_ij * math.log(p_ij / (p_i * p_j))
            }
        }

        2*I / (H1 + H2)
    }

    def main(args: Array[String]) {
        if (args.size < 6) {
            println("Usage: WSDEvaluation clusters-with-coocs clusters-with-deps linked-sentences-tokenized output prob-smoothing-addend wsd-mode")
            return
        }

        val conf = new SparkConf().setAppName("WSDEvaluation")
        val sc = new SparkContext(conf)

        val clusterFileCoocs = sc.textFile(args(0))
        val clusterFileDeps = if (args.size == 7) sc.textFile(args(1)) else null
        val sentFile = sc.textFile(args(args.size - 5))
        val outputFile = args(args.size - 4)
        val alpha = args(args.size - 3).toDouble
        val wsdMode = WSDMode.withName(args(args.size - 2))
        val usePriorProbs:Boolean = args(args.size - 1).equals("y")
        //val minClusterSize = args(5).toInt
        //val maxNumClusters = args(6).toInt
        //val featureCol = args(5).toInt
        //val numFeatures = args(3).toInt
        //val minPMI = args(4).toDouble
        //val multiplyScores = args(5).toBoolean

        val sentLinkedTokenized = sentFile
            .map(line => line.split("\t", -1)) // -1 means "do not drop empty entries"
            .zipWithIndex()               // (lemma,       (sentId, target,      features))
            .map({case (sentLine, sentId) => (sentLine(0), (sentId, sentLine(1), sentLine(2).split(" ") ++ sentLine(3).split(" ")))})
            .cache()

        // (lemma, (sense -> (feature -> prob)))
        val clustersWithCoocs:RDD[(String, Map[Int, (Double, Int, Map[String, Double])])] = clusterFileCoocs
            .map(line => line.split("\t"))
            .map({case Array(lemma, sense, senseLabel, senseCount, simWords, featuresWithValues) => (lemma, sense.toInt, senseCount.toDouble, simWords.split("  "), featuresWithValues.split("  "))})
            //.filter({case (lemma, sense, senseCount, simWords, featuresWithValues) => simWords.size >= minClusterSize})
            .map({case (lemma, sense, senseCount, simWords, featuresWithValues) => (lemma, (sense, (senseCount, simWords.size, WSD.computeFeatureProbs(lemma, featuresWithValues, simWords.size, senseCount))))})
            .groupByKey()
            .mapValues(clusters => /*pruneClusters(clusters, maxNumClusters)*/clusters.toMap)

        var clustersWithDeps: RDD[(String, Map[Int, (Double, Int, Map[String, Double])])] = null
        if (clusterFileDeps != null) {
            // (lemma, (sense -> (feature -> prob)))
            clustersWithDeps = clusterFileDeps
                .map(line => line.split("\t"))
                .map({ case Array(lemma, sense, senseLabel, senseCount, simWords, featuresWithValues) => (lemma, sense.toInt, senseCount.toDouble, simWords.split("  "), featuresWithValues.split("  "))})
                //.filter({case (lemma, sense, senseCount, simWords, featuresWithValues) => simWords.size >= minClusterSize})
                .map({ case (lemma, sense, senseCount, simWords, featuresWithValues) => (lemma, (sense, (senseCount, simWords.size, WSD.computeFeatureProbs(lemma, featuresWithValues, simWords.size, senseCount))))})
                .groupByKey()
                .mapValues(clusters => /*pruneClusters(clusters, maxNumClusters)*/ clusters.toMap)
        }

        var sentLinkedTokenizedContextualized: RDD[(String, Long, String, Int, Array[String])] = null
        if (clustersWithDeps != null) {
            sentLinkedTokenizedContextualized = sentLinkedTokenized
                .join(clustersWithCoocs)
                .join(clustersWithDeps)
                .map({case (lemma, (((sentId, target, tokens), senseInfoCoocs), senseInfoDeps)) => (lemma, sentId, target, WSD.chooseSense(tokens.toSet, senseInfoCoocs, senseInfoDeps, alpha, wsdMode, usePriorProbs), tokens)})
                .cache()
        } else {
            sentLinkedTokenizedContextualized = sentLinkedTokenized
                .join(clustersWithCoocs)
                .map({case (lemma, ((sentId, target, tokens), senseInfoCoocs)) => (lemma, sentId, target, WSD.chooseSense(tokens.toSet, senseInfoCoocs, null, alpha, wsdMode, usePriorProbs), tokens)})
                .cache()
        }

        val goldClustering = sentLinkedTokenizedContextualized
            .map({case (lemma, sentId, target, sense, tokens) => ("NMI", (sentId, (lemma, target)))})
            .groupByKey()
            .mapValues(WSD.mappingToClusters)

        val testClustering = sentLinkedTokenizedContextualized
            .map({case (lemma, sentId, target, sense, tokens) => ("NMI", (sentId, (lemma, sense)))})
            .groupByKey()
            .mapValues(WSD.mappingToClusters)

        val nmiScores = goldClustering.join(testClustering)
            .mapValues(clusterings => nmi(clusterings._1, clusterings._2, 100000))

        nmiScores.map({case (_, nmiScore) => nmiScore})
                 .saveAsTextFile(outputFile + "/NMI")

        sentLinkedTokenizedContextualized
            .map({case (lemma, sentId, target, sense, tokens) => lemma + "\t" + target + "\t" + sense + "\t" + tokens.mkString(" ")})
            .saveAsTextFile(outputFile + "/Contexts")

        val senseTargetCounts = sentLinkedTokenizedContextualized
            .map({case (lemma, sentId, target, sense, tokens) => ((lemma, target, sense), 1)})
            .reduceByKey(_+_)
            .cache()



        // SENSE -> MOST FREQ. TARGET
        val targetsPerSense = senseTargetCounts
            .map({case ((lemma, target, sense), count) => ((lemma, sense), (target, count))})
            .groupByKey()
            .map({case ((lemma, sense), targetCounts) => ((lemma, sense), targetCounts.toArray.sortBy(_._2).reverse)})
            .cache()

        targetsPerSense
            .map({case ((lemma, sense), targetCounts) => lemma + "\t" + sense + "\t" + targetCounts.map(targetCount => targetCount._1 + ":" + targetCount._2).mkString("  ")})
            .saveAsTextFile(outputFile + "/TargetsPerSense")

        val targetsPerSenseResults = targetsPerSense
            .map({case ((lemma, sense), targetCounts) => (lemma, WSD.computeMatchingScore(targetCounts))})
            .reduceByKey({case ((correct1, total1), (correct2, total2)) => (correct1+correct2, total1+total2)})
            .cache()

        targetsPerSenseResults
            .map({case (lemma, (correct, total)) => lemma + "\t" + correct.toDouble / total + "\t" + correct + "/" + total})
            .saveAsTextFile(outputFile + "/TargetsPerSense__Results")

        targetsPerSenseResults
            .map({case (lemma, (correct, total)) => ("TOTAL", (correct, total))})
            .reduceByKey({case ((correct1, total1), (correct2, total2)) => (correct1+correct2, total1+total2)})
            .map({case (lemma, (correct, total)) => lemma + "\t" + correct.toDouble / total + "\t" + correct + "/" + total})
            .saveAsTextFile(outputFile + "/TargetsPerSense__ResultsAggregated")



        // TARGET -> MOST FREQ. SENSE
        val sensesPerTarget = senseTargetCounts
            .map({case ((lemma, target, sense), count) => ((lemma, target), (sense, count))})
            .groupByKey()
            .map({case ((lemma, target), senseCounts) => ((lemma, target), senseCounts.toArray.sortBy(_._2).reverse)})
            .cache()

        sensesPerTarget
            .map({case ((lemma, target), senseCounts) => lemma + "\t" + target + "\t" + senseCounts.map(senseCount => senseCount._1 + ":" + senseCount._2).mkString("  ")})
            .saveAsTextFile(outputFile + "/SensesPerTarget")

        val sensesPerTargetResults = sensesPerTarget
            .map({case ((lemma, target), senseCounts) => (lemma, WSD.computeMatchingScore(senseCounts))})
            .reduceByKey({case ((correct1, total1), (correct2, total2)) => (correct1+correct2, total1+total2)})
            .cache()

        sensesPerTargetResults
            .map({case (lemma, (correct, total)) => lemma + "\t" + correct.toDouble / total + "\t" + correct + "/" + total})
            .saveAsTextFile(outputFile + "/SensesPerTarget__Results")

        sensesPerTargetResults
            .map({case (lemma, (correct, total)) => ("TOTAL", (correct, total))})
            .reduceByKey({case ((correct1, total1), (correct2, total2)) => (correct1+correct2, total1+total2)})
            .map({case (lemma, (correct, total)) => lemma + "\t" + correct.toDouble / total + "\t" + correct + "/" + total})
            .saveAsTextFile(outputFile + "/SensesPerTarget__ResultsAggregated")
    }
}
