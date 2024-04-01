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

import java.nio.charset.StandardCharsets

import cats.Contravariant

import io.circe.Json

/** Represents a typeclass capable of encoding a value of type `A` into an array of bytes.
  *
  * @tparam A
  *   the type of message to be encoded
  */
trait MessageEncoder[A] {

  /** Encodes the input value of type `A` into an array of bytes.
    *
    * @param a
    *   the value to be encoded
    * @return
    *   the encoded byte array representing the input value
    */
  def encode(a: A): Array[Byte]

  /** Contramaps the encoding process from a type `B` to the original type `A` using the provided function.
    *
    * @param f
    *   the function to contramap the encoding process from type `B` to type `A`
    * @return
    *   a new `MessageEncoder` instance for the contramapped type `B`
    */
  def contramap[B](f: B => A): MessageEncoder[B] = b => this.encode(f(b))

}

object MessageEncoder {

  def apply[A: MessageEncoder]: MessageEncoder[A] = implicitly

  /** Creates a new `MessageEncoder` instance for the specified type `A` using the provided encoding function.
    *
    * @param f
    *   the encoding function for the specified type `A`
    * @tparam A
    *   the type of message to be encoded
    * @return
    *   a new `MessageEncoder` instance for the specified type `A`
    */
  def instance[A](f: A => Array[Byte]): MessageEncoder[A] = a => f(a)

  implicit val string: MessageEncoder[String] = _.getBytes(StandardCharsets.UTF_8)

  implicit val json: MessageEncoder[Json] = string.contramap(_.noSpaces)

  implicit def option[A: MessageEncoder]: MessageEncoder[Option[A]] = {
    case Some(a) => MessageEncoder[A].encode(a)
    case None    => Array()
  }

  implicit val MessageEncoderContravariant: Contravariant[MessageEncoder] = new Contravariant[MessageEncoder] {

    override def contramap[A, B](fa: MessageEncoder[A])(f: B => A): MessageEncoder[B] = b => fa.encode(f(b))

  }

}
