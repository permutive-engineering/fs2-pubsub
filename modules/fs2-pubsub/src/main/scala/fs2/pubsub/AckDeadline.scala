/*
 * Copyright 2019-2026 Permutive Ltd. <https://permutive.com>
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

import scala.concurrent.duration._

import cats._
import cats.syntax.all._

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

/** Represents the duration for which the Pub/Sub system will wait for the subscriber to acknowledge the message before
  * redelivering it.
  */
final class AckDeadline private (val value: FiniteDuration) extends AnyVal {

  override def toString(): String = value.toString() // scalafix:ok

}

object AckDeadline {

  lazy val zero: AckDeadline = new AckDeadline(0.seconds)

  /** Creates a new `AckDeadline` from the provided `FiniteDuration`.
    *
    * @param duration
    *   the duration for which the Pub/Sub system will wait for the subscriber to acknowledge the message
    * @return
    *   a `Right` with the `AckDeadline` if the duration is between 0 and 600 seconds (both included), a `Left`
    *   otherwise
    */
  def from(duration: FiniteDuration): Either[String, AckDeadline] =
    if (duration >= 0.seconds && duration <= 600.seconds) new AckDeadline(duration).asRight
    else show"AckDeadline must be between 0 and 600 seconds (both included), but was: $duration".asLeft

  def unapply(value: AckDeadline): Some[FiniteDuration] = Some(value.value)

  // Cats instances

  implicit val DeadlineShow: Show[AckDeadline] = Show[FiniteDuration].contramap(_.value)

  implicit val DeadlineEqHashOrder: Eq[AckDeadline] with Hash[AckDeadline] with Order[AckDeadline] =
    new Eq[AckDeadline] with Hash[AckDeadline] with Order[AckDeadline] {

      override def hash(x: AckDeadline): Int = Hash[FiniteDuration].hash(x.value)

      override def compare(x: AckDeadline, y: AckDeadline): Int = Order[FiniteDuration].compare(x.value, y.value)

    }

  // Circe instances

  implicit val DeadlineCodec: Codec[AckDeadline] =
    Codec.from(
      Decoder[String].map(Duration(_)).emap {
        case duration: FiniteDuration => AckDeadline.from(duration)
        case other                    => show"$other wasn't a valid `FiniteDuration`".asLeft
      },
      Encoder[String].contramap(_.value.toString()) // scalafix:ok
    )

}
