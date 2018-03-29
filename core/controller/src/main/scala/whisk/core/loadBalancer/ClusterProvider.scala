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

package whisk.core.loadBalancer

import akka.actor.ActorSystem
import akka.actor.Address
import akka.cluster.Cluster
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import pureconfig._
import scala.collection.immutable.Seq
import whisk.core.ConfigKeys
import whisk.core.WhiskConfig

case class ClusterProviderConfig(useClusterBootstrap: Boolean)

object ClusterProvider {
  def joinCluster(config: WhiskConfig, actorSystem: ActorSystem) = {
    val clusterConfig: ClusterProviderConfig = loadConfigOrThrow[ClusterProviderConfig](ConfigKeys.cluster)
    if (clusterConfig.useClusterBootstrap) {
      AkkaManagement(actorSystem).start()
      ClusterBootstrap(actorSystem).start()
    } else {
      Cluster(actorSystem).joinSeedNodes(
        new StaticSeedNodesProvider(config.controllerSeedNodes, actorSystem.name).getSeedNodes())
    }
  }
}

class StaticSeedNodesProvider(seedNodes: String, actorSystemName: String) {
  def getSeedNodes(): Seq[Address] = {
    seedNodes
      .split(' ')
      .flatMap { rawNodes =>
        val ipWithPort = rawNodes.split(":")
        ipWithPort match {
          case Array(host, port) => Seq(Address("akka.tcp", actorSystemName, host, port.toInt))
          case _                 => Seq.empty[Address]
        }
      }
      .toIndexedSeq
  }
}
