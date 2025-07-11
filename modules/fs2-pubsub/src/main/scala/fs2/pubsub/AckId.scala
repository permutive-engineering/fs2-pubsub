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

/** Represents the unique identifier for the acknowledgment of a message. */
final class AckId(val value: String) extends AnyVal {

  override def toString(): String = value // scalafix:ok

}

object AckId {

  def apply(string: String): AckId = new AckId(string)

  def unapply(value: AckId): Some[String] = Some(value.value)

  // Cats instances

  implicit val AckIdShow: Show[AckId] = Show[String].contramap(_.value)

  implicit val AckIdEqHashOrder: Eq[AckId] with Hash[AckId] with Order[AckId] =
    new Eq[AckId] with Hash[AckId] with Order[AckId] {

      override def hash(x: AckId): Int = Hash[String].hash(x.value)

      override def compare(x: AckId, y: AckId): Int = Order[String].compare(x.value, y.value)

    }

  // Circe instances

  implicit val AckIdCodec: Codec[AckId] = Codec.from(Decoder[String].map(AckId(_)), Encoder[String].contramap(_.value))

}
