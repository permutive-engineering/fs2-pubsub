package fs2.pubsub.grpc

import scalapb.TypeMapper

package object internal {

  implicit val FloatValueTypeMapper: TypeMapper[FloatValue, Float] =
    TypeMapper[FloatValue, Float](_.value)(FloatValue.apply(_))

  implicit val Int64ValueTypeMapper: TypeMapper[Int64Value, Long] =
    TypeMapper[Int64Value, Long](_.value)(Int64Value.apply(_))

  implicit val UInt64ValueTypeMapper: TypeMapper[UInt64Value, Long] =
    TypeMapper[UInt64Value, Long](_.value)(UInt64Value.apply(_))

  implicit val Int32ValueTypeMapper: TypeMapper[Int32Value, Int] =
    TypeMapper[Int32Value, Int](_.value)(Int32Value.apply(_))

  implicit val UInt32ValueTypeMapper: TypeMapper[UInt32Value, Int] =
    TypeMapper[UInt32Value, Int](_.value)(UInt32Value.apply(_))

}
