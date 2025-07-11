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

import java.nio.charset.StandardCharsets.UTF_8

import cats.Functor
import cats.syntax.all._

import io.circe.Json

/** Represents a typeclass capable of decoding a target messaage type from an array of bytes.
  *
  * @tparam A
  *   the type of message to be decoded
  */
trait MessageDecoder[A] {

  /** Decodes the input array of bytes into the specified message type.
    *
    * @param message
    *   the byte array to be decoded
    * @return
    *   either the decoded message of type `A` or an exception if the decoding fails
    */
  def decode(message: Array[Byte]): Either[Throwable, A]

  /** Maps the decoded message of type `A` to a new type `B` using the provided function.
    *
    * @param f
    *   the function to map the decoded message to another type
    * @return
    *   a new `MessageDecoder` instance for the mapped type `B`
    */
  def map[B](f: A => B): MessageDecoder[B] = MessageDecoder.functor.map(this)(f)

  /** Maps the decoded message of type A to a new type B using the provided function that may result in an `Either`.
    *
    * @param f
    *   the function that may result in an `Either` value for mapping to type `B`
    * @return
    *   a new `MessageDecoder` instance for the mapped type `B`
    */
  def emap[B](f: A => Either[Throwable, B]): MessageDecoder[B] = this.decode(_).flatMap(f)

}

object MessageDecoder {

  def apply[A: MessageDecoder]: MessageDecoder[A] = implicitly

  /** Creates a new `MessageDecoder` instance for the specified type `A` using the provided decoding function.
    *
    * @param f
    *   the decoding function for the specified type `A`
    * @tparam A
    *   the type of message to be decoded
    * @return
    *   a new `MessageDecoder` instance for the specified type `A`
    */
  def instance[A](f: Array[Byte] => Either[Throwable, A]): MessageDecoder[A] = bytes => f(bytes)

  implicit val string: MessageDecoder[String] = bytes => Either.catchNonFatal(new String(bytes, UTF_8))

  implicit val byteArray: MessageDecoder[Array[Byte]] = _.asRight

  implicit val json: MessageDecoder[Json] = io.circe.jawn.decodeByteArray[Json](_)

  implicit val functor: Functor[MessageDecoder] = new Functor[MessageDecoder] {

    override def map[A, B](fa: MessageDecoder[A])(f: A => B): MessageDecoder[B] =
      (message: Array[Byte]) => fa.decode(message).map(f)

  }

}
