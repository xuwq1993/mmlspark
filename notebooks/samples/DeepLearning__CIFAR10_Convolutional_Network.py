#!/usr/bin/env python
# coding: utf-8

# ## 301 - Ingesting CIFAR Images into Spark DataFrames and Evaluating Pre-Trained CNTK Models

# In[ ]:


from mmlspark.cntk import CNTKModel
from mmlspark.downloader import ModelDownloader
from pyspark.sql.functions import udf
from pyspark.sql.types import IntegerType
from os.path import abspath


# Set some paths.

# In[ ]:


cdnURL = "https://mmlspark.azureedge.net/datasets"

# Please note that this is a copy of the CIFAR10 dataset originally found here:
# http://www.cs.toronto.edu/~kriz/cifar-10-python.tar.gz
imagesWithLabels = spark.read.parquet("wasbs://publicwasb@mmlspark.blob.core.windows.net/CIFAR10_test.parquet")


# In[ ]:


modelName = "ConvNet"
modelDir = "dbfs:///models/"


# Get the model

# In[ ]:


d = ModelDownloader(spark, modelDir)
model = d.downloadByName(modelName)


# Evaluate CNTK model.

# In[ ]:


import time
start = time.time()

# Use CNTK model to get log probabilities
cntkModel = CNTKModel().setInputCol("images").setOutputCol("output")                        .setModelLocation(model.uri).setOutputNode("z")
scoredImages = cntkModel.transform(imagesWithLabels)

# Transform the log probabilities to predictions
def argmax(x): return max(enumerate(x),key=lambda p: p[1])[0]

argmaxUDF = udf(argmax, IntegerType())
imagePredictions = scoredImages.withColumn("predictions", argmaxUDF("output"))                                .select("predictions", "labels")

numRows = imagePredictions.count()

end = time.time()
print("classifying {} images took {} seconds".format(numRows,end-start))


# Plot confusion matrix.

# In[ ]:


imagePredictions = imagePredictions.toPandas()
y, y_hat = imagePredictions["labels"], imagePredictions["predictions"]


# In[ ]:


import matplotlib.pyplot as plt
import numpy as np
from sklearn.metrics import confusion_matrix

cm = confusion_matrix(y, y_hat)

labels = ["airplane", "automobile", "bird", "cat", "deer", "dog", "frog",
          "horse", "ship", "truck"]
plt.imshow(cm, interpolation="nearest", cmap=plt.cm.Blues)
plt.colorbar()
tick_marks = np.arange(len(labels))
plt.xticks(tick_marks, labels, rotation=90)
plt.yticks(tick_marks, labels)
plt.xlabel("Predicted label")
plt.ylabel("True Label")
display(plt.show())

