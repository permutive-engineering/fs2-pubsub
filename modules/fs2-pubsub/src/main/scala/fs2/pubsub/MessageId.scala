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

/** Represents the unique identifier for a message. */
final class MessageId(val value: String) extends AnyVal {

  override def toString(): String = value // scalafix:ok

}

object MessageId {

  def apply(string: String): MessageId = new MessageId(string)

  def unapply(value: MessageId): Some[String] = Some(value.value)

  // Cats instances

  implicit val MessageIdShow: Show[MessageId] = Show[String].contramap(_.value)

  implicit val MessageIdEqHashOrder: Eq[MessageId] with Hash[MessageId] with Order[MessageId] =
    new Eq[MessageId] with Hash[MessageId] with Order[MessageId] {

      override def hash(x: MessageId): Int = Hash[String].hash(x.value)

      override def compare(x: MessageId, y: MessageId): Int = Order[String].compare(x.value, y.value)

    }

  // Circe instances

  implicit val MessageIdCodec: Codec[MessageId] =
    Codec.from(Decoder[String].map(MessageId(_)), Encoder[String].contramap(_.value))

}
