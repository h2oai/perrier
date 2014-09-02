package org.apache.spark.h2o

import org.apache.spark.annotation.AlphaComponent
import org.apache.spark.rdd.{H2ORDD, RDD}
import org.apache.spark.sql.catalyst.types.{BinaryType, DataType, DoubleType, FloatType, IntegerType}
import org.apache.spark.sql.{Row, SchemaRDD}
import org.apache.spark.{SparkContext, TaskContext}
import water.Key
import water.fvec.DataFrame

import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
 * Simple H2O context motivated by SQLContext.
 *
 * Doing - implicit conversion from RDD -> H2OLikeRDD
 */
@AlphaComponent
class H2OContext(@transient val sparkContext: SparkContext)
  extends org.apache.spark.Logging
  with H2OConf
  with Serializable {
}

object H2OContext {

  def colsFromType[A: TypeTag]() : Array[String] = {
    // Pull out the RDD members; the names become H2O column names; the type guide conversion
    val tt = typeOf[A].members.sorted.filter(!_.isMethod).toArray
    tt.map(_.name.toString.trim)
  }

  // Convert a Spark RDD[Product] to an H2O DataFrame.
  def toDataFrame[A <: Product: TypeTag: ClassTag](sc: SparkContext, rdd: RDD[A]): DataFrame = {
    // Pull out the RDD members; the names become H2O column names; the type guide conversion
    val tt = typeOf[A].members.sorted.filter(!_.isMethod).toArray
    val names = tt.map(_.name.toString.trim)
    
    // Make an H2O data Frame - but with no backing data (yet)
    val key = Key.rand
    val fr = new water.fvec.Frame(key)
    fr.preparePartialFrame(names)

    // Parallel-fill the H2O data Chunks
    val rows = sc.runJob(rdd, perPartition[A](key) _) // eager, not lazy, evaluation
    val res = new Array[Long](rdd.partitions.size)
    rows.foreach{ case(cidx,nrows) => res(cidx) = nrows }
    
    // Add Vec headers per-Chunk, and finalize the H2O Frame
    fr.finalizePartialFrame(res)
    new DataFrame(fr)
  }
  
  private def perPartition[A <: Product: ClassTag]( keyStr : String ) ( context: TaskContext, it: Iterator[A] ): (Int,Long) = {
    // An array of H2O NewChunks; A place to record all the data in this partition
    val nchks = water.fvec.Frame.createNewChunks(keyStr,context.partitionId)
    it.foreach(prod => { // For all rows...
      for( i <- 0 until prod.productArity ) { // For all fields...
        val fld = prod.productElement(i)
        nchks(i).addNum({ // Copy numeric data from fields to NewChunks
          val x = fld match { case Some(n) => n; case _ => fld }
          x match {
            case n: Number => n.doubleValue
            case n: Boolean => if (n) 1 else 0
            case _ => Double.NaN
          }
        })
      }
    })
    // Compress & write out the Partition/Chunks
    water.fvec.Frame.closeNewChunks(nchks)
    // Return Partition# and rows in this Partition
   (context.partitionId,nchks(0).len)
  }

  // Convert from a Spark SchemaRDD to an H2O DataFrame
  def toDataFrame(sc: SparkContext, rdd: SchemaRDD) : DataFrame = {
    val names = rdd.schema.fieldNames.toArray
    val types = rdd.schema.fields.map( field => dataTypeToClass(field.dataType) ).toArray

    // Make an H2O data Frame - but with no backing data (yet)
    val key = Key.rand
    val fr = new water.fvec.Frame(key)
    fr.preparePartialFrame(names)

    val rows = sc.runJob(rdd, perSQLPartition(rdd, key, types) _) // eager, not lazy, evaluation
    val res = new Array[Long](rdd.partitions.size)
    rows.foreach{ case(cidx,nrows) => res(cidx) = nrows }

    // Add Vec headers per-Chunk, and finalize the H2O Frame
    fr.finalizePartialFrame(res)
    new DataFrame(fr)
  }

  private def dataTypeToClass(dt : DataType):Class[_] = dt match {
    case BinaryType  => classOf[Integer]
    case IntegerType => classOf[Integer]
    case FloatType   => classOf[java.lang.Float]
    case DoubleType  => classOf[java.lang.Double]
    case _ => throw new IllegalArgumentException(s"Unsupported type $dt")
  }

  private def perSQLPartition( rdd: RDD[_], keystr: String, types: Array[Class[_]] ) ( context: TaskContext, it: Iterator[Row] ): (Int,Long) = {
    val nchks = water.fvec.Frame.createNewChunks(keystr,context.partitionId)
    it.foreach(row => {
      for( i <- 0 until types.length) {
        nchks(i).addNum(
          if (row.isNullAt(i)) Double.NaN
          else row(i).asInstanceOf[Double]
        )
      }
    })
    // Compress & write out the Partition/Chunks
    water.fvec.Frame.closeNewChunks(nchks)
    // Return Partition# and rows in this Partition
    (context.partitionId,nchks(0).len)
  }

  def toRDD[A <: Product: TypeTag: ClassTag]( sc : SparkContext, fr : DataFrame ) : RDD[A] = new H2ORDD[A](sc,fr)
}
