// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.nbtest

import com.microsoft.ml.spark.core.test.base.TestBase
import com.microsoft.ml.spark.nbtest.DatabricksUtilities._

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.existentials

/** Tests to validate fuzzing of modules. */
class NotebookTests extends TestBase {

  test("Databricks Notebooks") {
    val clusterId = createClusterInPool(ClusterName, PoolId)
    val jobIdsToCancel = mutable.ListBuffer[Int]()
    try {
      println("Checking if cluster is active")
      tryWithRetries(Seq.fill(60 * 15)(1000).toArray) { () =>
        assert(isClusterActive(clusterId))
      }
      println("Installing libraries")
      installLibraries(clusterId)
      tryWithRetries(Seq.fill(60 * 3)(1000).toArray) { () =>
        assert(isClusterActive(clusterId))
      }
      println(s"Creating folder $Folder")
      workspaceMkDir(Folder)

      println(s"Submitting jobs")
      val parJobIds = ParallizableNotebooks.map(uploadAndSubmitNotebook(clusterId, _))
      parJobIds.foreach(jobIdsToCancel.append(_))

      println(s"Submitted ${parJobIds.length} for execution: ${parJobIds.toList}")

      println(s"Monitoring Parallel Jobs...")
      val monitors = parJobIds.map((runId: Int) => monitorJob(runId, TimeoutInMillis, logLevel = 2))

      println(s"Awaiting parallelizable jobs...")
      val parFailures = monitors
        .map(Await.ready(_, Duration(TimeoutInMillis.toLong, TimeUnit.MILLISECONDS)).value.get)
        .filter(_.isFailure)

      println(s"Submitting nonparallelizable job...")
      val nonParFailutes = NonParallizableNotebooks.toIterator.map { nb =>
        val jid = uploadAndSubmitNotebook(clusterId, nb)
        jobIdsToCancel.append(jid)
        val monitor = monitorJob(jid, TimeoutInMillis, logLevel = 2)
        Await.ready(monitor, Duration(TimeoutInMillis.toLong, TimeUnit.MILLISECONDS)).value.get
      }.filter(_.isFailure).toArray

      assert(parFailures.isEmpty && nonParFailutes.isEmpty)
    } finally {
      jobIdsToCancel.foreach { jid =>
        println(s"Cancelling job $jid")
        cancelRun(jid)
      }
      deleteCluster(clusterId)
    }
  }

  test("SynapsePROD") {
    val workspaceName = "mmlsparkdemosynws"
    val poolName = "sparkpool1"
    val livyUrl = "https://" +
      workspaceName +
      ".dev.azuresynapse.net/livyApi/versions/2019-11-01-preview/sparkPools/" +
      poolName +
      "/batches"
    val livyBatches = LivyUtilities.NotebookFiles.map(LivyUtilities.uploadAndSubmitNotebook(livyUrl, _))
    println(s"Submitted ${livyBatches.length} jobs for execution: " +
      s"${livyBatches.map(batch => s"${batch.id} : ${batch.state}")}")
    try {
      val batchFutures = livyBatches.map((batch: LivyBatch) => {
        Future {
          if (batch.state != "success") {
            if (batch.state == "error") {
              LivyUtilities.postMortem(batch, livyUrl)
              throw new RuntimeException(s"${batch.id} returned with state ${batch.state}")
            }
            else {
              LivyUtilities.retry(batch.id, livyUrl, LivyUtilities.TimeoutInMillis, System.currentTimeMillis())
            }
          }
        }(ExecutionContext.global)
      })

      val failures = batchFutures
        .map(Await.ready(_, Duration(LivyUtilities.TimeoutInMillis.toLong, TimeUnit.MILLISECONDS)).value.get)
        .filter(_.isFailure)
      assert(failures.isEmpty)
    }
    catch {
      case t: Throwable =>
        livyBatches.foreach { batch =>
          println(s"Cancelling job ${batch.id}")
          LivyUtilities.cancelRun(livyUrl, batch.id)
        }
        throw t
    }
  }

  ignore("list running jobs for convenievce") {
    val obj = databricksGet("jobs/runs/list?active_only=true&limit=1000")
    println(obj)
  }

}
