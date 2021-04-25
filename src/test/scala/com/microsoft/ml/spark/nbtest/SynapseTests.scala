// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.nbtest

import com.microsoft.ml.spark.build.BuildInfo
import com.microsoft.ml.spark.core.env.FileUtilities
import com.microsoft.ml.spark.core.test.base.TestBase
import com.microsoft.ml.spark.nbtest.SynapseUtilities.{exec, listPythonFiles}

import java.io.File
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.existentials
import scala.sys.process.Process

/** Tests to validate fuzzing of modules. */
class SynapseTests extends TestBase {

  test("SynapsePPE") {
    val workspaceName = "wenqxsynapseppe"
    val poolName = "spark3pool"
    val livyUrl = "https://" +
      workspaceName +
      ".dev.azuresynapse-dogfood.net/livyApi/versions/2019-11-01-preview/sparkPools/" +
      poolName +
      "/batches"

    val os = sys.props("os.name").toLowerCase
    os match {
      case x if x contains "windows" =>
        exec("conda activate mmlspark && jupyter nbconvert --to script .\\notebooks\\samples\\*.ipynb")
      case _ =>
        Process(s"conda init bash; conda activate mmlspark; jupyter nbconvert --to script ./notebooks/samples/*.ipynb")
    }

    listPythonFiles().map(f => {
      val newPath = f
        .replace(" ", "")
        .replace("-", "")
      new File(f).renameTo(new File(newPath))
    })

    val livyBatches = SynapseUtilities.listPythonFiles()
      .filterNot(_.contains(" "))
      .filterNot(_.contains("-"))
      .map(f => {
        val livyBatch: LivyBatch = SynapseUtilities.uploadAndSubmitNotebook(livyUrl, f)
        SynapseUtilities.monitorJob(livyBatch, livyUrl)
      })

    try {
      val batchFutures: Array[Future[Any]] = livyBatches.map((batch: LivyBatch) => {
        Future {
          if (batch.state != "success") {
            if (batch.state == "error") {
              SynapseUtilities.postMortem(batch, livyUrl)
              throw new RuntimeException(s"${batch.id} returned with state ${batch.state}")
            }
            else {
              SynapseUtilities.retry(batch.id, livyUrl, SynapseUtilities.TimeoutInMillis, System.currentTimeMillis())
            }
          }
        }(ExecutionContext.global)
      })

      val failures = batchFutures
        .map(f => Await.ready(f, Duration(SynapseUtilities.TimeoutInMillis.toLong, TimeUnit.MILLISECONDS)).value.get)
        .filter(f => f.isFailure)
      assert(failures.isEmpty)
    }
    catch {
      case t: Throwable =>
        livyBatches.foreach { batch =>
          println(s"Cancelling job ${batch.id}")
          SynapseUtilities.cancelRun(livyUrl, batch.id)
        }
        throw t
    }
  }
}
