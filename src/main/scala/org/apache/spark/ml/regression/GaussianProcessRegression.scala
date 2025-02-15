package org.apache.spark.ml.regression

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import breeze.linalg.{DenseVector => BDV, _}
import org.apache.hadoop.fs.Path
import org.apache.spark.internal.Logging
import org.apache.spark.ml.commons._
import org.apache.spark.ml.commons.kernel.Kernel
import org.apache.spark.ml.commons.util._
import org.apache.spark.ml.feature.LabeledPoint
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.{Identifiable, MLReader, MLWritable, MLWriter}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Dataset
import org.json4s.DefaultFormats

/**
  * Gaussian Process Regression.
  *
  * Fitting of hyperparameters and prediction for GPR is infeasible for large datasets due to
  * high computational complexity O(N^3^).
  *
  * This implementation relies on the Bayesian Committee Machine proposed in [2] for fitting and on
  * Projected Process Approximation for prediction Chapter 8.3.4 [1].
  *
  * This way the linear complexity in sample size is achieved for fitting,
  * while prediction complexity doesn't depend on it.
  *
  * [1] Carl Edward Rasmussen and Christopher K. I. Williams. 2005. Gaussian Processes for Machine Learning
  * (Adaptive Computation and Machine Learning). The MIT Press.
  *
  * [2] Marc Peter Deisenroth and Jun Wei Ng. 2015. Distributed Gaussian processes.
  * In Proceedings of the 32nd International Conference on International Conference on Machine Learning
  * Volume 37 (ICML'15), Francis Bach and David Blei (Eds.), Vol. 37. JMLR.org 1481-1490.
  *
  */
class GaussianProcessRegression(override val uid: String)
  extends Regressor[Vector, GaussianProcessRegression, GaussianProcessRegressionModel]
    with GaussianProcessParams
    with GaussianProcessCommons[Vector, GaussianProcessRegression, GaussianProcessRegressionModel]
    with Logging {

  def this() = this(Identifiable.randomUID("gaussProcessReg"))

  override protected def train(dataset: Dataset[_]): GaussianProcessRegressionModel = {

    val points: RDD[LabeledPoint] = getPoints(dataset).cache()

    val expertLabelsAndKernels: RDD[(BDV[Double], Kernel)] = getExpertLabelsAndKernels(points).cache()

    val optimalHyperparameters = optimizeHypers(expertLabelsAndKernels, likelihoodAndGradient)

    expertLabelsAndKernels.foreach(_._2.setHyperparameters(optimalHyperparameters))

    produceModel(points, expertLabelsAndKernels, optimalHyperparameters)
  }

  private def likelihoodAndGradient(yAndK: (BDV[Double], Kernel), x: BDV[Double]) = {
    val (y: BDV[Double], kernel: Kernel) = yAndK
    kernel.setHyperparameters(x)
    val (k, derivative) = kernel.trainingKernelAndDerivative()
    val (_, logdet, kinv) = logDetAndInv(k)
    val alpha = kinv * y
    val likelihood = 0.5 * (y.t * alpha) + 0.5 * logdet

    val alphaAlphaTMinusKinv = alpha * alpha.t
    alphaAlphaTMinusKinv -= kinv

    val gradient = derivative.map(derivative => -0.5 * sum(derivative *= alphaAlphaTMinusKinv))
    (likelihood, BDV(gradient: _*))
  }

  override def copy(extra: ParamMap): GaussianProcessRegression = defaultCopy(extra)

  override protected def createModel(uid: String, rawPredictor: GaussianProjectedProcessRawPredictor): GaussianProcessRegressionModel = new GaussianProcessRegressionModel(uid, rawPredictor)


}

class GaussianProcessRegressionModelReader extends MLReader[GaussianProcessRegressionModel] {
  override def load(path: String): GaussianProcessRegressionModel = {
    implicit val sparkContext = super.sparkSession.sparkContext
    val dataPath = new Path(path, "data")
    val inputStream = dataPath.getFileSystem(sc.hadoopConfiguration).open(dataPath)

    val length = inputStream.readInt()
    val bytes = new Array[Byte](length)

    println(length)

    inputStream.readFully(bytes)

    val ois = new ObjectInputStream(new ByteArrayInputStream(bytes))
    val value = ois.readObject
    inputStream.close()
    value.asInstanceOf[GaussianProcessRegressionModel]
  }
}

class GaussianProcessRegressionModel private[regression](override val uid: String,
                                                         private val gaussianProjectedProcessRawPredictor: GaussianProjectedProcessRawPredictor)
  extends RegressionModel[Vector, GaussianProcessRegressionModel]
    with MLWritable {

  override def predict(features: Vector): Double = {
    gaussianProjectedProcessRawPredictor.predict(features)._1
  }

  override def copy(extra: ParamMap): GaussianProcessRegressionModel = {
    val newModel = copyValues(new GaussianProcessRegressionModel(uid, gaussianProjectedProcessRawPredictor), extra)
    newModel.setParent(parent)
  }


  class GaussianProcessRegressionModelWriter(model : GaussianProcessRegressionModel) extends MLWriter {
    override protected def saveImpl(path: String): Unit = {

      implicit val sc = super.sparkSession.sparkContext

      val dataPath = new Path(path, "data")
      val outputStream = dataPath.getFileSystem(sc.hadoopConfiguration).create(dataPath)

      val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
      val oos = new ObjectOutputStream(stream)
      oos.writeObject(model)

      val modelByteArray = stream.toByteArray

      outputStream.writeInt(modelByteArray.length)
      outputStream.write(modelByteArray)
      oos.close()
      outputStream.close()
    }
  }

  def write: MLWriter = new GaussianProcessRegressionModelWriter(this)
}



