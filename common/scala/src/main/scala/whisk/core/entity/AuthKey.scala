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

package whisk.core.entity

import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * Authentication key, consisting of a UUID and Secret.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param k (uuid, key) the uuid and key, assured to be non-null because both types are values
 */
protected[core] class AuthKey private (private val k: (UUID, Secret)) extends AnyVal {
  def uuid: UUID = k._1
  def key: Secret = k._2
  def revoke = new AuthKey(uuid, Secret())
  def compact: String = s"$uuid:$key"
  override def toString: String = uuid.toString
}

protected[core] object AuthKey {

  /**
   * Creates AuthKey.
   *
   * @param uuid the uuid, assured to be non-null because UUID is a value
   * @param key the key, assured to be non-null because Secret is a value
   */
  protected[core] def apply(uuid: UUID, key: Secret): AuthKey = new AuthKey(uuid, key)

  /**
   * Creates an auth key for a randomly generated UUID with a randomly generated secret.
   *
   * @return AuthKey
   */
  protected[core] def apply(): AuthKey = new AuthKey(UUID(), Secret())

  /**
   * Creates AuthKey from a string where the uuid and key are separated by a colon.
   * If the string contains more than one colon, all values are ignored except for
   * the first two hence "k:v*" produces ("k","v").
   *
   * @param str the string containing uuid and key separated by colon
   * @return AuthKey if argument is properly formatted
   * @throws IllegalArgumentException if argument is not well formed
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(str: String): AuthKey = {
    val (uuid, secret) = str.split(':').toList match {
      case k :: v :: _ => (k, v)
      case k :: Nil    => (k, "")
      case Nil         => ("", "")
    }

    new AuthKey(UUID(uuid.trim), Secret(secret.trim))
  }

  protected[core] implicit val serdes: RootJsonFormat[AuthKey] = new RootJsonFormat[AuthKey] {
    def write(k: AuthKey) = JsString(k.compact)
    def read(value: JsValue) = AuthKey(value.convertTo[String])
  }
}
