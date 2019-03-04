/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.database.cosmosdb.cache

import akka.event.slf4j.SLF4JLogging
import com.microsoft.azure.documentdb.Document
import org.apache.openwhisk.core.database.CacheInvalidationMessage
import org.apache.openwhisk.core.entity.CacheKey
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBUtil.unescapeId
import scala.collection.immutable.Seq

class WhisksCacheEventProducer extends BaseObserver {
  import CacheEventProducer._
  override def process(docs: Seq[Document]): Unit = {
    val msgs = docs.map { doc =>
      val id = unescapeId(doc.getId)
      log.debug("Changed doc [{}]", id)
      val event = CacheInvalidationMessage(CacheKey(id), instanceId)
      event.serialize
    }

    kafka.send(msgs) //TODO Await on returned future
  }
}

object CacheEventProducer extends SLF4JLogging {
  val instanceId = "cache-invalidator"
  private var _kafka: KafkaEventProducer = _

  def kafka: KafkaEventProducer = {
    require(_kafka != null, "KafkaEventProducer yet not initialized")
    _kafka
  }

  def kafka_=(kafka: KafkaEventProducer): Unit = _kafka = kafka
}
