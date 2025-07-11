/*
 * Copyright 2019-2025 Permutive Ltd. <https://permutive.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2.pubsub

import cats._
import cats.syntax.all._

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

/** Represents a subscription to a Pub/Sub topic. */
final class Subscription(val value: String) extends AnyVal {

  override def toString(): String = value // scalafix:ok

}

object Subscription {

  def apply(string: String): Subscription = new Subscription(string)

  def unapply(value: Subscription): Some[String] = Some(value.value)

  // Cats instances

  implicit val SubscriptionShow: Show[Subscription] = Show[String].contramap(_.value)

  implicit val SubscriptionEqHashOrder: Eq[Subscription] with Hash[Subscription] with Order[Subscription] =
    new Eq[Subscription] with Hash[Subscription] with Order[Subscription] {

      override def hash(x: Subscription): Int = Hash[String].hash(x.value)

      override def compare(x: Subscription, y: Subscription): Int = Order[String].compare(x.value, y.value)

    }

  // Circe instances

  implicit val SubscriptionCodec: Codec[Subscription] =
    Codec.from(Decoder[String].map(Subscription(_)), Encoder[String].contramap(_.value))

}
