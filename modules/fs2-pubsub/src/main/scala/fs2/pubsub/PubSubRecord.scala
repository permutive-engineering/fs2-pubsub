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

import java.time.Instant

import cats.Show
import cats.syntax.all._

object PubSubRecord {

  /** Represents a message that has been received from a Pub/Sub subscription.
    *
    * @param value
    *   the message payload
    * @param attributes
    *   the message attributes
    * @param messageId
    *   the unique identifier for the message
    * @param publishTime
    *   the time at which the message was published
    * @param ackId
    *   the unique identifier for the acknowledgment of the message
    * @param ack
    *   the function to acknowledge the message
    * @param nack
    *   the function to negatively acknowledge the message
    * @param extendDeadline
    *   the function to extend the deadline for acknowledging the message
    */
  sealed abstract class Subscriber[F[_], +A] private (
      val value: Option[A],
      val attributes: Map[String, String],
      val messageId: Option[MessageId],
      val publishTime: Option[Instant],
      val ackId: AckId,
      val ack: F[Unit],
      val nack: F[Unit],
      val extendDeadline: AckDeadline => F[Unit]
  ) {

    private def copy[B](
        value: Option[B] = this.value,
        attributes: Map[String, String] = this.attributes,
        messageId: Option[MessageId] = this.messageId,
        publishTime: Option[Instant] = this.publishTime,
        ackId: AckId = this.ackId,
        ack: F[Unit] = this.ack,
        nack: F[Unit] = this.nack,
        extendDeadline: AckDeadline => F[Unit] = this.extendDeadline
    ): Subscriber[F, B] = Subscriber(value, attributes, messageId, publishTime, ackId, ack, nack, extendDeadline)

    @SuppressWarnings(Array("scalafix:DisableSyntax.==", "scalafix:Disable.equals"))
    override def equals(obj: Any): Boolean = obj match {
      case record: Subscriber[_, _] =>
        this.value == record.value && this.attributes === record.attributes && this.messageId === record.messageId &&
        this.publishTime == record.publishTime && this.ackId === record.ackId
      case _ => false
    }

    @SuppressWarnings(Array("scalafix:Disable.toString"))
    override def toString(): String = s"PubSubRecord.Subscriber($value, $attributes, $messageId, $publishTime, $ackId)"

    /** Updates the function to acknowledge the message */
    def withAck(f: F[Unit]): PubSubRecord.Subscriber[F, A] = copy(ack = f)

    /** Updates the function to negatively acknowledge the message */
    def withNack(f: F[Unit]): PubSubRecord.Subscriber[F, A] = copy(nack = f)

    /** Contramaps the value of the message from type `B` to type `A` using the provided function.
      *
      * @param f
      *   the function to contramap the value of the message from type `B` to type `A`
      * @return
      *   a new `PubSubRecord.Subscriber` instance for the contramapped type `B`
      */
    def map[B](f: A => B): Subscriber[F, B] = copy(value = value.map(f))

    /** Maps the value of the message from type `A` to type `B` using the provided function that may result in an
      * `Either`.
      *
      * @param f
      *   the function that may result in an `Either` value for mapping to type `B`
      * @return
      *   a new `PubSubRecord.Subscriber` instance for the mapped type `B`
      */
    def emap[B](f: A => Either[Throwable, B]): Either[Throwable, Subscriber[F, B]] =
      value
        .map(f(_).map(v => copy(value = v.some)))
        .getOrElse(copy(value = None).asRight)

  }

  object Subscriber {

    /** Creates a new `PubSubRecord.Subscriber` from the provided parameters.
      *
      * @param value
      *   the message payload
      * @param attributes
      *   the message attributes
      * @param messageId
      *   the unique identifier for the message
      * @param publishTime
      *   the time at which the message was published
      * @param ackId
      *   the unique identifier for the acknowledgment of the message
      * @param ack
      *   the function to acknowledge the message
      * @param nack
      *   the function to negatively acknowledge the message
      * @param extendDeadline
      *   the function to extend the deadline for acknowledging the message
      * @return
      *   a new `PubSubRecord.Subscriber` instance
      */
    def apply[F[_], A](
        value: Option[A],
        attributes: Map[String, String],
        messageId: Option[MessageId],
        publishTime: Option[Instant],
        ackId: AckId,
        ack: F[Unit],
        nack: F[Unit],
        extendDeadline: AckDeadline => F[Unit]
    ): PubSubRecord.Subscriber[F, A] =
      new PubSubRecord.Subscriber(value, attributes, messageId, publishTime, ackId, ack, nack, extendDeadline) {}

    // format: off
    def unapply[F[_], A](record: PubSubRecord.Subscriber[F, A]): Some[(Option[A], Map[String, String], Option[MessageId], Option[Instant], AckId, F[Unit], F[Unit], AckDeadline => F[Unit])] =
      Some((record.value, record.attributes, record.messageId, record.publishTime, record.ackId, record.ack, record.nack, record.extendDeadline))
    // format: on

    implicit def show[F[_], A: Show]: Show[PubSubRecord.Subscriber[F, A]] = record =>
      show"PubSubRecord.Subscriber(${record.value}, ${record.attributes}, ${record.messageId}, ${s"${record.publishTime}"}, ${record.ackId})"

    implicit class PubSubRecordSubscriberSyntax[F[_]](subscriber: PubSubRecord.Subscriber[F, Array[Byte]]) {

      /** Decodes the message payload of the `PubSubRecord.Subscriber` into the specified message type.
        *
        * @tparam B
        *   the type of message to be decoded
        * @return
        *   either the decoded message of type `B` or an exception if the decoding fails
        */
      def as[B: MessageDecoder]: Either[Throwable, Subscriber[F, B]] = subscriber.emap(MessageDecoder[B].decode)

    }

  }

  /** Represents a message that is to be published to a Pub/Sub topic.
    *
    * @param data
    *   the message payload
    * @param attributes
    *   the message attributes
    */
  sealed abstract class Publisher[A] private (val data: A, val attributes: Map[String, String]) {

    private def copy[B](
        data: B = this.data,
        attributes: Map[String, String] = this.attributes
    ): Publisher[B] = Publisher(data, attributes)

    @SuppressWarnings(Array("scalafix:DisableSyntax.==", "scalafix:Disable.equals"))
    override def equals(obj: Any): Boolean = obj match {
      case record: Publisher[_] => this.data == record.data && this.attributes === record.attributes
      case _                    => false
    }

    @SuppressWarnings(Array("scalafix:Disable.toString"))
    override def toString(): String = s"PubSubRecord.Publisher($data, $attributes)"

    /** Updates the message payload of the record */
    def withData[B](data: B): Publisher[B] =
      copy(data = data)

    /** Adds a new attribute to the record */
    def withAttribute(key: String, value: String): Publisher[A] =
      withAttributes((key, value))

    /** Adds multiple attributes to the record */
    def withAttributes(first: (String, String), rest: (String, String)*): Publisher[A] =
      withAttributes((first +: rest).toMap)

    /** Adds multiple attributes to the record */
    def withAttributes(attributes: Map[String, String]): Publisher[A] =
      copy(attributes = this.attributes ++ attributes)

    /** Adds a callback to the record. The resulting record can only be published with an async `PubSubPublisher`
      *
      * @see
      *   [[PubSubPublisher.Async]]
      */
    def withCallback[F[_]](callback: Either[Throwable, Unit] => F[Unit]): Publisher.WithCallback[F, A] =
      Publisher.WithCallback[F, A](this.data, this.attributes, callback)

  }

  object Publisher {

    /** Creates a new `PubSubRecord.Publisher` from the provided parameters.
      *
      * @param data
      *   the message payload
      * @return
      *   a new `PubSubRecord.Publisher` instance
      */
    def apply[A](data: A): Publisher[A] = Publisher[A](data, Map.empty[String, String])

    /** Creates a new `PubSubRecord.Publisher` from the provided parameters.
      *
      * @param data
      *   the message payload
      * @param attributes
      *   the message attributes
      * @return
      *   a new `PubSubRecord.Publisher` instance
      */
    def apply[A](data: A, attributes: Map[String, String]): Publisher[A] = new Publisher[A](data, attributes) {}

    def unapply[A](record: PubSubRecord.Publisher[A]): Some[(A, Map[String, String])] =
      Some((record.data, record.attributes))

    implicit def show[A: Show]: Show[PubSubRecord.Publisher[A]] = record =>
      show"PubSubRecord.Publisher(${record.data}, ${record.attributes})"

    /** Represents a message that is to be published asynchronously to a Pub/Sub topic.
      *
      * @param data
      *   the message payload
      * @param attributes
      *   the message attributes
      * @param callback
      *   the callback to be executed after the message has been published
      */
    sealed abstract class WithCallback[F[_], A] private (
        val data: A,
        val attributes: Map[String, String],
        val callback: Either[Throwable, Unit] => F[Unit]
    ) {

      private def copy[B](
          data: B = this.data,
          attributes: Map[String, String] = this.attributes,
          callback: Either[Throwable, Unit] => F[Unit] = this.callback
      ): WithCallback[F, B] = WithCallback[F, B](data, attributes, callback)

      @SuppressWarnings(Array("scalafix:DisableSyntax.==", "scalafix:Disable.equals"))
      override def equals(obj: Any): Boolean = obj match {
        case record: Publisher.WithCallback[_, _] => this.data == record.data && this.attributes === record.attributes
        case _                                    => false
      }

      @SuppressWarnings(Array("scalafix:Disable.toString"))
      override def toString(): String = s"PubSubRecord.Publisher.WithCallback($data, $attributes)"

      /** Removes the callback from the record. The resulting record can only be published with a normal
        * `PubSubPublisher`.
        */
      def noCallback: Publisher[A] = Publisher[A](this.data, this.attributes)

      /** Adds an attribute to the record. */
      def withAttribute(key: String, value: String): Publisher.WithCallback[F, A] =
        withAttributes((key, value))

      /** Adds multiple attributes to the record */
      def withAttributes(first: (String, String), rest: (String, String)*): Publisher.WithCallback[F, A] =
        withAttributes((first +: rest).toMap)

      /** Adds multiple attributes to the record */
      def withAttributes(attributes: Map[String, String]): Publisher.WithCallback[F, A] =
        copy[A](attributes = this.attributes ++ attributes)

      /** Updates the record's callback */
      def withCallback(callback: Either[Throwable, Unit] => F[Unit]): Publisher.WithCallback[F, A] =
        copy[A](callback = callback)

    }

    object WithCallback {

      /** Creates a new `PubSubRecord.Publisher.WithCallback` from the provided parameters.
        *
        * @param data
        *   the message payload
        * @param attributes
        *   the message attributes
        * @param callback
        *   the callback to be executed after the message has been published
        * @return
        *   a new `PubSubRecord.Publisher.WithCallback` instance
        */
      def apply[F[_], A](
          data: A,
          attributes: Map[String, String],
          callback: Either[Throwable, Unit] => F[Unit]
      ): WithCallback[F, A] = new WithCallback[F, A](data, attributes, callback) {}

      def unapply[F[_], A](
          record: PubSubRecord.Publisher.WithCallback[F, A]
      ): Some[(A, Map[String, String], Either[Throwable, Unit] => F[Unit])] =
        Some((record.data, record.attributes, record.callback))

      implicit def show[A: Show]: Show[PubSubRecord.Publisher[A]] = record =>
        show"PubSubRecord.Publisher.WithCallback(${record.data}, ${record.attributes})"

    }

  }

}
