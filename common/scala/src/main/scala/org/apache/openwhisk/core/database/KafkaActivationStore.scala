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

package org.apache.openwhisk.core.database

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.connector.{MessageProducer, MessagingProvider, ResultMessage}
import org.apache.openwhisk.core.entity.{ActivationEntityLimit, ByteSize, DocInfo, WhiskActivation}
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.core.entity.size._
import pureconfig.loadConfigOrThrow

import scala.concurrent.Future
import scala.util.Success

case class KafkaActivationStoreConfig(activationsTopic: String, db: Boolean, maxRequestSize: Option[ByteSize])

class KafkaActivationStore(producer: MessageProducer,
                           config: KafkaActivationStoreConfig,
                           actorSystem: ActorSystem,
                           actorMaterializer: ActorMaterializer,
                           logging: Logging)
    extends ArtifactActivationStore(actorSystem, actorMaterializer, logging) {

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    if (config.db) {
      sendToKafka(activation).flatMap(_ => super.store(activation, context))
    } else {
      sendToKafka(activation)
    }
  }

  private def sendToKafka(activation: WhiskActivation)(implicit transid: TransactionId) = {
    val msg = ResultMessage(transid, activation)
    producer
      .send(topic = config.activationsTopic, msg)
      .andThen {
        case Success(_) =>
          logging.info(
            this,
            s"posted activation to Kafka topic ${config.activationsTopic} - ${activation.activationId}")
      }
      // returned DocInfo is currently not consumed anyway. So returning an info without the revId
      .map(_ => DocInfo(activation.docid))
  }
}

object KafkaActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem,
                        actorMaterializer: ActorMaterializer,
                        logging: Logging): ActivationStore = {
    val storeConfig = loadConfigOrThrow[KafkaActivationStoreConfig](ConfigKeys.kafkaActivationStore)
    val producer = createProducer(storeConfig.maxRequestSize)(logging, actorSystem)
    new KafkaActivationStore(producer, storeConfig, actorSystem, actorMaterializer, logging)
  }

  def createProducer(maxRequestSize: Option[ByteSize])(implicit logging: Logging,
                                                       system: ActorSystem): MessageProducer = {
    val msgProvider = SpiLoader.get[MessagingProvider]
    val whiskConfig = new WhiskConfig(WhiskConfig.kafkaHosts)
    val maxSize = maxRequestSize.getOrElse(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)
    msgProvider.getProducer(whiskConfig, Some(maxSize))
  }
}
