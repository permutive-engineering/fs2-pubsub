/*
 * Copyright 2019-2024 Permutive Ltd. <https://permutive.com>
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

/** Represents a Pub/Sub topic. */
final class Topic(val value: String) extends AnyVal {

  override def toString(): String = value // scalafix:ok

}

object Topic {

  def apply(string: String): Topic = new Topic(string)

  def unapply(value: Topic): Some[String] = Some(value.value)

  // Cats instances

  implicit val TopicShow: Show[Topic] = Show[String].contramap(_.value)

  implicit val TopicEqHashOrder: Eq[Topic] with Hash[Topic] with Order[Topic] =
    new Eq[Topic] with Hash[Topic] with Order[Topic] {

      override def hash(x: Topic): Int = Hash[String].hash(x.value)

      override def compare(x: Topic, y: Topic): Int = Order[String].compare(x.value, y.value)

    }

  // Circe instances

  implicit val TopicCodec: Codec[Topic] = Codec.from(Decoder[String].map(Topic(_)), Encoder[String].contramap(_.value))

}
